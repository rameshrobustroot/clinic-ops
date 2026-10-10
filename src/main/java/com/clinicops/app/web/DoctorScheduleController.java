package com.clinicops.app.web;

import com.clinicops.app.domain.Doctor;
import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.DoctorWorkingHours;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.DoctorScheduleForm;
import com.clinicops.app.service.DoctorScheduleService;
import com.clinicops.app.service.NotFoundException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/doctors/{doctorId}/schedule")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('OWNER')")
public class DoctorScheduleController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final DoctorScheduleService scheduleService;
    private final DoctorRepository doctorRepository;
    private final CurrentUser currentUser;

    @GetMapping
    public String showConfig(@PathVariable Long doctorId, Model model) {
        Long clinicId = currentUser.clinicId();
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new NotFoundException("Doctor not found"));

        Optional<DoctorScheduleConfig> config = scheduleService.findConfig(clinicId, doctorId);
        List<DoctorWorkingHours> existingHours = scheduleService.findWorkingHours(clinicId, doctorId);

        DoctorScheduleForm form = buildForm(config);
        String sessionsJson = toJson(existingHours);

        model.addAttribute("doctor", doctor);
        model.addAttribute("form", form);
        model.addAttribute("sessionsJson", sessionsJson);
        model.addAttribute("dayNames", dayNames());
        return "appointment/schedule-config";
    }

    @PostMapping
    public String saveConfig(@PathVariable Long doctorId,
                             @ModelAttribute DoctorScheduleForm form,
                             RedirectAttributes ra) {
        Long clinicId = currentUser.clinicId();
        try {
            scheduleService.saveSchedule(clinicId, doctorId, form);
            ra.addFlashAttribute("flash", "Schedule saved.");
        } catch (Exception e) {
            log.warn("Schedule save failed for doctor {}", doctorId, e);
            ra.addFlashAttribute("flash", "Save failed: " + e.getMessage());
        }
        return "redirect:/doctors/" + doctorId + "/schedule";
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** ISO weekday names: {1 → "Monday", …, 7 → "Sunday"} */
    static Map<Integer, String> dayNames() {
        return Map.of(
                1, "Monday", 2, "Tuesday", 3, "Wednesday",
                4, "Thursday", 5, "Friday",  6, "Saturday", 7, "Sunday");
    }

    private DoctorScheduleForm buildForm(Optional<DoctorScheduleConfig> config) {
        DoctorScheduleForm form = new DoctorScheduleForm();
        config.ifPresentOrElse(c -> {
            form.setBookingMode(c.getBookingMode());
            form.setSlotDurationMinutes(c.getSlotDurationMinutes());
            form.setMaxPerSlot(c.getMaxPerSlot());
            form.setMaxPerDay(c.getMaxPerDay());
        }, () -> {
            form.setBookingMode("SLOT_BASED");
            form.setSlotDurationMinutes(15);
            form.setMaxPerSlot(1);
        });
        return form;
    }

    /**
     * Serialise working hours to a compact JSON array for Alpine.js init.
     * Format: [{"d":1,"s":"09:00","e":"13:00"}, ...]
     * Times are always "HH:mm" — no special characters, no escaping needed.
     */
    private String toJson(List<DoctorWorkingHours> hours) {
        return hours.stream()
                .filter(h -> Boolean.TRUE.equals(h.getActive()))
                .map(h -> String.format("{\"d\":%d,\"s\":\"%s\",\"e\":\"%s\"}",
                        h.getDayOfWeek(),
                        h.getStartTime().format(TIME_FMT),
                        h.getEndTime().format(TIME_FMT)))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
