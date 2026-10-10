package com.clinicops.app.repository;

import com.clinicops.app.domain.MedicalSpecialization;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicalSpecializationRepository extends JpaRepository<MedicalSpecialization, Long> {

    @Query("SELECT s FROM MedicalSpecialization s WHERE s.active = true " +
           "AND (s.clinicId IS NULL OR s.clinicId = :clinicId) ORDER BY s.name")
    List<MedicalSpecialization> findActiveAvailableTo(@Param("clinicId") Long clinicId);

    @Query("SELECT s FROM MedicalSpecialization s " +
           "WHERE s.clinicId IS NULL OR s.clinicId = :clinicId ORDER BY s.name")
    List<MedicalSpecialization> findAllAvailableTo(@Param("clinicId") Long clinicId);

    @Query("SELECT s FROM MedicalSpecialization s WHERE s.clinicId IS NULL ORDER BY s.name")
    List<MedicalSpecialization> findAllPlatform();

    @Query("SELECT s FROM MedicalSpecialization s WHERE s.clinicId IS NULL AND LOWER(s.name) = LOWER(:name)")
    Optional<MedicalSpecialization> findPlatformByNameIgnoreCase(@Param("name") String name);

    @Query("SELECT s FROM MedicalSpecialization s WHERE s.clinicId = :clinicId AND LOWER(s.name) = LOWER(:name)")
    Optional<MedicalSpecialization> findClinicByNameIgnoreCase(
            @Param("clinicId") Long clinicId, @Param("name") String name);
}
