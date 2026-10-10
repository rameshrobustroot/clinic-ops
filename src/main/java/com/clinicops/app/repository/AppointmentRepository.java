package com.clinicops.app.repository;

import com.clinicops.app.domain.Appointment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /** All appointments for a clinic on a given date, ordered by token / time. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.clinicId = :clinicId AND a.appointmentDate = :date
            ORDER BY
                CASE WHEN a.appointmentTime IS NULL THEN 1 ELSE 0 END,
                a.appointmentTime ASC,
                a.tokenNo ASC,
                a.id ASC
            """)
    List<Appointment> findByClinicAndDate(
            @Param("clinicId") Long clinicId,
            @Param("date") LocalDate date);

    /** Appointments for a specific doctor on a date — used for token assignment. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.clinicId = :clinicId AND a.doctorId = :doctorId
              AND a.appointmentDate = :date
              AND a.status NOT IN ('CANCELLED', 'NO_SHOW')
            ORDER BY a.tokenNo ASC
            """)
    List<Appointment> findActiveByDoctorAndDate(
            @Param("clinicId") Long clinicId,
            @Param("doctorId") Long doctorId,
            @Param("date") LocalDate date);

    /** Highest token number assigned to a doctor on a given day — for auto-increment. */
    @Query("""
            SELECT MAX(a.tokenNo) FROM Appointment a
            WHERE a.clinicId = :clinicId AND a.doctorId = :doctorId
              AND a.appointmentDate = :date
            """)
    Optional<Integer> maxTokenForDoctorOnDate(
            @Param("clinicId") Long clinicId,
            @Param("doctorId") Long doctorId,
            @Param("date") LocalDate date);

    /** Count of non-terminal appointments for today — used on dashboard. */
    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.clinicId = :clinicId AND a.appointmentDate = :date
              AND a.status NOT IN ('CANCELLED', 'NO_SHOW', 'DONE')
            """)
    long countActiveToday(
            @Param("clinicId") Long clinicId,
            @Param("date") LocalDate date);
}
