package com.clinicops.app.repository;

import com.clinicops.app.domain.MedicalDegree;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicalDegreeRepository extends JpaRepository<MedicalDegree, Long> {

    /** Active merged view: platform rows + this clinic's own active rows. */
    @Query("SELECT d FROM MedicalDegree d WHERE d.active = true " +
           "AND (d.clinicId IS NULL OR d.clinicId = :clinicId) ORDER BY d.name")
    List<MedicalDegree> findActiveAvailableTo(@Param("clinicId") Long clinicId);

    /** All merged view (active + hidden) — clinic-owner CRUD page. */
    @Query("SELECT d FROM MedicalDegree d " +
           "WHERE d.clinicId IS NULL OR d.clinicId = :clinicId ORDER BY d.name")
    List<MedicalDegree> findAllAvailableTo(@Param("clinicId") Long clinicId);

    /** "Is this name visible to me?" — either platform or my clinic. */
    @Query("SELECT d FROM MedicalDegree d WHERE LOWER(d.name) = LOWER(:name) " +
           "AND (d.clinicId IS NULL OR d.clinicId = :clinicId)")
    Optional<MedicalDegree> findVisibleNameTo(@Param("name") String name, @Param("clinicId") Long clinicId);

    /** Platform rows only — PLATFORM_ADMIN's editor. */
    @Query("SELECT d FROM MedicalDegree d WHERE d.clinicId IS NULL ORDER BY d.name")
    List<MedicalDegree> findAllPlatform();

    @Query("SELECT d FROM MedicalDegree d WHERE d.clinicId IS NULL AND LOWER(d.name) = LOWER(:name)")
    Optional<MedicalDegree> findPlatformByNameIgnoreCase(@Param("name") String name);

    @Query("SELECT d FROM MedicalDegree d WHERE d.clinicId = :clinicId AND LOWER(d.name) = LOWER(:name)")
    Optional<MedicalDegree> findClinicByNameIgnoreCase(
            @Param("clinicId") Long clinicId, @Param("name") String name);
}
