package com.clinicops.app.web;

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
import org.junit.jupiter.api.BeforeEach;
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
 * Controller-layer tests for {@link MedicalMasterController} — OWNER view of the merged
 * master lists; only clinic-scoped rows may be mutated.
 */
@WebMvcTest(MedicalMasterController.class)
@Import(SecurityConfig.class)
class MedicalMasterControllerTest {

    @Autowired MockMvc mvc;

    @MockBean MedicalMasterService masterService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private final MedicalDegree platformDegree =
            MedicalDegree.builder().id(1L).clinicId(null).name("MBBS").active(true).build();
    private final MedicalDegree clinicDegree =
            MedicalDegree.builder().id(2L).clinicId(1L).name("DNB").active(true).build();

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(masterService.getDegreeOrThrow(1L)).thenReturn(platformDegree);
        when(masterService.getDegreeOrThrow(2L)).thenReturn(clinicDegree);
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @ParameterizedTest
        @ValueSource(strings = {"/degrees", "/specializations", "/investigations"})
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_view_master_lists(String path) throws Exception {
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }

        @ParameterizedTest
        @ValueSource(strings = {"/degrees", "/specializations", "/investigations"})
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_add_master_values(String path) throws Exception {
            mvc.perform(post(path).with(csrf()).param("name", "X")).andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_rename() throws Exception {
            mvc.perform(post("/degrees/2/rename").with(csrf()).param("name", "X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_redirected_to_platform_namespace() throws Exception {
            mvc.perform(get("/degrees")).andExpect(redirectedUrl("/platform"));
            verifyNoInteractions(masterService);
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/degrees")).andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/degrees").param("name", "X")).andExpect(status().isForbidden());
            verifyNoInteractions(masterService);
        }
    }

    // ── Listing ────────────────────────────────────────────────────────

    @Nested
    class Listing {

