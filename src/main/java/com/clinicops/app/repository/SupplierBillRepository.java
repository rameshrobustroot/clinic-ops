package com.clinicops.app.repository;

import com.clinicops.app.domain.SupplierBill;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierBillRepository extends JpaRepository<SupplierBill, Long> {

    List<SupplierBill> findByClinicIdOrderByDueDateAsc(Long clinicId);

    @Query("SELECT COALESCE(SUM(b.totalAmount - b.paidAmount), 0) FROM SupplierBill b WHERE b.clinicId = :clinicId AND b.status != 'PAID'")
    BigDecimal sumOutstanding(@Param("clinicId") Long clinicId);

    @Query("SELECT COUNT(b) FROM SupplierBill b WHERE b.clinicId = :clinicId AND b.dueDate < CURRENT_DATE AND b.status != 'PAID'")
    long countOverdue(@Param("clinicId") Long clinicId);

    /**
     * Returns unpaid/partial/overdue bills for a supplier, ordered so the most
     * urgent (earliest due date, nulls last) is first. Excludes the source bill
     * so overpayment credit flows only to OTHER bills.
     */
    @Query("""
            SELECT b FROM SupplierBill b
            WHERE b.clinicId = :clinicId
              AND b.supplierId = :supplierId
              AND b.status != 'PAID'
              AND b.id != :excludeId
            ORDER BY CASE WHEN b.dueDate IS NULL THEN 1 ELSE 0 END ASC,
                     b.dueDate ASC,
                     b.id ASC
            """)
    List<SupplierBill> findUnpaidBySupplierExcluding(
            @Param("clinicId") Long clinicId,
            @Param("supplierId") Long supplierId,
            @Param("excludeId") Long excludeId);
}
