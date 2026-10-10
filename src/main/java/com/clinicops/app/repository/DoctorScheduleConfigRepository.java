package com.clinicops.app.repository;

import com.clinicops.app.domain.DoctorScheduleConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DoctorScheduleConfigRepository extends JpaRepository<DoctorScheduleConfig, Long> {

    Optional<DoctorScheduleConfig> findByClinicIdAndDoctorId(Long clinicId, Long doctorId);
}
