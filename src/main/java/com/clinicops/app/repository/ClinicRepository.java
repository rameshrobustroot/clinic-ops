package com.clinicops.app.repository;

import com.clinicops.app.domain.Clinic;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicRepository extends JpaRepository<Clinic, Long> {

    Optional<Clinic> findByCode(String code);

    boolean existsByCode(String code);
}
