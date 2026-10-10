package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.clinicops.app.domain.MedicalDegree;
import com.clinicops.app.domain.MedicalSpecialization;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.DoctorService;
import com.clinicops.app.service.MedicalMasterService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.tenant.CrossTenantAccessException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link DoctorController} — OWNER-only doctor directory CRUD. */
@WebMvcTest(DoctorController.class)
@Import(SecurityConfig.class)
class DoctorControllerTest {

    @Autowired MockMvc mvc;

    @MockBean DoctorService doctorService;
    @MockBean MedicalMasterService masterService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(masterService.listActiveDegrees(1L)).thenReturn(List.of(
                MedicalDegree.builder().id(1L).name("MBBS").active(true).build()));
        when(masterService.listActiveSpecializations(1L)).thenReturn(List.of(
                MedicalSpecialization.builder().id(1L).name("Cardiology").active(true).build()));
        doctor = Doctor.builder().id(7L).clinicId(1L).fullName("Meera Iyer").qualification("MBBS")
                .specialization("Cardiology").phone("9876543210").active(true).build();
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_list_doctors() throws Exception {
            mvc.perform(get("/doctors")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_create_doctor() throws Exception {
            mvc.perform(post("/doctors").with(csrf()).param("fullName", "Dr X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(doctorService);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_deactivate_doctor() throws Exception {
            mvc.perform(post("/doctors/7/delete").with(csrf())).andExpect(status().isForbidden());
            verifyNoInteractions(doctorService);
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_edit_doctor() throws Exception {
            mvc.perform(post("/doctors/7").with(csrf()).param("fullName", "Hacked"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(doctorService);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_redirected_to_platform() throws Exception {
            mvc.perform(get("/doctors")).andExpect(redirectedUrl("/platform"));
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/doctors"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/doctors").param("fullName", "Dr X")).andExpect(status().isForbidden());
            verifyNoInteractions(doctorService);
        }
    }

    // ── GET /doctors, /doctors/new, /doctors/{id}/edit ────────────────

    @Nested
    class Views {

        @Test
        @WithMockAppUser(role = "OWNER")
        void lists_all_doctors_for_clinic() throws Exception {
            Doctor inactive = Doctor.builder().id(8L).clinicId(1L).fullName("Old Doc").active(false).build();
            when(doctorService.listAll(1L)).thenReturn(List.of(doctor, inactive));

            mvc.perform(get("/doctors"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("doctor/list"))
                    .andExpect(model().attribute("doctors", List.of(doctor, inactive)))
                    .andExpect(content().string(containsString("Meera Iyer")));
            verify(doctorService).listAll(1L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void new_form_has_empty_doctor_and_master_lists_as_json() throws Exception {
            mvc.perform(get("/doctors/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("doctor/form"))
                    .andExpect(model().attributeExists("doctor", "masterDegrees", "masterSpecializations"))
                    .andExpect(model().attribute("masterDegreesJson", "[\"MBBS\"]"))
                    .andExpect(model().attribute("masterSpecializationsJson", "[\"Cardiology\"]"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_loads_doctor() throws Exception {
            when(doctorService.getOrThrow(7L)).thenReturn(doctor);

            mvc.perform(get("/doctors/7/edit"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("doctor/form"))
                    .andExpect(model().attribute("doctor", doctor))
                    .andExpect(content().string(containsString("Meera Iyer")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_for_other_clinic_doctor_is_404() throws Exception {
            when(doctorService.getOrThrow(99L)).thenThrow(new CrossTenantAccessException());

            mvc.perform(get("/doctors/99/edit"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }

    // ── POST /doctors ─────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "OWNER")
        void creates_doctor_in_owner_clinic() throws Exception {
            when(doctorService.create(eq(1L), any())).thenReturn(doctor);

            mvc.perform(post("/doctors").with(csrf())
                            .param("fullName", "Meera Iyer")
                            .param("qualification", "MBBS")
                            .param("phone", "9876543210"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/doctors"))
                    .andExpect(flash().attribute("flash", "Doctor added: Dr Meera Iyer"));

            ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
            verify(doctorService).create(eq(1L), captor.capture());
            assertThat(captor.getValue().getFullName()).isEqualTo("Meera Iyer");
            assertThat(captor.getValue().getQualification()).isEqualTo("MBBS");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void blank_name_rerenders_form_with_error() throws Exception {
            mvc.perform(post("/doctors").with(csrf()).param("fullName", ""))
                    .andExpect(status().isOk())
                    .andExpect(view().name("doctor/form"))
                    .andExpect(model().attribute("error", "Name is required"))
                    .andExpect(model().attributeExists("masterDegreesJson"))
                    .andExpect(content().string(containsString("Name is required")));
            verify(doctorService, never()).create(anyLong(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_phone_rerenders_form() throws Exception {
            mvc.perform(post("/doctors").with(csrf()).param("fullName", "Dr X").param("phone", "12345"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("doctor/form"))
                    .andExpect(model().attributeExists("error"));
            verify(doctorService, never()).create(anyLong(), any());
        }
    }

    // ── POST /doctors/{id}, delete, restore ───────────────────────────

    @Nested
    class Mutations {

        @Test
        @WithMockAppUser(role = "OWNER")
        void updates_doctor() throws Exception {
            mvc.perform(post("/doctors/7").with(csrf()).param("fullName", "Meera R Iyer"))
                    .andExpect(redirectedUrl("/doctors"))
                    .andExpect(flash().attribute("flash", "Doctor updated."));

            ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
            verify(doctorService).update(eq(7L), captor.capture());
            assertThat(captor.getValue().getFullName()).isEqualTo("Meera R Iyer");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_update_rerenders_form() throws Exception {
            mvc.perform(post("/doctors/7").with(csrf()).param("fullName", " "))
                    .andExpect(status().isOk())
                    .andExpect(view().name("doctor/form"))
                    .andExpect(model().attribute("error", "Name is required"));
            verify(doctorService, never()).update(anyLong(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void deactivates_doctor() throws Exception {
            mvc.perform(post("/doctors/7/delete").with(csrf()))
                    .andExpect(redirectedUrl("/doctors"))
                    .andExpect(flash().attribute("flash", "Doctor deactivated."));
            verify(doctorService).softDelete(7L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void reactivates_doctor() throws Exception {
            mvc.perform(post("/doctors/7/restore").with(csrf()))
                    .andExpect(redirectedUrl("/doctors"))
                    .andExpect(flash().attribute("flash", "Doctor reactivated."));
            verify(doctorService).restore(7L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void deactivating_other_clinic_doctor_is_404() throws Exception {
            org.mockito.Mockito.doThrow(new CrossTenantAccessException()).when(doctorService).softDelete(99L);

            mvc.perform(post("/doctors/99/delete").with(csrf()))
                    .andExpect(status().isNotFound());
        }
    }
}
