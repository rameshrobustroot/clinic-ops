package com.clinicops.app.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents one bookable time slot for a doctor on a specific day.
 *
 * @param time      The slot start time.
 * @param booked    Number of appointments already booked in this slot.
 * @param max       Maximum allowed bookings in this slot.
 * @param available {@code true} if at least one booking is still possible.
 */
public record SlotInfo(LocalTime time, int booked, int max, boolean available) {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("hh:mm a");

    /** Human-readable label, e.g. "09:30 AM (2/3)". */
    public String label() {
        String base = time.format(FMT);
        if (max > 1) return base + " (" + booked + "/" + max + ")";
        return base;
    }

    /** Value string for the HTML select option (HH:mm). */
    public String value() {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}
