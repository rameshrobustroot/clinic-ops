package com.clinicops.app.repository;

import com.clinicops.app.domain.Visit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitRepository extends JpaRepository<Visit, Long> {
    List<Visit> findByPatientIdOrderByVisitDateDesc(Long patientId);

    List<Visit> findByClinicIdAndFollowUpDate(Long clinicId, LocalDate date);

    List<Visit> findByClinicIdAndFollowUpDateBetweenOrderByFollowUpDateAsc(
            Long clinicId, LocalDate from, LocalDate to);

    long countByClinicIdAndVisitDateGreaterThanEqual(Long clinicId, Instant from);
}
