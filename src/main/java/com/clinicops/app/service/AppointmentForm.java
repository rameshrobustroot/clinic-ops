package com.clinicops.app.service;

import java.time.LocalDate;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** Form DTO for booking or editing an appointment. */
@Data
public class AppointmentForm {

    /** Existing registered patient — null for walk-in. */
    private Long patientId;

    /** Walk-in / new patient name (used when patientId is null). */
    private String patientName;

    private String patientPhone;

    private Long doctorId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate appointmentDate;

    /** "HH:mm" string — optional; null means walk-in token only. */
    private String appointmentTime;

    private String reason;

    private String notes;
}
