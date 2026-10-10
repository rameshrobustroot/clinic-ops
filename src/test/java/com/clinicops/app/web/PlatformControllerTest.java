package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.MedicalDegree;
import com.clinicops.app.domain.MedicalInvestigation;
import com.clinicops.app.domain.MedicalSpecialization;
import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.MedicalMasterService;
import com.clinicops.app.service.TermsService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link PlatformController} — the PLATFORM_ADMIN landing page. */
@WebMvcTest(PlatformController.class)
@Import(SecurityConfig.class)
class PlatformControllerTest {

    @Autowired MockMvc mvc;

    @MockBean TermsService termsService;
    @MockBean MedicalMasterService masterService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean ClinicRepository clinicRepository;

    @Test
    @WithMockAppUser(role = "PLATFORM_ADMIN")
    void platform_admin_sees_home_with_counts_and_current_terms() throws Exception {
        TermsDocument doc = TermsDocument.builder().id(1L).version("2026-09-01")
                .effectiveDate(LocalDate.of(2026, 9, 1)).build();
        when(termsService.getCurrent()).thenReturn(Optional.of(doc));
        when(masterService.listPlatformDegrees()).thenReturn(List.of(new MedicalDegree(), new MedicalDegree()));
        when(masterService.listPlatformSpecializations()).thenReturn(List.of(new MedicalSpecialization()));
        when(masterService.listPlatformInvestigations()).thenReturn(List.of());

        mvc.perform(get("/platform"))
                .andExpect(status().isOk())
                .andExpect(view().name("platform/home"))
                .andExpect(model().attribute("currentTerms", doc))
                .andExpect(model().attribute("degreeCount", 2))
                .andExpect(model().attribute("specializationCount", 1))
                .andExpect(model().attribute("investigationCount", 0))
                .andExpect(content().string(containsString("2026-09-01")));
    }

    @Test
    @WithMockAppUser(role = "PLATFORM_ADMIN")
    void home_renders_when_no_terms_published() throws Exception {
        when(termsService.getCurrent()).thenReturn(Optional.empty());

        mvc.perform(get("/platform"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("currentTerms", (Object) null));
    }

    @Test
    @WithMockAppUser(role = "PLATFORM_ADMIN")
    void platform_admin_is_never_gated_by_terms_acceptance() throws Exception {
        when(clinicRepository.findById(any())).thenReturn(Optional.of(Clinic.builder().id(1L).build()));
        when(termsService.requiresAcceptance(any())).thenReturn(true);

        mvc.perform(get("/platform")).andExpect(status().isOk());
        verify(termsService, never()).requiresAcceptance(any());
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void owner_is_forbidden() throws Exception {
        mvc.perform(get("/platform")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockAppUser(role = "DOCTOR")
    void doctor_is_forbidden() throws Exception {
        mvc.perform(get("/platform")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockAppUser(role = "PHARMACIST")
    void pharmacist_is_forbidden() throws Exception {
        mvc.perform(get("/platform")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockAppUser(role = "RECEPTIONIST")
    void receptionist_is_forbidden() throws Exception {
        mvc.perform(get("/platform")).andExpect(status().isForbidden());
    }

    @Test
    void anonymous_user_is_redirected_to_login() throws Exception {
        mvc.perform(get("/platform"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
