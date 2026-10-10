package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Appointment;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.AppointmentService;
import com.clinicops.app.service.DoctorScheduleService;
import com.clinicops.app.service.TermsService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for {@link AppointmentController}.
 *
 * <p>Uses {@code @WebMvcTest} so only the web layer loads — all services and repositories
 * are mocked. Security is applied via {@code SecurityConfig} import so {@code @PreAuthorize}
 * is enforced correctly.
 */
@WebMvcTest(AppointmentController.class)
@Import(SecurityConfig.class)
class AppointmentControllerTest {

    @Autowired MockMvc mvc;

    // --- mocked dependencies of AppointmentController ---
    @MockBean AppointmentService appointmentService;
    @MockBean DoctorScheduleService scheduleService;
    @MockBean PatientRepository patientRepository;
    @MockBean DoctorRepository doctorRepository;
    @MockBean CurrentUser currentUser;

    // Needed so Spring Security doesn't fail to find a UserDetailsService
    @MockBean AppUserDetailsService appUserDetailsService;
    // WebMvcConfig → TermsAcceptanceInterceptor → TermsService + ClinicRepository
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(patientRepository.findTop200ByClinicIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of());
        when(doctorRepository.findByClinicIdAndActiveTrueOrderByFullNameAsc(1L)).thenReturn(List.of());
    }

    // ── GET /appointments/new ────────────────────────────────────────────

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_returns_200_with_form_model() throws Exception {
        mvc.perform(get("/appointments/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("appointment/book"))
                .andExpect(model().attributeExists("form", "doctors", "patients"));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_pre_fills_date_and_doctor_from_query_params() throws Exception {
        when(scheduleService.findConfig(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(scheduleService.nextToken(anyLong(), anyLong(), any())).thenReturn(1);

        mvc.perform(get("/appointments/new")
                        .param("date", "2026-06-01")
                        .param("doctorId", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("form"));
    }

    @Test
    void unauthenticated_request_is_redirected_to_login() throws Exception {
        mvc.perform(get("/appointments/new"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockAppUser(role = "RECEPTIONIST")
    void receptionist_can_access_booking_form() throws Exception {
        mvc.perform(get("/appointments/new"))
                .andExpect(status().isOk());
    }

    // ── POST /appointments ───────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "OWNER")
    void successful_booking_redirects_to_day_view() throws Exception {
        Appointment booked = Appointment.builder()
                .id(1L).tokenNo(3).status("SCHEDULED")
                .appointmentDate(LocalDate.of(2026, 6, 1))
                .build();
        when(appointmentService.book(anyLong(), any())).thenReturn(booked);

        mvc.perform(post("/appointments").with(csrf())
                        .param("appointmentDate", "2026-06-01")
                        .param("patientName", "Walk-in Patient"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/appointments*"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void booking_with_invalid_day_redirects_back_to_new_form() throws Exception {
        when(appointmentService.book(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("not available on Tuesday"));

        mvc.perform(post("/appointments").with(csrf())
                        .param("appointmentDate", "2026-06-02")
                        .param("doctorId", "10")
                        .param("patientName", "Walk-in"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/appointments/new*"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void booking_on_non_working_day_redirect_includes_doctor_and_date_params() throws Exception {
        when(appointmentService.book(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("not available on Tuesday"));

        String location = mvc.perform(post("/appointments").with(csrf())
                        .param("appointmentDate", "2026-06-02")
                        .param("doctorId", "10")
                        .param("patientName", "Test"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        assert location != null;
        assert location.contains("date=2026-06-02");
        assert location.contains("doctorId=10");
    }

    // ── GET /appointments/slots-fragment ────────────────────────────────

    @Test
    @WithMockAppUser(role = "OWNER")
    void slots_fragment_returns_200_with_no_doctor() throws Exception {
        mvc.perform(get("/appointments/slots-fragment"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void slots_fragment_returns_200_with_doctor_and_date() throws Exception {
        when(scheduleService.findConfig(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(scheduleService.generateSlots(anyLong(), anyLong(), any())).thenReturn(List.of());
        when(scheduleService.nextToken(anyLong(), anyLong(), any())).thenReturn(1);

        mvc.perform(get("/appointments/slots-fragment")
                        .param("doctorId", "10")
                        .param("appointmentDate", "2026-06-01"))
                .andExpect(status().isOk());
    }

    // ── POST /appointments/{id}/status ───────────────────────────────────

    @Test
    @WithMockUser(roles = "DOCTOR")
    void status_update_redirects_back_to_day_view() throws Exception {
        Appointment appt = Appointment.builder()
                .id(1L).status("ARRIVED").clinicId(1L)
                .appointmentDate(LocalDate.of(2026, 6, 1))
                .patientName("Patient A")
                .build();
        when(appointmentService.updateStatus(1L, "ARRIVED")).thenReturn(appt);

        mvc.perform(post("/appointments/1/status").with(csrf())
                        .param("to", "ARRIVED")
                        .param("date", "2026-06-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/appointments*"));
    }
}
