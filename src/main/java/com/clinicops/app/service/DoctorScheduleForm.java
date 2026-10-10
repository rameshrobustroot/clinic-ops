package com.clinicops.app.service;

import lombok.Data;

/**
 * Form DTO for saving a doctor's schedule configuration + working hours.
 *
 * Working-hour sessions are submitted as a single JSON string via the
 * {@code sessionsJson} field (kept in sync by Alpine.js x-effect).
 * Format: [{"d":1,"s":"09:00","e":"13:00"}, ...]
 *   d = ISO day-of-week (1=Mon … 7=Sun)
 *   s = start time "HH:mm"
 *   e = end   time "HH:mm"
 */
@Data
public class DoctorScheduleForm {

    private String  bookingMode;           // SLOT_BASED | TOKEN_ONLY
    private Integer slotDurationMinutes;   // 10 / 15 / 20 / 30 / 60
    private Integer maxPerSlot;
    private Integer maxPerDay;             // null = no cap

    /** JSON array written by Alpine.js x-effect before the form is submitted. */
    private String  sessionsJson;
}
