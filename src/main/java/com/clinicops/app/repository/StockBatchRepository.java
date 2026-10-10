package com.clinicops.app.repository;

import com.clinicops.app.domain.StockBatch;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    List<StockBatch> findByMedicineIdOrderByExpiryDateAsc(Long medicineId);

    /** Clinic-explicit variant for writes (FEFO dispensing): never relies on the session tenantFilter. */
    List<StockBatch> findByClinicIdAndMedicineIdOrderByExpiryDateAsc(Long clinicId, Long medicineId);

    // Batches always belong to the clinic that bought them (b.clinicId), even when the medicine is a
    // shared platform-catalog row with clinic_id NULL - so filter on the batch, never on the medicine.
    @Query("""
        SELECT b FROM StockBatch b
        WHERE b.clinicId = :clinicId
          AND b.quantity > 0
          AND b.expiryDate <= :upTo
        ORDER BY b.expiryDate ASC
    """)
    List<StockBatch> findExpiringUpTo(@Param("clinicId") Long clinicId, @Param("upTo") LocalDate upTo);

    @Query("""
        SELECT b FROM StockBatch b
        WHERE b.clinicId = :clinicId
          AND b.quantity > 0
          AND b.expiryDate < :today
        ORDER BY b.expiryDate ASC
    """)
    List<StockBatch> findExpired(@Param("clinicId") Long clinicId, @Param("today") LocalDate today);

    @Query("""
        SELECT COALESCE(SUM(b.quantity), 0) FROM StockBatch b
        WHERE b.medicineId = :medicineId AND b.quantity > 0
    """)
    Integer totalStockForMedicine(@Param("medicineId") Long medicineId);

    @Query("""
        SELECT COALESCE(SUM(b.quantity), 0) FROM StockBatch b
        WHERE b.clinicId = :clinicId AND b.medicineId = :medicineId AND b.quantity > 0
    """)
    Integer totalStockForMedicine(@Param("clinicId") Long clinicId, @Param("medicineId") Long medicineId);

    /** Medicines this clinic has ever received a batch of (incl. sold-out) - what it "carries". */
    @Query("SELECT DISTINCT b.medicineId FROM StockBatch b WHERE b.clinicId = :clinicId")
    java.util.Set<Long> findMedicineIdsEverStocked(@Param("clinicId") Long clinicId);
}
