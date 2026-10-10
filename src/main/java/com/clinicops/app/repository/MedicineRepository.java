package com.clinicops.app.repository;

import com.clinicops.app.domain.Medicine;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    /**
     * All active medicines visible to a clinic:
     *   - platform medicines (clinic_id IS NULL) — the shared catalog, visible to everyone
     *   - the clinic's own private medicines (clinic_id = clinicId)
     *
     * Platform medicines are listed first (NULLS FIRST) so the catalog appears at the top.
     */
    @Query("""
        SELECT m FROM Medicine m
        WHERE (m.clinicId IS NULL OR m.clinicId = :clinicId)
          AND m.active = true
        ORDER BY m.clinicId NULLS FIRST, m.name ASC
    """)
    List<Medicine> findByClinicIdAndActiveTrueOrderByNameAsc(@Param("clinicId") Long clinicId);

    /** Alias kept for legacy callers. */
    default List<Medicine> findByClinicIdOrderByNameAsc(Long clinicId) {
        return findByClinicIdAndActiveTrueOrderByNameAsc(clinicId);
    }

    /** Full-text search across platform + clinic-private medicines. */
    @Query("""
        SELECT m FROM Medicine m
        WHERE (m.clinicId IS NULL OR m.clinicId = :clinicId)
          AND m.active = true
          AND (LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(m.genericName, '')) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY m.clinicId NULLS FIRST, m.name ASC
    """)
    Page<Medicine> search(@Param("clinicId") Long clinicId, @Param("q") String q, Pageable pageable);

    /**
     * Duplicate-check for clinic-private medicines only.
     * Platform duplicates are enforced at the DB level via the unique index on
     * COALESCE(clinic_id, 0).
     */
    @Query("""
        SELECT m FROM Medicine m
        WHERE m.clinicId = :clinicId
          AND m.active = true
          AND LOWER(m.name) = LOWER(:name)
          AND LOWER(COALESCE(m.strength, '')) = LOWER(COALESCE(:strength, ''))
          AND LOWER(COALESCE(m.manufacturer, '')) = LOWER(COALESCE(:manufacturer, ''))
    """)
    java.util.Optional<Medicine> findDuplicate(
            @Param("clinicId") Long clinicId,
            @Param("name") String name,
            @Param("strength") String strength,
            @Param("manufacturer") String manufacturer);
}
