package com.clinicops.app.service;

import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.DoctorWorkingHours;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DoctorScheduleService {

    /** Returns the schedule config for a doctor; empty if not configured yet. */
    Optional<DoctorScheduleConfig> findConfig(Long clinicId, Long doctorId);

    /** Returns all working-hour entries for a doctor across all days. */
    List<DoctorWorkingHours> findWorkingHours(Long clinicId, Long doctorId);

    /** Save (upsert) schedule config + working hours. Replaces existing hours. */
    void saveSchedule(Long clinicId, Long doctorId, DoctorScheduleForm form);

    /**
     * Generate available time slots for a doctor on a specific date, respecting
     * the configured working hours, slot duration, and current booking counts.
     *
     * <p>Returns an empty list if the doctor has no working hours for that day or
     * is configured as TOKEN_ONLY.
     */
    List<SlotInfo> generateSlots(Long clinicId, Long doctorId, LocalDate date);

    /** Next available token number for a doctor on a date. */
    int nextToken(Long clinicId, Long doctorId, LocalDate date);
}
