package com.clinicops.app.repository;

import com.clinicops.app.domain.Patient;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    @Query("""
        SELECT p FROM Patient p
        WHERE p.clinicId = :clinicId
          AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR p.phone LIKE CONCAT('%', :q, '%')
               OR LOWER(p.mrn) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY p.updatedAt DESC
    """)
    Page<Patient> search(@Param("clinicId") Long clinicId, @Param("q") String q, Pageable pageable);

    Page<Patient> findByClinicIdOrderByUpdatedAtDesc(Long clinicId, Pageable pageable);

    long countByClinicIdAndCreatedAtGreaterThanEqual(Long clinicId, Instant from);

    /** Recent patients for appointment booking dropdown — most recently active first, up to 200. */
    List<Patient> findTop200ByClinicIdOrderByUpdatedAtDesc(Long clinicId);
}
