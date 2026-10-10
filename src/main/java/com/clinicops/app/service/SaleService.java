package com.clinicops.app.service;

import com.clinicops.app.domain.*;
import com.clinicops.app.repository.*;
import com.clinicops.app.tenant.CrossTenantAccessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId; // used for startOfDay calculation
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SaleService {

    public static final String DISCOUNT_PERCENT = "PERCENT";
    public static final String DISCOUNT_AMOUNT  = "AMOUNT";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final MedicineRepository medicineRepository;
    private final MedicalIdSequence medicalIdSequence;
    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;

    private static final java.util.regex.Pattern WALK_IN_PHONE =
            java.util.regex.Pattern.compile(com.clinicops.app.util.ValidationPatterns.PHONE_INDIA_OPTIONAL);

    /** Record a sale using FEFO (first-expiry-first-out) batch allocation. */
    @Transactional
    public Sale record(Long clinicId, Long userId, Long patientId, Long visitId,
                       String paymentMode, String walkInName, String walkInPhone,
                       List<SaleLine> lines,
                       String billDiscountType, BigDecimal billDiscountValue) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Sale must contain at least one line");
        }

        // Patient / visit references must belong to the billing clinic. Compared against the
        // explicit clinicId (not just TenantContext) so the guard holds for every caller.
        if (patientId != null) {
            Patient patient = patientRepository.findById(patientId)
                    .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));
            if (!clinicId.equals(patient.getClinicId())) throw new CrossTenantAccessException();
        }
        if (visitId != null) {
            Visit visit = visitRepository.findById(visitId)
                    .orElseThrow(() -> new NotFoundException("Visit not found: " + visitId));
            if (!clinicId.equals(visit.getClinicId())) throw new CrossTenantAccessException();
        }

        // If patient is selected, walk-in fields are ignored (patient wins)
        if (patientId != null) {
            walkInName = null;
            walkInPhone = null;
        }

        if (walkInPhone != null && !walkInPhone.isBlank() && !WALK_IN_PHONE.matcher(walkInPhone).matches()) {
            throw new IllegalArgumentException("Walk-in phone must be a 10-digit mobile starting with 6/7/8/9");
        }

        Sale sale = saleRepository.save(Sale.builder()
                .clinicId(clinicId)
                .billNo(medicalIdSequence.nextBillNo())
                .patientId(patientId)
                .visitId(visitId)
                .subtotalAmount(BigDecimal.ZERO)
                .billDiscountAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .paymentMode(paymentMode == null ? "CASH" : paymentMode)
                .walkInName(walkInName != null && !walkInName.isBlank() ? walkInName.trim() : null)
                .walkInPhone(walkInPhone != null && !walkInPhone.isBlank() ? walkInPhone.trim() : null)
                .createdBy(userId)
                .build());

        BigDecimal subtotal = BigDecimal.ZERO;

        for (SaleLine line : lines) {
            if (line.getQuantity() == null || line.getQuantity() <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }

            Medicine medicine = com.clinicops.app.tenant.TenantGuard.check(
                    medicineRepository.findById(line.getMedicineId())
                            .orElseThrow(() -> new NotFoundException("Medicine not found: " + line.getMedicineId())),
                    com.clinicops.app.domain.Medicine::getClinicId);
            List<StockBatch> batches = stockBatchRepository.findByClinicIdAndMedicineIdOrderByExpiryDateAsc(clinicId, line.getMedicineId());

            // First pass: FEFO-allocate batches, build SaleItem objects (not yet persisted),
            // and decrement stock. We persist after distributing the line-level discount.
            List<SaleItem> lineItems = new ArrayList<>();
            BigDecimal lineGross = BigDecimal.ZERO;
            int remaining = line.getQuantity();

            for (StockBatch batch : batches) {
                if (remaining <= 0) break;
                if (batch.getQuantity() == null || batch.getQuantity() <= 0) continue;
                if (batch.getExpiryDate().isBefore(LocalDate.now())) continue;

                int take = Math.min(remaining, batch.getQuantity());
                BigDecimal unitPrice = batch.getSalePrice() == null ? BigDecimal.ZERO : batch.getSalePrice();
                BigDecimal itemGross = unitPrice.multiply(BigDecimal.valueOf(take))
                        .setScale(2, RoundingMode.HALF_UP);

                SaleItem item = SaleItem.builder()
                        .clinicId(clinicId)
                        .saleId(sale.getId())
                        .stockBatchId(batch.getId())
                        .medicineId(medicine.getId())
                        .quantity(take)
                        .unitPrice(unitPrice)
                        .grossAmount(itemGross)
                        .discountType(line.getDiscountType())
                        .discountValue(line.getDiscountValue())
                        .discountAmount(BigDecimal.ZERO)
                        .lineTotal(itemGross)
                        .build();
                lineItems.add(item);
                lineGross = lineGross.add(itemGross);

                batch.setQuantity(batch.getQuantity() - take);
                stockBatchRepository.save(batch);

                stockMovementRepository.save(StockMovement.builder()
                        .clinicId(clinicId)
                        .stockBatchId(batch.getId())
                        .movementType(StockMovement.MovementType.OUT)
                        .quantity(take)
                        .referenceId(sale.getId())
                        .referenceType("SALE")
                        .createdBy(userId)
                        .build());

                remaining -= take;
            }

            if (remaining > 0) {
                throw new IllegalStateException(
                        "Insufficient stock for " + medicine.getName()
                                + " — short by " + remaining + " " + medicine.getUnit());
            }

            // Distribute the line discount proportionally across the allocated items.
            BigDecimal lineDiscount = computeDiscount(line.getDiscountType(), line.getDiscountValue(), lineGross);
            distributeDiscount(lineItems, lineDiscount, lineGross);

            for (SaleItem item : lineItems) {
                saleItemRepository.save(item);
                subtotal = subtotal.add(item.getLineTotal());
            }
        }

        BigDecimal billDiscount = computeDiscount(billDiscountType, billDiscountValue, subtotal);
        BigDecimal total = subtotal.subtract(billDiscount).setScale(2, RoundingMode.HALF_UP);
        if (total.signum() < 0) total = BigDecimal.ZERO;

        sale.setSubtotalAmount(subtotal);
        sale.setBillDiscountType(billDiscount.signum() > 0 ? billDiscountType : null);
        sale.setBillDiscountValue(billDiscount.signum() > 0 ? billDiscountValue : null);
        sale.setBillDiscountAmount(billDiscount);
        sale.setTotalAmount(total);
        return saleRepository.save(sale);
    }

    /** Compute the resolved ₹ discount given a type, raw value, and the base amount it applies to. */
    static BigDecimal computeDiscount(String type, BigDecimal value, BigDecimal base) {
        if (type == null || value == null || value.signum() <= 0 || base == null || base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal disc;
        if (DISCOUNT_PERCENT.equals(type)) {
            BigDecimal capped = value.min(HUNDRED);
            disc = base.multiply(capped).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        } else if (DISCOUNT_AMOUNT.equals(type)) {
            disc = value.min(base);
        } else {
            return BigDecimal.ZERO;
        }
        return disc.setScale(2, RoundingMode.HALF_UP);
    }

    /** Distribute a line-level discount across items by gross share. Last item takes any rounding remainder. */
    private static void distributeDiscount(List<SaleItem> items, BigDecimal totalDiscount, BigDecimal totalGross) {
        if (items.isEmpty() || totalDiscount.signum() <= 0) return;
        if (items.size() == 1) {
            SaleItem only = items.get(0);
            only.setDiscountAmount(totalDiscount);
            only.setLineTotal(only.getGrossAmount().subtract(totalDiscount).setScale(2, RoundingMode.HALF_UP));
            return;
        }
        BigDecimal accumulated = BigDecimal.ZERO;
        for (int i = 0; i < items.size() - 1; i++) {
            SaleItem item = items.get(i);
            BigDecimal share = totalDiscount
                    .multiply(item.getGrossAmount())
                    .divide(totalGross, 2, RoundingMode.HALF_UP);
            item.setDiscountAmount(share);
            item.setLineTotal(item.getGrossAmount().subtract(share).setScale(2, RoundingMode.HALF_UP));
            accumulated = accumulated.add(share);
        }
        SaleItem last = items.get(items.size() - 1);
        BigDecimal lastDisc = totalDiscount.subtract(accumulated).setScale(2, RoundingMode.HALF_UP);
        last.setDiscountAmount(lastDisc);
        last.setLineTotal(last.getGrossAmount().subtract(lastDisc).setScale(2, RoundingMode.HALF_UP));
    }

    @Transactional(readOnly = true)
    public Sale getOrThrow(Long id) {
        Sale s = saleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Sale not found: " + id));
        return com.clinicops.app.tenant.TenantGuard.check(s, Sale::getClinicId);
    }

    @Transactional(readOnly = true)
    public List<SaleItem> itemsFor(Long saleId) {
        return saleItemRepository.findBySaleId(saleId);
    }

    @Transactional(readOnly = true)
    public List<Sale> salesForPatient(Long patientId) {
        return saleRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Sale> salesPage(Long clinicId, int pageNo) {
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, pageNo), 20);
        return saleRepository.findByClinicIdOrderByCreatedAtDesc(clinicId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Sale> salesToday(Long clinicId) {
        Instant startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        // Use clinic-scoped query instead of findAll() to avoid loading cross-tenant rows
        // even if the Hibernate filter is bypassed for any reason.
        return saleRepository
                .findByClinicIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(clinicId, startOfDay);
    }

    @Value
    public static class SaleLine {
        Long medicineId;
        Integer quantity;
        String discountType;
        BigDecimal discountValue;

        /** Backwards-compatible constructor for callers that don't use line discounts. */
        public static SaleLine of(Long medicineId, Integer quantity) {
            return new SaleLine(medicineId, quantity, null, null);
        }
    }

    /**
     * Parse parallel form arrays into SaleLine objects. Discount arrays may be null
     * (no discount on any line) or undersized (the remainder treated as no discount).
     */
    public static List<SaleLine> parseLines(String[] medicineIds, String[] quantities,
                                            String[] discountTypes, String[] discountValues) {
        if (medicineIds == null || quantities == null) return List.of();
        List<SaleLine> lines = new ArrayList<>();
        int len = Math.min(medicineIds.length, quantities.length);
        for (int i = 0; i < len; i++) {
            if (medicineIds[i] == null || medicineIds[i].isBlank()) continue;
            if (quantities[i] == null || quantities[i].isBlank()) continue;
            try {
                Long mid = Long.parseLong(medicineIds[i]);
                Integer qty = Integer.parseInt(quantities[i]);
                if (qty <= 0) continue;

                String dType = (discountTypes != null && i < discountTypes.length && discountTypes[i] != null
                        && !discountTypes[i].isBlank()) ? discountTypes[i] : null;
                BigDecimal dValue = null;
                if (discountValues != null && i < discountValues.length
                        && discountValues[i] != null && !discountValues[i].isBlank()) {
                    try { dValue = new BigDecimal(discountValues[i].trim()); }
                    catch (NumberFormatException ignored) { dValue = null; }
                }
                // Sanitize: keep discount only when both type and a positive value are present
                if (dType == null || dValue == null || dValue.signum() <= 0) {
                    dType = null;
                    dValue = null;
                }
                lines.add(new SaleLine(mid, qty, dType, dValue));
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        return lines;
    }

    /** Convenience overload for callers without discount support (e.g. legacy tests). */
    public static List<SaleLine> parseLines(String[] medicineIds, String[] quantities) {
        return parseLines(medicineIds, quantities, null, null);
    }
}
