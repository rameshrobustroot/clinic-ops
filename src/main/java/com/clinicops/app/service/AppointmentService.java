package com.clinicops.app.service;

import com.clinicops.app.domain.Appointment;
import java.time.LocalDate;
import java.util.List;

public interface AppointmentService {

    /** Book a new appointment and auto-assign a token number. */
    Appointment book(Long clinicId, AppointmentForm form);

    /** All appointments for a clinic on a given day, ordered by slot/token. */
    List<Appointment> listByDate(Long clinicId, LocalDate date);

    /**
     * Transition an appointment to a new status.
     * Validates allowed transitions; throws {@link IllegalStateException} on invalid moves.
     */
    Appointment updateStatus(Long id, String newStatus);

    /** Fetch a single appointment or throw. */
    Appointment getOrThrow(Long id);
}
