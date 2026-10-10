package com.clinicops.app.service;

import com.clinicops.app.domain.BillPayment;
import com.clinicops.app.domain.Supplier;
import com.clinicops.app.domain.SupplierBill;
import com.clinicops.app.repository.BillPaymentRepository;
import com.clinicops.app.repository.SupplierBillRepository;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.tenant.TenantGuard;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierBillServiceImpl implements SupplierBillService {

    private final SupplierBillRepository billRepository;
    private final BillPaymentRepository paymentRepository;
    private final SupplierRepository supplierRepository;
    private final FileStorageService fileStorageService;

    // -------------------------------------------------------------------------
    // Bill creation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public SupplierBill create(Long clinicId, SupplierBillForm form, MultipartFile attachment) {
        log.info("Creating supplier bill for clinic {} billNumber={}", clinicId, form.getBillNumber());

        String attachmentPath = null;
        if (attachment != null && !attachment.isEmpty()) {
            try {
                attachmentPath = fileStorageService.store(attachment);
            } catch (IOException e) {
                throw new RuntimeException("Failed to store bill attachment", e);
            }
        }

        SupplierBill bill = SupplierBill.builder()
                .clinicId(clinicId)
                .supplierId(form.getSupplierId())
                .billNumber(form.getBillNumber())
                .billDate(form.getBillDate() != null ? form.getBillDate() : LocalDate.now())
                .dueDate(form.getDueDate())
                .totalAmount(form.getTotalAmount())
                .paidAmount(BigDecimal.ZERO)
                .status("UNPAID")
                .notes(form.getNotes())
                .attachmentPath(attachmentPath)
                .createdAt(OffsetDateTime.now())
                .build();

        SupplierBill saved = billRepository.save(bill);
        log.info("Supplier bill created id={}", saved.getId());

        // Auto-apply any existing supplier credit to this new bill
        if (saved.getSupplierId() != null) {
            autoApplySupplierCredit(clinicId, saved);
        }

        return saved;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public SupplierBill findById(Long clinicId, Long id) {
        log.info("Loading supplier bill id={} for clinic {}", id, clinicId);
        SupplierBill bill = billRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier bill not found: " + id));
        TenantGuard.check(bill, SupplierBill::getClinicId);
        return bill;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierBill> listAll(Long clinicId) {
        return billRepository.findByClinicIdOrderByDueDateAsc(clinicId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierBill> listFiltered(Long clinicId, Long supplierId,
                                            LocalDate from, LocalDate to, String status) {
        return billRepository.findByClinicIdOrderByDueDateAsc(clinicId).stream()
                .filter(b -> supplierId == null || supplierId.equals(b.getSupplierId()))
                .filter(b -> from == null || (b.getBillDate() != null && !b.getBillDate().isBefore(from)))
                .filter(b -> to == null || (b.getBillDate() != null && !b.getBillDate().isAfter(to)))
                .filter(b -> status == null || status.isBlank() || status.equalsIgnoreCase(b.getStatus()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Payment recording
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void addPayment(Long clinicId, Long billId, BillPaymentForm form) {
        log.info("Adding payment to bill {} for clinic {} amount={}", billId, clinicId, form.getAmount());

        SupplierBill bill = findById(clinicId, billId);

        BillPayment payment = BillPayment.builder()
                .clinicId(clinicId)
                .billId(bill.getId())
                .paidDate(form.getPaidDate() != null ? form.getPaidDate() : LocalDate.now())
                .amount(form.getAmount())
                .paymentMode(form.getPaymentMode())
                .referenceNo(form.getReferenceNo())
                .notes(form.getNotes())
                .createdAt(OffsetDateTime.now())
                .build();
        paymentRepository.save(payment);

        BigDecimal excess = recalculateStatus(billId);

        if (excess.compareTo(BigDecimal.ZERO) > 0) {
            log.info("Overpayment detected on bill {} — excess=₹{}", billId, excess);
            if (bill.getSupplierId() != null) {
                BigDecimal remaining = distributeToOtherBills(clinicId, bill.getSupplierId(), excess, billId);
                if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                    addToSupplierCredit(clinicId, bill.getSupplierId(), remaining);
                }
            } else {
                log.warn("Bill {} has no supplierId; ₹{} overpayment cannot be attributed", billId, excess);
            }
        }

        log.info("Payment recorded for bill {}", billId);
    }

    // -------------------------------------------------------------------------
    // Status recalculation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public BigDecimal recalculateStatus(Long billId) {
        SupplierBill bill = TenantGuard.check(
                billRepository.findById(billId)
                        .orElseThrow(() -> new NotFoundException("Supplier bill not found: " + billId)),
                SupplierBill::getClinicId);

        BigDecimal totalPaid = paymentRepository.sumByBillId(billId);
        BigDecimal excess = BigDecimal.ZERO;

        if (totalPaid.compareTo(bill.getTotalAmount()) >= 0) {
            // Overpaid or exactly paid — cap paid_amount at bill total
            bill.setPaidAmount(bill.getTotalAmount());
            bill.setStatus("PAID");
            excess = totalPaid.subtract(bill.getTotalAmount());
        } else if (totalPaid.compareTo(BigDecimal.ZERO) > 0) {
            bill.setPaidAmount(totalPaid);
            bill.setStatus("PARTIAL");
        } else if (bill.getDueDate() != null && LocalDate.now().isAfter(bill.getDueDate())) {
            bill.setPaidAmount(BigDecimal.ZERO);
            bill.setStatus("OVERDUE");
        } else {
            bill.setPaidAmount(BigDecimal.ZERO);
            bill.setStatus("UNPAID");
        }

        billRepository.save(bill);
        log.info("Recalculated bill {} status={} paidAmount={} excess={}", billId, bill.getStatus(), bill.getPaidAmount(), excess);
        return excess;
    }

    // -------------------------------------------------------------------------
    // Attachment
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Path getAttachment(Long clinicId, Long billId) {
        SupplierBill bill = findById(clinicId, billId);
        if (bill.getAttachmentPath() == null || bill.getAttachmentPath().isBlank()) {
            return null;
        }
        return fileStorageService.resolve(bill.getAttachmentPath());
    }

    // -------------------------------------------------------------------------
    // Payments list
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<BillPayment> listPayments(Long billId) {
        return paymentRepository.findByBillIdOrderByPaidDateDesc(billId);
    }

    // -------------------------------------------------------------------------
    // Dashboard aggregates
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public BigDecimal outstandingTotal(Long clinicId) {
        return billRepository.sumOutstanding(clinicId);
    }

    @Override
    @Transactional(readOnly = true)
    public long overdueCount(Long clinicId) {
        return billRepository.countOverdue(clinicId);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * When a new bill is created for a supplier that has accumulated credit,
     * immediately apply the credit as a system payment against the new bill.
     */
    private void autoApplySupplierCredit(Long clinicId, SupplierBill newBill) {
        supplierRepository.findById(newBill.getSupplierId())
                .map(s -> TenantGuard.check(s, Supplier::getClinicId))
                .ifPresent(supplier -> {
            BigDecimal credit = supplier.getCreditBalance();
            if (credit == null || credit.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            BigDecimal toApply = credit.min(newBill.getTotalAmount());
            log.info("Auto-applying ₹{} supplier credit (supplier={}) to new bill {}", toApply, supplier.getId(), newBill.getId());

            BillPayment autoPayment = BillPayment.builder()
                    .clinicId(clinicId)
                    .billId(newBill.getId())
                    .paidDate(LocalDate.now())
                    .amount(toApply)
                    .paymentMode("CREDIT")
                    .notes("Auto-applied from supplier credit balance")
                    .createdAt(OffsetDateTime.now())
                    .build();
            paymentRepository.save(autoPayment);

            supplier.setCreditBalance(credit.subtract(toApply));
            supplierRepository.save(supplier);

            recalculateStatus(newBill.getId());
        });
    }

    /**
     * Distributes {@code excess} across existing unpaid bills from the same supplier
     * (earliest due date first), creating one auto-applied BillPayment per bill.
     *
     * @return any remaining excess that could not be distributed (no more unpaid bills)
     */
    private BigDecimal distributeToOtherBills(Long clinicId, Long supplierId,
                                               BigDecimal excess, Long excludeBillId) {
        List<SupplierBill> unpaidBills =
                billRepository.findUnpaidBySupplierExcluding(clinicId, supplierId, excludeBillId);

        for (SupplierBill next : unpaidBills) {
            if (excess.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal available = next.getBalance();
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal toApply = excess.min(available);
            log.info("Auto-applying ₹{} credit to bill {} (supplier={})", toApply, next.getId(), supplierId);

            BillPayment autoPayment = BillPayment.builder()
                    .clinicId(clinicId)
                    .billId(next.getId())
                    .paidDate(LocalDate.now())
                    .amount(toApply)
                    .paymentMode("CREDIT")
                    .notes("Auto-applied from overpayment on bill #" + excludeBillId)
                    .createdAt(OffsetDateTime.now())
                    .build();
            paymentRepository.save(autoPayment);

            // Only recalculate status; do not cascade excess further
            recalculateStatus(next.getId());

            excess = excess.subtract(toApply);
        }

        return excess;
    }

    /**
     * Adds leftover credit to the supplier's credit balance so it is
     * automatically applied when the next bill arrives.
     */
    private void addToSupplierCredit(Long clinicId, Long supplierId, BigDecimal amount) {
        supplierRepository.findById(supplierId)
                .map(s -> TenantGuard.check(s, Supplier::getClinicId))
                .ifPresent(supplier -> {
            BigDecimal current = supplier.getCreditBalance() != null
                    ? supplier.getCreditBalance() : BigDecimal.ZERO;
            supplier.setCreditBalance(current.add(amount));
            supplierRepository.save(supplier);
            log.info("Stored ₹{} as supplier credit (supplier={} total={})", amount, supplierId, supplier.getCreditBalance());
        });
    }
}
