package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.*;
import com.clinicops.app.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SaleServiceTest {

    @Autowired SaleService saleService;
    @Autowired InventoryService inventoryService;
    @Autowired ClinicRepository clinicRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired SaleItemRepository saleItemRepository;

    Long clinicId;
    Long userId;               // real app_user row: sale.created_by is an FK on PostgreSQL
    Long patientId;
    Medicine medicine;
    StockBatch oldBatch;       // expires sooner (FEFO target)
    StockBatch newBatch;       // expires later

    @BeforeEach
    void setUp() {
        Clinic c = clinicRepository.save(
                Clinic.builder().code("sale-test").name("Sale Test").build());
        clinicId = c.getId();
        userId = appUserRepository.save(AppUser.builder().clinicId(clinicId).username("sale-user-" + System.nanoTime())
                .fullName("Pharmacist").role(Role.PHARMACIST).passwordHash("n/a").enabled(true).build()).getId();

        Patient p = patientRepository.save(Patient.builder()
                .clinicId(clinicId).name("Test Patient").phone("9876543210").mrn("MRN-TEST-1")
                .build());
        patientId = p.getId();

        medicine = inventoryService.createMedicine(clinicId,
                Medicine.builder().name("Crocin").strength("500mg").manufacturer("GSK").build());

        // Two batches — earlier expiry first (FEFO should pick this)
        oldBatch = stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinicId).medicineId(medicine.getId()).batchNumber("OLD")
                .expiryDate(LocalDate.now().plusDays(30))
                .quantity(20).initialQuantity(20)
                .salePrice(new BigDecimal("2.00"))
                .receivedDate(LocalDate.now())
                .build());

        newBatch = stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinicId).medicineId(medicine.getId()).batchNumber("NEW")
                .expiryDate(LocalDate.now().plusDays(180))
                .quantity(50).initialQuantity(50)
                .salePrice(new BigDecimal("2.00"))
                .receivedDate(LocalDate.now())
                .build());
    }

    @Test
    void record_picks_earliest_expiring_batch_first_FEFO() {
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 5)), null, null);

        assertThat(sale.getId()).isNotNull();

        List<SaleItem> items = saleItemRepository.findBySaleId(sale.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getStockBatchId()).isEqualTo(oldBatch.getId());

        // 5 dispensed from old batch → 15 left
        assertThat(stockBatchRepository.findById(oldBatch.getId()).orElseThrow().getQuantity())
                .isEqualTo(15);
        // New batch untouched
        assertThat(stockBatchRepository.findById(newBatch.getId()).orElseThrow().getQuantity())
                .isEqualTo(50);
    }

    @Test
    void record_skips_expired_batches() {
        // Convert the old batch to expired
        oldBatch.setExpiryDate(LocalDate.now().minusDays(1));
        stockBatchRepository.save(oldBatch);

        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 5)), null, null);

        List<SaleItem> items = saleItemRepository.findBySaleId(sale.getId());
        // Should pull from NEW (non-expired) batch, not the expired OLD one
        assertThat(items.get(0).getStockBatchId()).isEqualTo(newBatch.getId());
        assertThat(stockBatchRepository.findById(oldBatch.getId()).orElseThrow().getQuantity())
                .isEqualTo(20); // unchanged
    }

    @Test
    void record_spans_multiple_batches_when_one_insufficient() {
        // 25 requested; old has 20 → take 20 from old, 5 from new
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 25)), null, null);

        List<SaleItem> items = saleItemRepository.findBySaleId(sale.getId());
        assertThat(items).hasSize(2);
        assertThat(items)
                .extracting(SaleItem::getStockBatchId, SaleItem::getQuantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(oldBatch.getId(), 20),
                        org.assertj.core.groups.Tuple.tuple(newBatch.getId(), 5));

        assertThat(stockBatchRepository.findById(oldBatch.getId()).orElseThrow().getQuantity()).isZero();
        assertThat(stockBatchRepository.findById(newBatch.getId()).orElseThrow().getQuantity()).isEqualTo(45);
    }

    @Test
    void record_throws_when_total_stock_insufficient() {
        // Total available = 20 + 50 = 70. Ask for 100.
        assertThatThrownBy(() -> saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 100)), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    void record_walkin_persists_name_when_no_patient() {
        Sale sale = saleService.record(clinicId, userId, null, null, "CASH", "Walk-in Mr X", "9876543210",
                List.of(SaleService.SaleLine.of(medicine.getId(), 1)), null, null);

        assertThat(sale.getPatientId()).isNull();
        assertThat(sale.getWalkInName()).isEqualTo("Walk-in Mr X");
        assertThat(sale.getWalkInPhone()).isEqualTo("9876543210");
    }

    @Test
    void record_ignores_walkin_when_patient_selected() {
        // If both patient AND walk-in name passed, patient wins.
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", "Should be ignored", null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 1)), null, null);

        assertThat(sale.getPatientId()).isEqualTo(patientId);
        assertThat(sale.getWalkInName()).isNull();
    }

    @Test
    void record_throws_on_invalid_walkin_phone() {
        assertThatThrownBy(() -> saleService.record(clinicId, userId, null, null, "CASH",
                "Walk-in", "12345", // < 10 digits and starts with 1
                List.of(SaleService.SaleLine.of(medicine.getId(), 1)), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void record_computes_total_amount_correctly() {
        // unit price 2.00, qty 5 → 10.00
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 5)), null, null);

        assertThat(sale.getTotalAmount()).isEqualByComparingTo("10.00");
    }

    // ===== Discount tests =====

    @Test
    void record_applies_line_percent_discount() {
        // qty 5 @ ₹2 = ₹10 gross; 10% off = ₹1; net = ₹9
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(new SaleService.SaleLine(medicine.getId(), 5, "PERCENT", new BigDecimal("10"))),
                null, null);

        List<SaleItem> items = saleItemRepository.findBySaleId(sale.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getGrossAmount()).isEqualByComparingTo("10.00");
        assertThat(items.get(0).getDiscountAmount()).isEqualByComparingTo("1.00");
        assertThat(items.get(0).getLineTotal()).isEqualByComparingTo("9.00");

        assertThat(sale.getSubtotalAmount()).isEqualByComparingTo("9.00");
        assertThat(sale.getBillDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("9.00");
    }

    @Test
    void record_applies_line_flat_amount_discount() {
        // qty 5 @ ₹2 = ₹10; flat ₹3 off; net = ₹7
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(new SaleService.SaleLine(medicine.getId(), 5, "AMOUNT", new BigDecimal("3"))),
                null, null);

        List<SaleItem> items = saleItemRepository.findBySaleId(sale.getId());
        assertThat(items.get(0).getDiscountAmount()).isEqualByComparingTo("3.00");
        assertThat(items.get(0).getLineTotal()).isEqualByComparingTo("7.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("7.00");
    }

    @Test
    void record_caps_line_amount_discount_at_gross() {
        // Flat ₹50 off a ₹10 line → caps at ₹10 (free), not negative
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(new SaleService.SaleLine(medicine.getId(), 5, "AMOUNT", new BigDecimal("50"))),
                null, null);

        assertThat(saleItemRepository.findBySaleId(sale.getId()).get(0).getLineTotal())
                .isEqualByComparingTo("0.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void record_applies_bill_percent_discount() {
        // qty 5 @ ₹2 = ₹10; no line discount; 20% off bill = ₹2 off; net = ₹8
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 5)),
                "PERCENT", new BigDecimal("20"));

        assertThat(sale.getSubtotalAmount()).isEqualByComparingTo("10.00");
        assertThat(sale.getBillDiscountAmount()).isEqualByComparingTo("2.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("8.00");
        assertThat(sale.getBillDiscountType()).isEqualTo("PERCENT");
    }

    @Test
    void record_applies_both_line_and_bill_discount() {
        // qty 5 @ ₹2 = ₹10 gross; 10% line off → ₹9; bill ₹1 off → ₹8
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(new SaleService.SaleLine(medicine.getId(), 5, "PERCENT", new BigDecimal("10"))),
                "AMOUNT", new BigDecimal("1"));

        assertThat(sale.getSubtotalAmount()).isEqualByComparingTo("9.00");
        assertThat(sale.getBillDiscountAmount()).isEqualByComparingTo("1.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("8.00");
    }

    @Test
    void record_splits_line_amount_discount_proportionally_across_batches() {
        // qty 25 spans both batches: 20 from old (₹40 gross) + 5 from new (₹10 gross), total ₹50.
        // Flat ₹10 line discount → 80% (₹8) on old item, 20% (₹2) on new item.
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(new SaleService.SaleLine(medicine.getId(), 25, "AMOUNT", new BigDecimal("10"))),
                null, null);

        List<SaleItem> items = saleItemRepository.findBySaleId(sale.getId());
        assertThat(items).hasSize(2);

        BigDecimal totalDiscount = items.stream()
                .map(SaleItem::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDiscount).isEqualByComparingTo("10.00");
        assertThat(sale.getSubtotalAmount()).isEqualByComparingTo("40.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void record_ignores_line_discount_with_zero_value() {
        // Zero / negative discount value should be silently dropped (no discount stored)
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(new SaleService.SaleLine(medicine.getId(), 5, "PERCENT", BigDecimal.ZERO)),
                null, null);

        SaleItem item = saleItemRepository.findBySaleId(sale.getId()).get(0);
        assertThat(item.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(item.getLineTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void record_caps_bill_percent_at_100() {
        // 150% capped at 100% → entire bill discounted
        Sale sale = saleService.record(clinicId, userId, patientId, null, "CASH", null, null,
                List.of(SaleService.SaleLine.of(medicine.getId(), 5)),
                "PERCENT", new BigDecimal("150"));

        assertThat(sale.getTotalAmount()).isEqualByComparingTo("0.00");
    }
}