        @Test
        @WithMockAppUser(role = "OWNER")
        void degrees_show_merged_list_for_clinic() throws Exception {
            when(masterService.listAllDegrees(1L)).thenReturn(List.of(platformDegree, clinicDegree));

            mvc.perform(get("/degrees"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("master-list/list"))
                    .andExpect(model().attribute("kind", "degrees"))
                    .andExpect(model().attribute("title", "Medical Degrees"))
                    .andExpect(model().attribute("items", List.of(platformDegree, clinicDegree)))
                    .andExpect(content().string(containsString("DNB")));
            verify(masterService).listAllDegrees(1L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void specializations_list_renders() throws Exception {
            when(masterService.listAllSpecializations(1L)).thenReturn(List.of(
                    MedicalSpecialization.builder().id(3L).clinicId(1L).name("Sports Medicine").active(false).build()));

            mvc.perform(get("/specializations"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("kind", "specializations"))
                    .andExpect(content().string(containsString("Sports Medicine")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void investigations_list_renders_empty() throws Exception {
            when(masterService.listAllInvestigations(1L)).thenReturn(List.of());

            mvc.perform(get("/investigations"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("kind", "investigations"))
                    .andExpect(model().attribute("title", "Investigations"));
        }
    }

    // ── Create ────────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "OWNER")
        void adds_clinic_degree() throws Exception {
            when(masterService.createClinicDegree(1L, "DNB")).thenReturn(clinicDegree);

            mvc.perform(post("/degrees").with(csrf()).param("name", "DNB"))
                    .andExpect(redirectedUrl("/degrees"))
                    .andExpect(flash().attribute("flash", "Added: DNB"));
            verify(masterService).createClinicDegree(1L, "DNB");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void existing_platform_value_shows_info_flash() throws Exception {
            when(masterService.createClinicDegree(1L, "mbbs")).thenReturn(platformDegree);

            mvc.perform(post("/degrees").with(csrf()).param("name", "mbbs"))
                    .andExpect(redirectedUrl("/degrees"))
                    .andExpect(flash().attribute("flashInfo", containsString("'MBBS' is already in the platform list")))
                    .andExpect(flash().attributeCount(1));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_name_shows_error_flash() throws Exception {
            when(masterService.createClinicDegree(1L, ""))
                    .thenThrow(new IllegalArgumentException("Name is required"));

            mvc.perform(post("/degrees").with(csrf()).param("name", ""))
                    .andExpect(redirectedUrl("/degrees"))
                    .andExpect(flash().attribute("flash", "Couldn't add: Name is required"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void adds_clinic_specialization() throws Exception {
            when(masterService.createClinicSpecialization(1L, "Sports"))
                    .thenReturn(MedicalSpecialization.builder().id(4L).clinicId(1L).name("Sports").build());

            mvc.perform(post("/specializations").with(csrf()).param("name", "Sports"))
                    .andExpect(redirectedUrl("/specializations"))
                    .andExpect(flash().attribute("flash", "Added: Sports"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void adds_clinic_investigation() throws Exception {
            when(masterService.createClinicInvestigation(1L, "HbA1c"))
                    .thenReturn(MedicalInvestigation.builder().id(5L).clinicId(1L).name("HbA1c").build());

            mvc.perform(post("/investigations").with(csrf()).param("name", "HbA1c"))
                    .andExpect(redirectedUrl("/investigations"))
                    .andExpect(flash().attribute("flash", "Added: HbA1c"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void investigation_create_failure_shows_error_flash() throws Exception {
            when(masterService.createClinicInvestigation(1L, "dup"))
                    .thenThrow(new IllegalArgumentException("Already exists"));

            mvc.perform(post("/investigations").with(csrf()).param("name", "dup"))
                    .andExpect(flash().attribute("flash", "Couldn't add: Already exists"));
        }
    }

    // ── Rename / delete / restore ──────────────────────────────────────

    @Nested
    class Mutations {

        @Test
        @WithMockAppUser(role = "OWNER")
        void renames_clinic_degree() throws Exception {
            mvc.perform(post("/degrees/2/rename").with(csrf()).param("name", "DNB (Med)"))
                    .andExpect(redirectedUrl("/degrees"))
                    .andExpect(flash().attribute("flash", "Renamed."));
            verify(masterService).renameDegree(2L, "DNB (Med)");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void rename_failure_shows_error_flash() throws Exception {
            when(masterService.renameDegree(2L, "MBBS"))
                    .thenThrow(new IllegalArgumentException("Duplicate name"));

            mvc.perform(post("/degrees/2/rename").with(csrf()).param("name", "MBBS"))
                    .andExpect(flash().attribute("flash", "Couldn't rename: Duplicate name"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cannot_rename_platform_degree() throws Exception {
            mvc.perform(post("/degrees/1/rename").with(csrf()).param("name", "Hijacked"))
                    .andExpect(redirectedUrl("/degrees"))
                    .andExpect(flash().attribute("flash", containsString("can't be edited here")));
            verify(masterService, never()).renameDegree(anyLong(), anyString());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cannot_hide_platform_degree() throws Exception {
            mvc.perform(post("/degrees/1/delete").with(csrf()))
                    .andExpect(redirectedUrl("/degrees"))
                    .andExpect(flash().attribute("flash", containsString("can't be hidden")));
            verify(masterService, never()).softDeleteDegree(anyLong());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cannot_restore_platform_degree() throws Exception {
            mvc.perform(post("/degrees/1/restore").with(csrf()))
                    .andExpect(redirectedUrl("/degrees"));
            verify(masterService, never()).restoreDegree(anyLong());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void hides_and_restores_clinic_degree() throws Exception {
            mvc.perform(post("/degrees/2/delete").with(csrf()))
                    .andExpect(flash().attribute("flash", "Hidden from picker."));
            mvc.perform(post("/degrees/2/restore").with(csrf()))
                    .andExpect(flash().attribute("flash", "Restored."));
            verify(masterService).softDeleteDegree(2L);
            verify(masterService).restoreDegree(2L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void specialization_platform_row_is_protected_and_clinic_row_is_editable() throws Exception {
            when(masterService.getSpecializationOrThrow(10L))
                    .thenReturn(MedicalSpecialization.builder().id(10L).name("Cardiology").build());
            when(masterService.getSpecializationOrThrow(11L))
                    .thenReturn(MedicalSpecialization.builder().id(11L).clinicId(1L).name("Sports").build());

            mvc.perform(post("/specializations/10/rename").with(csrf()).param("name", "X"))
                    .andExpect(redirectedUrl("/specializations"));
            mvc.perform(post("/specializations/10/delete").with(csrf()))
                    .andExpect(redirectedUrl("/specializations"));
            mvc.perform(post("/specializations/10/restore").with(csrf()))
                    .andExpect(redirectedUrl("/specializations"));
            mvc.perform(post("/specializations/11/rename").with(csrf()).param("name", "Sports Med"))
                    .andExpect(flash().attribute("flash", "Renamed."));
            mvc.perform(post("/specializations/11/delete").with(csrf()))
                    .andExpect(flash().attribute("flash", "Hidden from picker."));
            mvc.perform(post("/specializations/11/restore").with(csrf()))
                    .andExpect(flash().attribute("flash", "Restored."));

            verify(masterService, never()).renameSpecialization(10L, "X");
            verify(masterService, never()).softDeleteSpecialization(10L);
            verify(masterService, never()).restoreSpecialization(10L);
            verify(masterService).renameSpecialization(11L, "Sports Med");
            verify(masterService).softDeleteSpecialization(11L);
            verify(masterService).restoreSpecialization(11L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void investigation_platform_row_is_protected_and_clinic_row_is_editable() throws Exception {
            when(masterService.getInvestigationOrThrow(20L))
                    .thenReturn(MedicalInvestigation.builder().id(20L).name("CBC").build());
            when(masterService.getInvestigationOrThrow(21L))
                    .thenReturn(MedicalInvestigation.builder().id(21L).clinicId(1L).name("HbA1c").build());

            mvc.perform(post("/investigations/20/rename").with(csrf()).param("name", "X"))
                    .andExpect(redirectedUrl("/investigations"));
            mvc.perform(post("/investigations/20/delete").with(csrf()))
                    .andExpect(redirectedUrl("/investigations"));
            mvc.perform(post("/investigations/20/restore").with(csrf()))
                    .andExpect(redirectedUrl("/investigations"));
            mvc.perform(post("/investigations/21/rename").with(csrf()).param("name", "A1c"))
                    .andExpect(flash().attribute("flash", "Renamed."));
            mvc.perform(post("/investigations/21/delete").with(csrf()))
                    .andExpect(flash().attribute("flash", "Hidden from picker."));
            mvc.perform(post("/investigations/21/restore").with(csrf()))
                    .andExpect(flash().attribute("flash", "Restored."));

            verify(masterService, never()).renameInvestigation(20L, "X");
            verify(masterService, never()).softDeleteInvestigation(20L);
            verify(masterService, never()).restoreInvestigation(20L);
            verify(masterService).renameInvestigation(21L, "A1c");
            verify(masterService).softDeleteInvestigation(21L);
            verify(masterService).restoreInvestigation(21L);
        }
    }
}
