package com.clinicops.app.repository;

import com.clinicops.app.domain.Doctor;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findByClinicIdAndActiveTrueOrderByFullNameAsc(Long clinicId);

    List<Doctor> findByClinicIdOrderByFullNameAsc(Long clinicId);
}
