package com.clinicops.app.service;

import com.clinicops.app.domain.Appointment;
import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.DoctorWorkingHours;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.repository.AppointmentRepository;
import com.clinicops.app.repository.DoctorScheduleConfigRepository;
import com.clinicops.app.repository.DoctorWorkingHoursRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.tenant.TenantGuard;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final DoctorScheduleConfigRepository configRepo;
    private final DoctorWorkingHoursRepository hoursRepo;

    // Valid transitions: from → set of allowed next statuses
    private static final java.util.Map<String, Set<String>> TRANSITIONS = java.util.Map.of(
            "SCHEDULED",   Set.of("ARRIVED", "CANCELLED", "NO_SHOW"),
            "ARRIVED",     Set.of("IN_PROGRESS", "CANCELLED", "NO_SHOW"),
            "IN_PROGRESS", Set.of("DONE", "CANCELLED"),
            "DONE",        Set.of(),
            "CANCELLED",   Set.of(),
            "NO_SHOW",     Set.of()
    );

    @Override
    @Transactional
    public Appointment book(Long clinicId, AppointmentForm form) {
        log.info("Booking appointment: clinic={} date={} doctor={} patient={}",
                clinicId, form.getAppointmentDate(), form.getDoctorId(), form.getPatientId());

        // Resolve patient details — TenantGuard prevents a caller from another clinic
        // supplying a patientId that belongs to a different tenant.
        String patientName = form.getPatientName();
        String patientPhone = form.getPatientPhone();
        if (form.getPatientId() != null) {
            Patient p = TenantGuard.check(
                    patientRepository.findById(form.getPatientId()).orElse(null),
                    Patient::getClinicId);
            if (p != null) {
                patientName = p.getName();
                patientPhone = p.getPhone();
            }
        }

        // Auto-assign next token number for the doctor (or clinic-wide if no doctor)
        int token = nextToken(clinicId, form.getDoctorId(), form.getAppointmentDate());

        // Parse time if provided
        LocalTime apptTime = null;
        if (form.getAppointmentTime() != null && !form.getAppointmentTime().isBlank()) {
            try {
                apptTime = LocalTime.parse(form.getAppointmentTime());
            } catch (Exception e) {
                log.warn("Could not parse appointment time '{}', ignoring", form.getAppointmentTime());
            }
        }

        // Validate the booking against the doctor's configured schedule.
        //
        // For SLOT_BASED doctors the day-of-week must be a scheduled working day —
        // this check runs regardless of whether a specific appointmentTime was submitted
        // so that walk-in / token bookings on non-working days are also rejected.
        // For TOKEN_ONLY doctors any submitted time is discarded (they use queue order).
        if (form.getDoctorId() != null && form.getAppointmentDate() != null) {
            Optional<DoctorScheduleConfig> cfgOpt =
                    configRepo.findByClinicIdAndDoctorId(clinicId, form.getDoctorId());
            if (cfgOpt.isPresent()) {
                DoctorScheduleConfig cfg = cfgOpt.get();
                if (cfg.isTokenOnly()) {
                    // TOKEN_ONLY doctors never have time slots — discard any submitted time.
                    if (apptTime != null) {
                        log.warn("appointmentTime submitted for TOKEN_ONLY doctor {}; clearing",
                                form.getDoctorId());
                        apptTime = null;
                    }
                } else {
                    // SLOT_BASED: the selected day must have configured working sessions.
                    int dow = form.getAppointmentDate().getDayOfWeek().getValue();
                    List<DoctorWorkingHours> sessions = hoursRepo
                            .findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(
                                    clinicId, form.getDoctorId(), dow);
                    if (sessions.isEmpty()) {
                        String dayName = form.getAppointmentDate().getDayOfWeek()
                                .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                        throw new IllegalArgumentException(
                                "This doctor is not available on " + dayName
                                        + ". Please select a different date.");
                    }
                    // If a specific time was submitted, verify it falls within a session.
                    if (apptTime != null) {
                        final LocalTime checkTime = apptTime;
                        boolean withinHours = sessions.stream().anyMatch(
                                s -> !checkTime.isBefore(s.getStartTime())
                                        && checkTime.isBefore(s.getEndTime()));
                        if (!withinHours) {
                            String dayName = form.getAppointmentDate().getDayOfWeek()
                                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                            throw new IllegalArgumentException(
                                    "Selected time is outside this doctor's working hours on "
                                            + dayName + ". Please choose a valid slot.");
                        }
                    }
                }
            }
        }

        Appointment appt = Appointment.builder()
                .clinicId(clinicId)
                .patientId(form.getPatientId())
                .patientName(patientName)
                .patientPhone(patientPhone)
                .doctorId(form.getDoctorId())
                .appointmentDate(form.getAppointmentDate() != null
                        ? form.getAppointmentDate() : LocalDate.now())
                .appointmentTime(apptTime)
                .reason(form.getReason())
                .notes(form.getNotes())
                .status("SCHEDULED")
                .tokenNo(token)
                .build();

        return appointmentRepository.save(appt);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Appointment> listByDate(Long clinicId, LocalDate date) {
        return appointmentRepository.findByClinicAndDate(clinicId, date);
    }

    @Override
    @Transactional
    public Appointment updateStatus(Long id, String newStatus) {
        Appointment appt = getOrThrow(id);
        String current = appt.getStatus();
        Set<String> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new IllegalStateException(
                    "Cannot move appointment from " + current + " to " + newStatus);
        }
        log.info("Appointment {} status {} → {}", id, current, newStatus);
        appt.setStatus(newStatus);
        return appointmentRepository.save(appt);
    }

    @Override
    @Transactional(readOnly = true)
    public Appointment getOrThrow(Long id) {
        Appointment appt = appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found: " + id));
        // findById bypasses the Hibernate tenantFilter on Hibernate 6.6 — explicit guard required.
        return TenantGuard.check(appt, Appointment::getClinicId);
    }

    // ── Private helpers ────────────────────────────────────────────────

    private int nextToken(Long clinicId, Long doctorId, LocalDate date) {
        if (doctorId == null) {
            // No doctor specified: token is clinic-wide for the day
            return appointmentRepository
                    .findByClinicAndDate(clinicId, date).size() + 1;
        }
        return appointmentRepository
                .maxTokenForDoctorOnDate(clinicId, doctorId, date)
                .map(max -> max + 1)
                .orElse(1);
    }
}
