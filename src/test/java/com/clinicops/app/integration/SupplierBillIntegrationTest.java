package com.clinicops.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.Supplier;
import com.clinicops.app.domain.SupplierBill;
import com.clinicops.app.repository.BillPaymentRepository;
import com.clinicops.app.repository.SupplierBillRepository;
import com.clinicops.app.service.BillPaymentForm;
import com.clinicops.app.service.SupplierBillForm;
import com.clinicops.app.service.SupplierBillService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for the supplier-bill lifecycle: create → partial pay → fully pay.
 *
 * <p>Also verifies overpayment handling, credit auto-application on new bills,
 * and the list / filter queries.
 */
class SupplierBillIntegrationTest extends BaseIntegrationTest {

    @Autowired SupplierBillService billService;
    @Autowired SupplierBillRepository billRepository;
    @Autowired BillPaymentRepository paymentRepository;

    // ── Bill creation ────────────────────────────────────────────────────

    @Test
    void create_bill_persists_with_unpaid_status() {
        Supplier supplier = createSupplier("Pharma A");
        SupplierBillForm form = billForm(supplier.getId(), "INV-001", new BigDecimal("10000"));

        SupplierBill bill = billService.create(clinicId, form, null);

        assertThat(bill.getId()).isNotNull();
        assertThat(bill.getStatus()).isEqualTo("UNPAID");
        assertThat(bill.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(bill.getBalance()).isEqualByComparingTo("10000");
    }

    @Test
    void create_bill_without_supplier_still_persists() {
        SupplierBillForm form = billForm(null, "MISC-001", new BigDecimal("500"));
        SupplierBill bill = billService.create(clinicId, form, null);

        assertThat(bill.getId()).isNotNull();
        assertThat(bill.getSupplierId()).isNull();
        assertThat(bill.getStatus()).isEqualTo("UNPAID");
    }

    @Test
    void create_bill_defaults_bill_date_to_today_when_not_provided() {
        SupplierBillForm form = new SupplierBillForm();
        form.setTotalAmount(new BigDecimal("1000"));
        // billDate intentionally left null

        SupplierBill bill = billService.create(clinicId, form, null);
        assertThat(bill.getBillDate()).isEqualTo(LocalDate.now());
    }

    // ── Payment recording ────────────────────────────────────────────────

    @Test
    void partial_payment_sets_status_to_partial() {
        Supplier supplier = createSupplier("Pharma B");
        SupplierBill bill = billService.create(clinicId,
                billForm(supplier.getId(), "INV-002", new BigDecimal("5000")), null);

        billService.addPayment(clinicId, bill.getId(), paymentForm(new BigDecimal("2000")));

        SupplierBill updated = billService.findById(clinicId, bill.getId());
        assertThat(updated.getStatus()).isEqualTo("PARTIAL");
        assertThat(updated.getPaidAmount()).isEqualByComparingTo("2000.00");
        assertThat(updated.getBalance()).isEqualByComparingTo("3000.00");
    }

    @Test
    void full_payment_sets_status_to_paid() {
        Supplier supplier = createSupplier("Pharma C");
        SupplierBill bill = billService.create(clinicId,
                billForm(supplier.getId(), "INV-003", new BigDecimal("3000")), null);

        billService.addPayment(clinicId, bill.getId(), paymentForm(new BigDecimal("3000")));

        SupplierBill updated = billService.findById(clinicId, bill.getId());
        assertThat(updated.getStatus()).isEqualTo("PAID");
        assertThat(updated.getPaidAmount()).isEqualByComparingTo("3000.00");
        assertThat(updated.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void two_partial_payments_accumulate_correctly() {
        Supplier supplier = createSupplier("Pharma D");
        SupplierBill bill = billService.create(clinicId,
                billForm(supplier.getId(), "INV-004", new BigDecimal("6000")), null);

        billService.addPayment(clinicId, bill.getId(), paymentForm(new BigDecimal("2000")));
        billService.addPayment(clinicId, bill.getId(), paymentForm(new BigDecimal("2500")));

        SupplierBill updated = billService.findById(clinicId, bill.getId());
        assertThat(updated.getStatus()).isEqualTo("PARTIAL");
        assertThat(updated.getPaidAmount()).isEqualByComparingTo("4500.00");
        assertThat(updated.getBalance()).isEqualByComparingTo("1500.00");
    }

    @Test
    void overpayment_caps_paid_amount_at_bill_total() {
        Supplier supplier = createSupplier("Pharma E");
        SupplierBill bill = billService.create(clinicId,
                billForm(supplier.getId(), "INV-005", new BigDecimal("1000")), null);

        // Pay ₹1500 against a ₹1000 bill — excess ₹500 should go to supplier credit
        billService.addPayment(clinicId, bill.getId(), paymentForm(new BigDecimal("1500")));

        SupplierBill updated = billService.findById(clinicId, bill.getId());
        assertThat(updated.getStatus()).isEqualTo("PAID");
        assertThat(updated.getPaidAmount()).isEqualByComparingTo("1000.00");
        // ₹500 excess should be stored as supplier credit
        Supplier refreshed = supplierRepository.findById(supplier.getId()).orElseThrow();
        assertThat(refreshed.getCreditBalance()).isEqualByComparingTo("500.00");
    }

    // ── Credit auto-application ──────────────────────────────────────────

    @Test
    void new_bill_auto_applies_existing_supplier_credit() {
        Supplier supplier = createSupplier("Pharma F");

        // Give the supplier ₹2000 credit directly
        supplier.setCreditBalance(new BigDecimal("2000"));
        supplierRepository.save(supplier);

        // Create a ₹3000 bill — ₹2000 credit should be auto-applied → status PARTIAL
        SupplierBill bill = billService.create(clinicId,
                billForm(supplier.getId(), "INV-006", new BigDecimal("3000")), null);

        SupplierBill loaded = billService.findById(clinicId, bill.getId());
        assertThat(loaded.getStatus()).isEqualTo("PARTIAL");
        assertThat(loaded.getPaidAmount()).isEqualByComparingTo("2000.00");
        assertThat(loaded.getBalance()).isEqualByComparingTo("1000.00");

        // Supplier credit should be exhausted
        Supplier refreshed = supplierRepository.findById(supplier.getId()).orElseThrow();
        assertThat(refreshed.getCreditBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void new_bill_fully_paid_when_credit_covers_entire_amount() {
        Supplier supplier = createSupplier("Pharma G");
        supplier.setCreditBalance(new BigDecimal("5000"));
        supplierRepository.save(supplier);

        SupplierBill bill = billService.create(clinicId,
                billForm(supplier.getId(), "INV-007", new BigDecimal("2000")), null);

        SupplierBill loaded = billService.findById(clinicId, bill.getId());
        assertThat(loaded.getStatus()).isEqualTo("PAID");
        // Remaining credit = 5000 - 2000 = 3000
        Supplier refreshed = supplierRepository.findById(supplier.getId()).orElseThrow();
        assertThat(refreshed.getCreditBalance()).isEqualByComparingTo("3000.00");
    }

    // ── Filtered listing ─────────────────────────────────────────────────

    @Test
    void list_all_returns_bills_for_this_clinic_only() {
        Supplier supplier = createSupplier("Pharma H");
        billService.create(clinicId, billForm(supplier.getId(), "INV-A", new BigDecimal("100")), null);
        billService.create(clinicId, billForm(supplier.getId(), "INV-B", new BigDecimal("200")), null);

        List<SupplierBill> bills = billService.listAll(clinicId);
        assertThat(bills).hasSize(2);
    }

    @Test
    void filtered_list_returns_only_matching_supplier() {
        Supplier s1 = createSupplier("Supplier Alpha");
        Supplier s2 = createSupplier("Supplier Beta");
        billService.create(clinicId, billForm(s1.getId(), "ALPHA-1", new BigDecimal("100")), null);
        billService.create(clinicId, billForm(s2.getId(), "BETA-1",  new BigDecimal("200")), null);

        List<SupplierBill> filtered = billService.listFiltered(clinicId, s1.getId(), null, null, null);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).getBillNumber()).isEqualTo("ALPHA-1");
    }

    @Test
    void filtered_list_by_status_returns_matching_bills_only() {
        Supplier supplier = createSupplier("Pharma I");
        SupplierBill unpaid = billService.create(clinicId,
                billForm(supplier.getId(), "UNPAID-1", new BigDecimal("1000")), null);
        SupplierBill paidBill = billService.create(clinicId,
                billForm(supplier.getId(), "PAID-1",   new BigDecimal("500")),  null);
        billService.addPayment(clinicId, paidBill.getId(), paymentForm(new BigDecimal("500")));

        List<SupplierBill> unpaidBills = billService.listFiltered(clinicId, null, null, null, "UNPAID");
        assertThat(unpaidBills).extracting(SupplierBill::getBillNumber).contains("UNPAID-1");
        assertThat(unpaidBills).extracting(SupplierBill::getBillNumber).doesNotContain("PAID-1");
    }

    // ── Overdue logic ────────────────────────────────────────────────────

    @Test
    void bill_past_due_date_with_no_payment_is_overdue_after_recalculate() {
        Supplier supplier = createSupplier("Pharma J");
        SupplierBillForm form = billForm(supplier.getId(), "OVERDUE-1", new BigDecimal("1000"));
        form.setDueDate(LocalDate.now().minusDays(1)); // due yesterday

        SupplierBill bill = billService.create(clinicId, form, null);

        // Force recalculate (normally triggered by payment)
        billService.recalculateStatus(bill.getId());

        SupplierBill loaded = billService.findById(clinicId, bill.getId());
        assertThat(loaded.getStatus()).isEqualTo("OVERDUE");
        assertThat(loaded.isOverdue()).isTrue();
    }

    // ── Payment listing ──────────────────────────────────────────────────

    @Test
    void list_payments_returns_all_payments_for_bill() {
        Supplier supplier = createSupplier("Pharma K");
        SupplierBill bill = billService.create(clinicId,
                billForm(supplier.getId(), "MULTI-PAY", new BigDecimal("5000")), null);

        billService.addPayment(clinicId, bill.getId(), paymentForm(new BigDecimal("1000")));
        billService.addPayment(clinicId, bill.getId(), paymentForm(new BigDecimal("1500")));

        List<?> payments = billService.listPayments(bill.getId());
        assertThat(payments).hasSize(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private SupplierBillForm billForm(Long supplierId, String billNumber, BigDecimal totalAmount) {
        SupplierBillForm form = new SupplierBillForm();
        form.setSupplierId(supplierId);
        form.setBillNumber(billNumber);
        form.setBillDate(LocalDate.now());
        form.setTotalAmount(totalAmount);
        return form;
    }

    private BillPaymentForm paymentForm(BigDecimal amount) {
        BillPaymentForm form = new BillPaymentForm();
        form.setPaidDate(LocalDate.now());
        form.setAmount(amount);
        form.setPaymentMode("CASH");
        return form;
    }
}
