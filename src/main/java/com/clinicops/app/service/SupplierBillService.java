package com.clinicops.app.service;

import com.clinicops.app.domain.BillPayment;
import com.clinicops.app.domain.SupplierBill;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface SupplierBillService {

    SupplierBill create(Long clinicId, SupplierBillForm form, MultipartFile attachment);

    /** Returns the bill, throwing {@link com.clinicops.app.tenant.CrossTenantAccessException} if it belongs to a different clinic. */
    SupplierBill findById(Long clinicId, Long id);

    List<SupplierBill> listAll(Long clinicId);

    /**
     * Returns bills for a clinic filtered by any combination of supplier, bill-date
     * range, and status. All filter parameters are optional; {@code null} means no filter.
     */
    List<SupplierBill> listFiltered(Long clinicId, Long supplierId,
                                    LocalDate from, LocalDate to, String status);

    void addPayment(Long clinicId, Long billId, BillPaymentForm form);

    /**
     * Recomputes paid_amount from all BillPayment rows and updates status.
     * Caps paid_amount at total_amount so the column never exceeds the bill total.
     *
     * @return the overpayment excess (positive when payments exceed total), else ZERO
     */
    BigDecimal recalculateStatus(Long billId);

    /** Resolves the attachment to an absolute {@link Path}, or {@code null} if none. */
    Path getAttachment(Long clinicId, Long billId);

    List<BillPayment> listPayments(Long billId);

    BigDecimal outstandingTotal(Long clinicId);

    long overdueCount(Long clinicId);
}
