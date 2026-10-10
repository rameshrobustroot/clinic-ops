package com.clinicops.app.service;

import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.DoctorWorkingHours;
import com.clinicops.app.repository.AppointmentRepository;
import com.clinicops.app.repository.DoctorScheduleConfigRepository;
import com.clinicops.app.repository.DoctorWorkingHoursRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DoctorScheduleServiceImpl implements DoctorScheduleService {

    private final DoctorScheduleConfigRepository configRepo;
    private final DoctorWorkingHoursRepository hoursRepo;
    private final AppointmentRepository appointmentRepo;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<DoctorScheduleConfig> findConfig(Long clinicId, Long doctorId) {
        return configRepo.findByClinicIdAndDoctorId(clinicId, doctorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DoctorWorkingHours> findWorkingHours(Long clinicId, Long doctorId) {
        return hoursRepo.findByClinicIdAndDoctorIdOrderByDayOfWeekAscStartTimeAsc(clinicId, doctorId);
    }

    @Override
    @Transactional
    public void saveSchedule(Long clinicId, Long doctorId, DoctorScheduleForm form) {
        // Upsert config
        DoctorScheduleConfig config = configRepo.findByClinicIdAndDoctorId(clinicId, doctorId)
                .orElse(DoctorScheduleConfig.builder().clinicId(clinicId).doctorId(doctorId).build());

        config.setBookingMode(form.getBookingMode() != null ? form.getBookingMode() : "SLOT_BASED");
        config.setSlotDurationMinutes(
                form.getSlotDurationMinutes() != null ? form.getSlotDurationMinutes() : 15);
        config.setMaxPerSlot(form.getMaxPerSlot() != null ? form.getMaxPerSlot() : 1);
        config.setMaxPerDay(form.getMaxPerDay());
        configRepo.save(config);

        // Replace all working hours with the submitted sessions (may be multiple per day).
        // Sessions arrive as a JSON string written by Alpine.js x-effect.
        // Format: [{"d":1,"s":"09:00","e":"13:00"}, ...]
        hoursRepo.deleteByClinicIdAndDoctorId(clinicId, doctorId);
        String sessionsJson = form.getSessionsJson();
        if (sessionsJson != null && sessionsJson.length() > 2) { // "[]" = 2 chars → skip
            try {
                List<Map<String, Object>> raw =
                        objectMapper.readValue(sessionsJson, new TypeReference<>() {});
                for (Map<String, Object> item : raw) {
                    try {
                        Integer dow      = ((Number) item.get("d")).intValue();
                        LocalTime start  = LocalTime.parse((String) item.get("s"));
                        LocalTime end    = LocalTime.parse((String) item.get("e"));
                        if (!end.isAfter(start)) {
                            String dayName = java.time.DayOfWeek.of(dow)
                                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
                            throw new IllegalArgumentException(
                                    "Invalid session on " + dayName + ": end time " + end
                                    + " must be after start time " + start + ".");
                        }
                        hoursRepo.save(DoctorWorkingHours.builder()
                                .clinicId(clinicId)
                                .doctorId(doctorId)
                                .dayOfWeek(dow)
                                .startTime(start)
                                .endTime(end)
                                .active(true)
                                .build());
                    } catch (IllegalArgumentException iae) {
                        throw iae; // time-range validation — must surface to the user
                    } catch (Exception e) {
                        log.warn("Skipping malformed session entry {}: {}", item, e.getMessage());
                    }
                }
            } catch (IllegalArgumentException iae) {
                throw iae; // re-throw so the controller can show the flash message
            } catch (Exception e) {
                log.warn("Failed to parse sessionsJson: {}", e.getMessage());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SlotInfo> generateSlots(Long clinicId, Long doctorId, LocalDate date) {
        if (date == null || doctorId == null) return List.of();

        Optional<DoctorScheduleConfig> configOpt = findConfig(clinicId, doctorId);
        if (configOpt.isEmpty()) return List.of();  // no config yet → no slots

        DoctorScheduleConfig config = configOpt.get();
        if (config.isTokenOnly()) return List.of();  // token-only mode → no time slots

        int isoDow = date.getDayOfWeek().getValue(); // 1=Mon … 7=Sun
        List<DoctorWorkingHours> sessions =
                hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(clinicId, doctorId, isoDow);
        if (sessions.isEmpty()) return List.of(); // doctor not working that day

        // Count existing (non-cancelled/no-show) bookings per time slot for this doctor+date
        Map<LocalTime, Long> bookedBySlot = appointmentRepo
                .findActiveByDoctorAndDate(clinicId, doctorId, date)
                .stream()
                .filter(a -> a.getAppointmentTime() != null)
                .collect(Collectors.groupingBy(
                        com.clinicops.app.domain.Appointment::getAppointmentTime,
                        Collectors.counting()));

        int duration = config.getSlotDurationMinutes();
        int maxPerSlot = config.getMaxPerSlot();

        List<SlotInfo> slots = new ArrayList<>();
        for (DoctorWorkingHours session : sessions) {
            LocalTime cursor = session.getStartTime();
            while (cursor.isBefore(session.getEndTime())) {
                int booked = bookedBySlot.getOrDefault(cursor, 0L).intValue();
                slots.add(new SlotInfo(cursor, booked, maxPerSlot, booked < maxPerSlot));
                cursor = cursor.plusMinutes(duration);
            }
        }
        return slots;
    }

    @Override
    @Transactional(readOnly = true)
    public int nextToken(Long clinicId, Long doctorId, LocalDate date) {
        return appointmentRepo
                .maxTokenForDoctorOnDate(clinicId, doctorId, date)
                .map(max -> max + 1)
                .orElse(1);
    }
}
