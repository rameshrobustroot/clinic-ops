package com.clinicops.app.repository;

import com.clinicops.app.domain.Sale;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    @Query("""
        SELECT COALESCE(SUM(s.totalAmount), 0)
        FROM Sale s WHERE s.clinicId = :clinicId AND s.createdAt >= :from
    """)
    BigDecimal totalSince(@Param("clinicId") Long clinicId, @Param("from") Instant from);

    long countByClinicIdAndCreatedAtGreaterThanEqual(Long clinicId, Instant from);

    List<Sale> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    java.util.Optional<Sale> findFirstByVisitIdOrderByCreatedAtDesc(Long visitId);

    org.springframework.data.domain.Page<Sale> findByClinicIdOrderByCreatedAtDesc(
            Long clinicId, org.springframework.data.domain.Pageable pageable);

    /** Clinic-scoped sales on or after {@code from} — used by {@code salesToday()}
     *  instead of the dangerous {@code findAll()} pattern. */
    List<Sale> findByClinicIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long clinicId, Instant from);
}
