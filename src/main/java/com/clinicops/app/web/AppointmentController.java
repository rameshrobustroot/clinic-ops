package com.clinicops.app.web;

import com.clinicops.app.domain.Appointment;
import com.clinicops.app.domain.Doctor;
import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.AppointmentForm;
import com.clinicops.app.service.AppointmentService;
import com.clinicops.app.service.DoctorScheduleService;
import com.clinicops.app.service.SlotInfo;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/appointments")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('OWNER','DOCTOR','RECEPTIONIST','PHARMACIST')")
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final DoctorScheduleService scheduleService;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final CurrentUser currentUser;

    // ── Day view ────────────────────────────────────────────────────────

    /**
     * Main schedule view — shows all appointments for a given day.
     * Defaults to today. Supports ?date= and ?doctorId= filters.
     */
    @GetMapping
    public String dayView(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "doctorId", required = false) Long doctorId,
            Model model) {

        Long clinicId = currentUser.clinicId();
        LocalDate viewDate = date != null ? date : LocalDate.now();

        List<Appointment> all = appointmentService.listByDate(clinicId, viewDate);

        // Apply doctor filter
        List<Appointment> appointments = doctorId != null
                ? all.stream().filter(a -> doctorId.equals(a.getDoctorId())).toList()
                : all;

        // Resolve patient + doctor names for display
        Map<Long, Patient> patientById = resolvePatients(appointments);
        Map<Long, Doctor> doctorById = resolveDoctors(all); // all, for tab counts

        // Doctor tab data: doctor → count of appointments (non-terminal)
        List<Doctor> activeDoctors = doctorRepository.findByClinicIdAndActiveTrueOrderByFullNameAsc(clinicId);
        Map<Long, Long> countByDoctor = all.stream()
                .filter(a -> a.getDoctorId() != null && !a.isTerminal())
                .collect(Collectors.groupingBy(Appointment::getDoctorId, Collectors.counting()));

        // Summary counts
        long totalScheduled = all.stream().filter(a -> "SCHEDULED".equals(a.getStatus())).count();
        long totalArrived   = all.stream().filter(a -> "ARRIVED".equals(a.getStatus())).count();
        long totalDone      = all.stream().filter(a -> "DONE".equals(a.getStatus())).count();

        model.addAttribute("viewDate", viewDate);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("prevDate", viewDate.minusDays(1));
        model.addAttribute("nextDate", viewDate.plusDays(1));
        model.addAttribute("appointments", appointments);
        model.addAttribute("allAppointments", all);
        model.addAttribute("patientById", patientById);
        model.addAttribute("doctorById", doctorById);
        model.addAttribute("activeDoctors", activeDoctors);
        model.addAttribute("countByDoctor", countByDoctor);
        model.addAttribute("selectedDoctorId", doctorId);
        model.addAttribute("totalScheduled", totalScheduled);
        model.addAttribute("totalArrived", totalArrived);
        model.addAttribute("totalDone", totalDone);
        return "appointment/day";
    }

    // ── Booking form ─────────────────────────────────────────────────────

    // ── Calendar view ────────────────────────────────────────────────────

    /**
     * Month + week calendar view.
     * ?view=month (default) or ?view=week
     * ?month=YYYY-MM for month navigation; ?week=YYYY-MM-DD for week navigation.
     */
    @GetMapping("/calendar")
    public String calendar(
            @RequestParam(value = "view", defaultValue = "month") String view,
            @RequestParam(value = "month", required = false) String monthParam,
            @RequestParam(value = "week", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekParam,
            Model model) {

        Long clinicId = currentUser.clinicId();
        LocalDate today = LocalDate.now();

        if ("week".equals(view)) {
            // Week view — default to current week (Mon–Sun)
            LocalDate weekStart = weekParam != null
                    ? weekParam.with(java.time.DayOfWeek.MONDAY)
                    : today.with(java.time.DayOfWeek.MONDAY);
            LocalDate weekEnd = weekStart.plusDays(6);

            // Gather appointments for the whole week
            Map<LocalDate, List<Appointment>> byDay = new LinkedHashMap<>();
            for (int i = 0; i < 7; i++) {
                LocalDate d = weekStart.plusDays(i);
                byDay.put(d, appointmentService.listByDate(clinicId, d));
            }
            Map<Long, Patient> patientById = new LinkedHashMap<>();
            Map<Long, Doctor> doctorById = new LinkedHashMap<>();
            byDay.values().forEach(appts -> {
                resolvePatients(appts).forEach(patientById::putIfAbsent);
                resolveDoctors(appts).forEach(doctorById::putIfAbsent);
            });

            model.addAttribute("view", "week");
            model.addAttribute("weekStart", weekStart);
            model.addAttribute("weekEnd", weekEnd);
            model.addAttribute("prevWeek", weekStart.minusWeeks(1));
            model.addAttribute("nextWeek", weekStart.plusWeeks(1));
            model.addAttribute("byDay", byDay);
            model.addAttribute("patientById", patientById);
            model.addAttribute("doctorById", doctorById);
            model.addAttribute("today", today);
            model.addAttribute("activeDoctors", doctorRepository.findByClinicIdAndActiveTrueOrderByFullNameAsc(clinicId));
        } else {
            // Month view
            YearMonth ym = monthParam != null
                    ? YearMonth.parse(monthParam)
                    : YearMonth.from(today);
            LocalDate firstDay = ym.atDay(1);
            LocalDate lastDay  = ym.atEndOfMonth();

            // Count appointments per day for the month
            Map<LocalDate, Long> countByDay = new LinkedHashMap<>();
            for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
                long cnt = appointmentService.listByDate(clinicId, d).stream()
                        .filter(a -> !a.isTerminal()).count();
                if (cnt > 0) countByDay.put(d, cnt);
            }

            // Build calendar grid — start from Monday of the week containing firstDay
            LocalDate gridStart = firstDay.with(java.time.DayOfWeek.MONDAY);
            if (gridStart.isAfter(firstDay)) gridStart = gridStart.minusWeeks(1);
            List<LocalDate> grid = new ArrayList<>();
            LocalDate cursor = gridStart;
            while (cursor.isBefore(lastDay) || cursor.getMonthValue() == lastDay.getMonthValue()
                    || grid.size() % 7 != 0) {
                grid.add(cursor);
                cursor = cursor.plusDays(1);
                if (grid.size() > 42) break; // max 6 weeks
            }

            model.addAttribute("view", "month");
            model.addAttribute("yearMonth", ym);
            model.addAttribute("prevMonth", ym.minusMonths(1));
            model.addAttribute("nextMonth", ym.plusMonths(1));
            model.addAttribute("grid", grid);
            model.addAttribute("countByDay", countByDay);
            model.addAttribute("today", today);
        }
        return "appointment/calendar";
    }

    // ── HTMX slot fragment ───────────────────────────────────────────────

    /**
     * Returns an HTML fragment with slot options for HTMX injection.
     * Called on doctor + date change in the booking form.
     * GET /appointments/slots-fragment?doctorId=X&date=YYYY-MM-DD
     */
    @GetMapping("/slots-fragment")
    public String slotsFragment(
            @RequestParam(value = "doctorId", required = false) Long doctorId,
            @RequestParam(value = "appointmentDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model) {

        Long clinicId = currentUser.clinicId();
        LocalDate apptDate = date != null ? date : LocalDate.now();

        if (doctorId == null) {
            model.addAttribute("slots", List.of());
            model.addAttribute("tokenOnly", false);
            model.addAttribute("nextToken", 1);
            model.addAttribute("configExists", false);
            return "appointment/slots-fragment :: slots";
        }

        DoctorScheduleConfig config = scheduleService.findConfig(clinicId, doctorId).orElse(null);
        boolean tokenOnly = config != null && config.isTokenOnly();
        List<SlotInfo> slots = tokenOnly ? List.of()
                : scheduleService.generateSlots(clinicId, doctorId, apptDate);
        int nextToken = scheduleService.nextToken(clinicId, doctorId, apptDate);

        model.addAttribute("slots", slots);
        model.addAttribute("tokenOnly", tokenOnly);
        model.addAttribute("nextToken", nextToken);
        model.addAttribute("configExists", config != null);
        return "appointment/slots-fragment :: slots";
    }

    // ── Booking form ─────────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "doctorId", required = false) Long doctorId,
            Model model) {

        Long clinicId = currentUser.clinicId();
        AppointmentForm form = new AppointmentForm();
        form.setAppointmentDate(date != null ? date : LocalDate.now());
        form.setDoctorId(doctorId);

        List<SlotInfo> slots = List.of();
        boolean tokenOnly = false;
        int nextToken = 1;
        boolean configExists = false;
        if (doctorId != null) {
            DoctorScheduleConfig config = scheduleService.findConfig(clinicId, doctorId).orElse(null);
            configExists = config != null;
            tokenOnly = config != null && config.isTokenOnly();
            slots = tokenOnly ? List.of()
                    : scheduleService.generateSlots(clinicId, doctorId, form.getAppointmentDate());
            nextToken = scheduleService.nextToken(clinicId, doctorId, form.getAppointmentDate());
        }

        model.addAttribute("form", form);
        model.addAttribute("doctors", doctorRepository.findByClinicIdAndActiveTrueOrderByFullNameAsc(clinicId));
        model.addAttribute("patients", patientRepository.findTop200ByClinicIdOrderByUpdatedAtDesc(clinicId));
        model.addAttribute("slots", slots);
        model.addAttribute("tokenOnly", tokenOnly);
        model.addAttribute("nextToken", nextToken);
        model.addAttribute("configExists", configExists);
        model.addAttribute("backDate", form.getAppointmentDate());
        return "appointment/book";
    }

    @PostMapping
    public String book(@ModelAttribute AppointmentForm form, RedirectAttributes ra) {
        Long clinicId = currentUser.clinicId();
        LocalDate apptDate = form.getAppointmentDate() != null ? form.getAppointmentDate() : LocalDate.now();
        try {
            Appointment appt = appointmentService.book(clinicId, form);
            ra.addFlashAttribute("flash", "Appointment booked — Token #" + appt.getTokenNo());
            return "redirect:/appointments?date=" + apptDate;
        } catch (IllegalArgumentException e) {
            // Validation error (e.g. stale slot on wrong day) — send user back to
            // the booking form with date + doctor pre-filled so they can correct it.
            log.warn("Booking rejected: {}", e.getMessage());
            ra.addFlashAttribute("flash", e.getMessage());
            String redirect = "/appointments/new?date=" + apptDate;
            if (form.getDoctorId() != null) redirect += "&doctorId=" + form.getDoctorId();
            return "redirect:" + redirect;
        } catch (Exception e) {
            log.warn("Booking failed", e);
            ra.addFlashAttribute("flash", "Couldn't book: " + e.getMessage());
            return "redirect:/appointments?date=" + apptDate;
        }
    }

    // ── Status transitions ───────────────────────────────────────────────

    /**
     * Quick status update from the day view.
     * POST /appointments/{id}/status?to=ARRIVED (etc.)
     */
    @PostMapping("/{id}/status")
    public String updateStatus(
            @PathVariable Long id,
            @RequestParam("to") String newStatus,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
            RedirectAttributes ra) {

        try {
            Appointment appt = appointmentService.updateStatus(id, newStatus);
            ra.addFlashAttribute("flash", statusMessage(newStatus, appt));
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("flash", e.getMessage());
        } catch (Exception e) {
            log.warn("Status update failed for appointment {}", id, e);
            ra.addFlashAttribute("flash", "Update failed: " + e.getMessage());
        }
        String dateParam = returnDate != null ? "?date=" + returnDate : "";
        return "redirect:/appointments" + dateParam;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<Long, Patient> resolvePatients(List<Appointment> appointments) {
        Map<Long, Patient> map = new LinkedHashMap<>();
        for (Appointment a : appointments) {
            if (a.getPatientId() != null && !map.containsKey(a.getPatientId())) {
                patientRepository.findById(a.getPatientId())
                        .ifPresent(p -> map.put(p.getId(), p));
            }
        }
        return map;
    }

    private Map<Long, Doctor> resolveDoctors(List<Appointment> appointments) {
        Map<Long, Doctor> map = new LinkedHashMap<>();
        for (Appointment a : appointments) {
            if (a.getDoctorId() != null && !map.containsKey(a.getDoctorId())) {
                doctorRepository.findById(a.getDoctorId())
                        .ifPresent(d -> map.put(d.getId(), d));
            }
        }
        return map;
    }

    private static String statusMessage(String status, Appointment appt) {
        return switch (status) {
            case "ARRIVED"     -> appt.displayName() + " marked as arrived.";
            case "IN_PROGRESS" -> "Consultation started for " + appt.displayName() + ".";
            case "DONE"        -> "Consultation done for " + appt.displayName() + ".";
            case "CANCELLED"   -> "Appointment cancelled for " + appt.displayName() + ".";
            case "NO_SHOW"     -> appt.displayName() + " marked as no-show.";
            default            -> "Status updated.";
        };
    }
}
