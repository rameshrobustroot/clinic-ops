package com.clinicops.app.repository;

import com.clinicops.app.domain.MedicalInvestigation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicalInvestigationRepository extends JpaRepository<MedicalInvestigation, Long> {

    @Query("SELECT i FROM MedicalInvestigation i WHERE i.active = true " +
           "AND (i.clinicId IS NULL OR i.clinicId = :clinicId) ORDER BY i.name")
    List<MedicalInvestigation> findActiveAvailableTo(@Param("clinicId") Long clinicId);

    @Query("SELECT i FROM MedicalInvestigation i " +
           "WHERE i.clinicId IS NULL OR i.clinicId = :clinicId ORDER BY i.name")
    List<MedicalInvestigation> findAllAvailableTo(@Param("clinicId") Long clinicId);

    @Query("SELECT i FROM MedicalInvestigation i WHERE i.clinicId IS NULL ORDER BY i.name")
    List<MedicalInvestigation> findAllPlatform();

    @Query("SELECT i FROM MedicalInvestigation i WHERE i.clinicId IS NULL AND LOWER(i.name) = LOWER(:name)")
    Optional<MedicalInvestigation> findPlatformByNameIgnoreCase(@Param("name") String name);

    @Query("SELECT i FROM MedicalInvestigation i WHERE i.clinicId = :clinicId AND LOWER(i.name) = LOWER(:name)")
    Optional<MedicalInvestigation> findClinicByNameIgnoreCase(
            @Param("clinicId") Long clinicId, @Param("name") String name);
}
