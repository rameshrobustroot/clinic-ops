package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.clinicops.app.domain.MedicalDegree;
import com.clinicops.app.domain.MedicalInvestigation;
import com.clinicops.app.domain.MedicalSpecialization;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.MedicalMasterService;
import com.clinicops.app.service.TermsService;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for {@link PlatformMasterController} — PLATFORM_ADMIN editor for the
 * platform-wide (clinic_id IS NULL) master lists.
 */
@WebMvcTest(PlatformMasterController.class)
@Import(SecurityConfig.class)
class PlatformMasterControllerTest {

    @Autowired MockMvc mvc;

    @MockBean MedicalMasterService masterService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private static MedicalDegree degree(Long id, Long clinicId, String name) {
        return MedicalDegree.builder().id(id).clinicId(clinicId).name(name).active(true).build();
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @ParameterizedTest
        @ValueSource(strings = {"/platform/degrees", "/platform/specializations", "/platform/investigations"})
        @WithMockAppUser(role = "OWNER")
        void owner_cannot_view_platform_lists(String path) throws Exception {
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @ValueSource(strings = {"/platform/degrees", "/platform/specializations", "/platform/investigations"})
        @WithMockAppUser(role = "OWNER")
        void owner_cannot_create_platform_rows(String path) throws Exception {
            mvc.perform(post(path).with(csrf()).param("name", "MBBS"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_rename_platform_degree() throws Exception {
            mvc.perform(post("/platform/degrees/1/rename").with(csrf()).param("name", "X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_delete_platform_specialization() throws Exception {
            mvc.perform(post("/platform/specializations/1/delete").with(csrf()))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_restore_platform_investigation() throws Exception {
            mvc.perform(post("/platform/investigations/1/restore").with(csrf()))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/platform/degrees"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/platform/degrees").param("name", "MBBS"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }
    }

    // ── Listing ────────────────────────────────────────────────────────

    @Nested
    class Listing {

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void degrees_list_shows_platform_rows() throws Exception {
            List<MedicalDegree> items = List.of(degree(1L, null, "MBBS"));
            when(masterService.listPlatformDegrees()).thenReturn(items);

            mvc.perform(get("/platform/degrees"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("platform/master-list/list"))
                    .andExpect(model().attribute("kind", "degrees"))
                    .andExpect(model().attribute("title", "Platform Degrees"))
                    .andExpect(model().attribute("items", items))
                    .andExpect(content().string(containsString("MBBS")));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void specializations_list_renders() throws Exception {
            when(masterService.listPlatformSpecializations()).thenReturn(List.of(
                    MedicalSpecialization.builder().id(2L).name("Cardiology").active(false).build()));

            mvc.perform(get("/platform/specializations"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("kind", "specializations"))
                    .andExpect(content().string(containsString("Cardiology")));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void investigations_list_renders_when_empty() throws Exception {
            when(masterService.listPlatformInvestigations()).thenReturn(List.of());

            mvc.perform(get("/platform/investigations"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("kind", "investigations"))
                    .andExpect(model().attribute("title", "Platform Investigations"));
        }
    }

    // ── Create ────────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void creates_platform_degree() throws Exception {
            mvc.perform(post("/platform/degrees").with(csrf()).param("name", "MBBS"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform/degrees"))
                    .andExpect(flash().attribute("flash", "Added: MBBS"));
            verify(masterService).createPlatformDegree("MBBS");
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void duplicate_degree_shows_error_flash() throws Exception {
            when(masterService.createPlatformDegree("MBBS"))
                    .thenThrow(new IllegalArgumentException("Already exists"));

            mvc.perform(post("/platform/degrees").with(csrf()).param("name", "MBBS"))
                    .andExpect(redirectedUrl("/platform/degrees"))
                    .andExpect(flash().attribute("flash", "Couldn't add: Already exists"));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void creates_platform_specialization() throws Exception {
            mvc.perform(post("/platform/specializations").with(csrf()).param("name", "ENT"))
                    .andExpect(redirectedUrl("/platform/specializations"));
            verify(masterService).createPlatformSpecialization("ENT");
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void creates_platform_investigation() throws Exception {
            mvc.perform(post("/platform/investigations").with(csrf()).param("name", "CBC"))
                    .andExpect(redirectedUrl("/platform/investigations"));
            verify(masterService).createPlatformInvestigation("CBC");
        }
    }

    // ── Rename / delete / restore ──────────────────────────────────────

    @Nested
    class Mutations {

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void renames_platform_degree() throws Exception {
            when(masterService.getDegreeOrThrow(1L)).thenReturn(degree(1L, null, "MBBS"));

            mvc.perform(post("/platform/degrees/1/rename").with(csrf()).param("name", "M.B.B.S"))
                    .andExpect(redirectedUrl("/platform/degrees"))
                    .andExpect(flash().attribute("flash", "Renamed."));
            verify(masterService).renameDegree(1L, "M.B.B.S");
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void rename_failure_shows_error_flash() throws Exception {
            when(masterService.getDegreeOrThrow(1L)).thenReturn(degree(1L, null, "MBBS"));
            when(masterService.renameDegree(1L, "")).thenThrow(new IllegalArgumentException("Name is required"));

            mvc.perform(post("/platform/degrees/1/rename").with(csrf()).param("name", ""))
                    .andExpect(redirectedUrl("/platform/degrees"))
                    .andExpect(flash().attribute("flash", "Couldn't rename: Name is required"));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void refuses_to_rename_clinic_scoped_degree() {
            when(masterService.getDegreeOrThrow(5L)).thenReturn(degree(5L, 3L, "Clinic Degree"));

            assertThatThrownBy(() -> mvc.perform(
                    post("/platform/degrees/5/rename").with(csrf()).param("name", "Hijacked")))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            verify(masterService, never()).renameDegree(anyLong(), anyString());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void refuses_to_delete_clinic_scoped_degree() {
            when(masterService.getDegreeOrThrow(5L)).thenReturn(degree(5L, 3L, "Clinic Degree"));

            assertThatThrownBy(() -> mvc.perform(post("/platform/degrees/5/delete").with(csrf())))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            verify(masterService, never()).softDeleteDegree(anyLong());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void soft_deletes_platform_degree() throws Exception {
            when(masterService.getDegreeOrThrow(1L)).thenReturn(degree(1L, null, "MBBS"));

            mvc.perform(post("/platform/degrees/1/delete").with(csrf()))
                    .andExpect(redirectedUrl("/platform/degrees"))
                    .andExpect(flash().attribute("flash", "Hidden from every clinic's picker."));
            verify(masterService).softDeleteDegree(1L);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void restores_platform_degree() throws Exception {
            when(masterService.getDegreeOrThrow(1L)).thenReturn(degree(1L, null, "MBBS"));

            mvc.perform(post("/platform/degrees/1/restore").with(csrf()))
                    .andExpect(redirectedUrl("/platform/degrees"));
            verify(masterService).restoreDegree(1L);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void renames_and_deletes_platform_specialization() throws Exception {
            when(masterService.getSpecializationOrThrow(2L))
                    .thenReturn(MedicalSpecialization.builder().id(2L).name("ENT").build());

            mvc.perform(post("/platform/specializations/2/rename").with(csrf()).param("name", "Otolaryngology"))
                    .andExpect(redirectedUrl("/platform/specializations"));
            mvc.perform(post("/platform/specializations/2/delete").with(csrf()))
                    .andExpect(redirectedUrl("/platform/specializations"));
            mvc.perform(post("/platform/specializations/2/restore").with(csrf()))
                    .andExpect(redirectedUrl("/platform/specializations"));

            verify(masterService).renameSpecialization(2L, "Otolaryngology");
            verify(masterService).softDeleteSpecialization(2L);
            verify(masterService).restoreSpecialization(2L);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void refuses_to_touch_clinic_scoped_specialization() {
            when(masterService.getSpecializationOrThrow(2L))
                    .thenReturn(MedicalSpecialization.builder().id(2L).clinicId(7L).name("ENT").build());

            assertThatThrownBy(() -> mvc.perform(post("/platform/specializations/2/restore").with(csrf())))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            verify(masterService, never()).restoreSpecialization(anyLong());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void renames_and_deletes_platform_investigation() throws Exception {
            when(masterService.getInvestigationOrThrow(3L))
                    .thenReturn(MedicalInvestigation.builder().id(3L).name("CBC").build());

            mvc.perform(post("/platform/investigations/3/rename").with(csrf()).param("name", "Complete Blood Count"))
                    .andExpect(redirectedUrl("/platform/investigations"));
            mvc.perform(post("/platform/investigations/3/delete").with(csrf()))
                    .andExpect(redirectedUrl("/platform/investigations"));
            mvc.perform(post("/platform/investigations/3/restore").with(csrf()))
                    .andExpect(redirectedUrl("/platform/investigations"));

            verify(masterService).renameInvestigation(3L, "Complete Blood Count");
            verify(masterService).softDeleteInvestigation(3L);
            verify(masterService).restoreInvestigation(3L);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void refuses_to_delete_clinic_scoped_investigation() {
            when(masterService.getInvestigationOrThrow(3L))
                    .thenReturn(MedicalInvestigation.builder().id(3L).clinicId(7L).name("CBC").build());

            assertThatThrownBy(() -> mvc.perform(post("/platform/investigations/3/delete").with(csrf())))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            verify(masterService, never()).softDeleteInvestigation(anyLong());
        }
    }
}
