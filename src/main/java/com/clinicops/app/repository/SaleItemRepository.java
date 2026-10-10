package com.clinicops.app.repository;

import com.clinicops.app.domain.SaleItem;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleItemRepository extends JpaRepository<SaleItem, Long> {

    List<SaleItem> findBySaleId(Long saleId);

    @Query("""
        SELECT si.medicineId, SUM(si.quantity)
        FROM SaleItem si
        JOIN Sale s ON s.id = si.saleId
        WHERE s.clinicId = :clinicId AND s.createdAt >= :from
        GROUP BY si.medicineId
        ORDER BY SUM(si.quantity) DESC
    """)
    List<Object[]> topMedicinesSince(@Param("clinicId") Long clinicId, @Param("from") Instant from);
}
