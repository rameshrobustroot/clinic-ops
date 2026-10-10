package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Doctor;
import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.DoctorWorkingHours;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.DoctorScheduleForm;
import com.clinicops.app.service.DoctorScheduleService;
import com.clinicops.app.service.TermsService;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link DoctorScheduleController} — OWNER-only schedule config. */
@WebMvcTest(DoctorScheduleController.class)
@Import(SecurityConfig.class)
class DoctorScheduleControllerTest {

    @Autowired MockMvc mvc;

    @MockBean DoctorScheduleService scheduleService;
    @MockBean DoctorRepository doctorRepository;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(doctorRepository.findById(7L)).thenReturn(Optional.of(
                Doctor.builder().id(7L).clinicId(1L).fullName("Meera Iyer").active(true).build()));
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_view_schedule_config() throws Exception {
            mvc.perform(get("/doctors/7/schedule")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_save_schedule() throws Exception {
            mvc.perform(post("/doctors/7/schedule").with(csrf()).param("bookingMode", "TOKEN_ONLY"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(scheduleService);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_view_schedule_config() throws Exception {
            mvc.perform(get("/doctors/7/schedule")).andExpect(status().isForbidden());
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/doctors/7/schedule"))
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/doctors/7/schedule").param("bookingMode", "TOKEN_ONLY"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(scheduleService);
        }
    }

    // ── GET /doctors/{id}/schedule ────────────────────────────────────

    @Nested
    class ShowConfig {

        @Test
        @WithMockAppUser(role = "OWNER")
        void defaults_to_15_minute_slots_when_unconfigured() throws Exception {
            when(scheduleService.findConfig(1L, 7L)).thenReturn(Optional.empty());
            when(scheduleService.findWorkingHours(1L, 7L)).thenReturn(List.of());

            mvc.perform(get("/doctors/7/schedule"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("appointment/schedule-config"))
                    .andExpect(model().attribute("form", hasProperty("bookingMode", is("SLOT_BASED"))))
                    .andExpect(model().attribute("form", hasProperty("slotDurationMinutes", is(15))))
                    .andExpect(model().attribute("form", hasProperty("maxPerSlot", is(1))))
                    .andExpect(model().attribute("form", hasProperty("maxPerDay", nullValue())))
                    .andExpect(model().attribute("sessionsJson", "[]"))
                    .andExpect(model().attributeExists("doctor", "dayNames"))
                    .andExpect(content().string(containsString("Meera Iyer")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void populates_form_from_saved_config_and_serialises_active_hours() throws Exception {
            when(scheduleService.findConfig(1L, 7L)).thenReturn(Optional.of(DoctorScheduleConfig.builder()
                    .clinicId(1L).doctorId(7L).bookingMode("TOKEN_ONLY")
                    .slotDurationMinutes(20).maxPerSlot(2).maxPerDay(40).build()));
            when(scheduleService.findWorkingHours(1L, 7L)).thenReturn(List.of(
                    DoctorWorkingHours.builder().dayOfWeek(1).startTime(LocalTime.of(9, 0))
                            .endTime(LocalTime.of(13, 0)).active(true).build(),
                    DoctorWorkingHours.builder().dayOfWeek(2).startTime(LocalTime.of(14, 0))
                            .endTime(LocalTime.of(18, 30)).active(false).build()));

            mvc.perform(get("/doctors/7/schedule"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("form", hasProperty("bookingMode", is("TOKEN_ONLY"))))
                    .andExpect(model().attribute("form", hasProperty("slotDurationMinutes", is(20))))
                    .andExpect(model().attribute("form", hasProperty("maxPerDay", is(40))))
                    // inactive Tuesday row is dropped
                    .andExpect(model().attribute("sessionsJson", "[{\"d\":1,\"s\":\"09:00\",\"e\":\"13:00\"}]"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void unknown_doctor_is_404() throws Exception {
            when(doctorRepository.findById(404L)).thenReturn(Optional.empty());

            mvc.perform(get("/doctors/404/schedule"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }

    // ── POST /doctors/{id}/schedule ───────────────────────────────────

    @Nested
    class SaveConfig {

        @Test
        @WithMockAppUser(role = "OWNER")
        void saves_schedule_for_doctor_in_owner_clinic() throws Exception {
            mvc.perform(post("/doctors/7/schedule").with(csrf())
                            .param("bookingMode", "SLOT_BASED")
                            .param("slotDurationMinutes", "30")
                            .param("maxPerSlot", "2")
                            .param("maxPerDay", "25")
                            .param("sessionsJson", "[{\"d\":1,\"s\":\"09:00\",\"e\":\"13:00\"}]"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/doctors/7/schedule"))
                    .andExpect(flash().attribute("flash", "Schedule saved."));

            ArgumentCaptor<DoctorScheduleForm> captor = ArgumentCaptor.forClass(DoctorScheduleForm.class);
            verify(scheduleService).saveSchedule(eq(1L), eq(7L), captor.capture());
            DoctorScheduleForm form = captor.getValue();
            assertThat(form.getBookingMode()).isEqualTo("SLOT_BASED");
            assertThat(form.getSlotDurationMinutes()).isEqualTo(30);
            assertThat(form.getMaxPerSlot()).isEqualTo(2);
            assertThat(form.getMaxPerDay()).isEqualTo(25);
            assertThat(form.getSessionsJson()).contains("09:00");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void service_failure_is_reported_in_flash() throws Exception {
            doThrow(new IllegalArgumentException("End time must be after start time"))
                    .when(scheduleService).saveSchedule(anyLong(), anyLong(), any());

            mvc.perform(post("/doctors/7/schedule").with(csrf()).param("bookingMode", "SLOT_BASED"))
                    .andExpect(redirectedUrl("/doctors/7/schedule"))
                    .andExpect(flash().attribute("flash", "Save failed: End time must be after start time"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void forbidden_role_never_reaches_service() throws Exception {
            mvc.perform(post("/doctors/7/schedule").with(csrf()).param("bookingMode", "SLOT_BASED"))
                    .andExpect(status().isForbidden());
            verify(scheduleService, never()).saveSchedule(anyLong(), anyLong(), any());
        }
    }
}
