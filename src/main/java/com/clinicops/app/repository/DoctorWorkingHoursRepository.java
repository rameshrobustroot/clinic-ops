package com.clinicops.app.repository;

import com.clinicops.app.domain.DoctorWorkingHours;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {

    List<DoctorWorkingHours> findByClinicIdAndDoctorIdOrderByDayOfWeekAscStartTimeAsc(
            Long clinicId, Long doctorId);

    List<DoctorWorkingHours> findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(
            Long clinicId, Long doctorId, Integer dayOfWeek);

    @Modifying
    @Query("DELETE FROM DoctorWorkingHours h WHERE h.clinicId = :clinicId AND h.doctorId = :doctorId")
    void deleteByClinicIdAndDoctorId(@Param("clinicId") Long clinicId, @Param("doctorId") Long doctorId);
}
