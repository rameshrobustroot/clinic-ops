package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.legal.TermsVersion;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.TermsService;
import java.time.LocalDate;
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

/**
 * Controller-layer tests for {@link TermsController}: the public terms page and the
 * OWNER-only forced re-acceptance flow (including its interaction with
 * {@code TermsAcceptanceInterceptor}).
 */
@WebMvcTest(TermsController.class)
@Import(SecurityConfig.class)
class TermsControllerTest {

    @Autowired MockMvc mvc;

    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;

    private Clinic clinic;
    private TermsDocument currentDoc;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        clinic = Clinic.builder().id(1L).code("sunrise").name("Sunrise Clinic").termsVersion("2025-01-01").build();
        currentDoc = TermsDocument.builder()
                .id(10L).version("2026-09-01").body("Be nice to patients.")
                .effectiveDate(LocalDate.of(2026, 9, 1)).build();
        when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
    }

    // ── GET /terms ──────────────────────────────────────────────────────

    @Nested
    class PublicTerms {

        @Test
        void anonymous_user_sees_current_terms() throws Exception {
            when(termsService.getCurrent()).thenReturn(Optional.of(currentDoc));

            mvc.perform(get("/terms"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("legal/terms"))
                    .andExpect(model().attribute("termsVersion", "2026-09-01"))
                    .andExpect(model().attribute("termsBody", "Be nice to patients."))
                    .andExpect(model().attribute("termsEffectiveDate", LocalDate.of(2026, 9, 1)))
                    .andExpect(content().string(containsString("Be nice to patients.")));
        }

        @Test
        void falls_back_to_seed_version_when_nothing_published() throws Exception {
            when(termsService.getCurrent()).thenReturn(Optional.empty());

            mvc.perform(get("/terms"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("termsVersion", TermsVersion.CURRENT))
                    .andExpect(model().attribute("termsBody", ""));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_can_view_public_terms() throws Exception {
            mvc.perform(get("/terms"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("legal/terms"));
        }
    }

    // ── GET /terms/accept ───────────────────────────────────────────────

    @Nested
    class AcceptForm {

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_sees_accept_form_with_clinic_and_current_terms() throws Exception {
            when(termsService.getCurrent()).thenReturn(Optional.of(currentDoc));

            mvc.perform(get("/terms/accept"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("legal/accept"))
                    .andExpect(model().attribute("clinic", clinic))
                    .andExpect(model().attribute("termsVersion", "2026-09-01"))
                    .andExpect(model().attribute("termsBody", "Be nice to patients."));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void accept_form_is_not_itself_gated_when_acceptance_required() throws Exception {
            // The interceptor must whitelist /terms/accept or owners would loop forever.
            when(termsService.requiresAcceptance(any())).thenReturn(true);
            when(termsService.getCurrent()).thenReturn(Optional.of(currentDoc));

            mvc.perform(get("/terms/accept"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("legal/accept"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_is_forbidden() throws Exception {
            mvc.perform(get("/terms/accept")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_is_forbidden() throws Exception {
            mvc.perform(get("/terms/accept")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_forbidden() throws Exception {
            // /terms/** is whitelisted by the scope interceptor, so the method guard decides.
            mvc.perform(get("/terms/accept")).andExpect(status().isForbidden());
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/terms/accept"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    // ── POST /terms/accept ──────────────────────────────────────────────

    @Nested
    class Accept {

        @Test
        @WithMockAppUser(role = "OWNER")
        void ticking_box_records_current_version_on_clinic() throws Exception {
            when(termsService.getCurrent()).thenReturn(Optional.of(currentDoc));

            mvc.perform(post("/terms/accept").with(csrf()).param("acceptTerms", "true"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"))
                    .andExpect(flash().attribute("flash", "Terms accepted. Thank you."));

            ArgumentCaptor<Clinic> captor = ArgumentCaptor.forClass(Clinic.class);
            verify(clinicRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(1L);
            assertThat(captor.getValue().getTermsVersion()).isEqualTo("2026-09-01");
            assertThat(captor.getValue().getTermsAcceptedAt()).isNotNull();
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void unticked_box_redirects_back_with_error_and_saves_nothing() throws Exception {
            mvc.perform(post("/terms/accept").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/terms/accept"))
                    .andExpect(flash().attributeExists("error"));

            verify(clinicRepository, never()).save(any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void no_published_terms_redirects_home_without_saving() throws Exception {
            when(termsService.getCurrent()).thenReturn(Optional.empty());

            mvc.perform(post("/terms/accept").with(csrf()).param("acceptTerms", "true"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));

            verify(clinicRepository, never()).save(any());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_accept_on_behalf_of_clinic() throws Exception {
            mvc.perform(post("/terms/accept").with(csrf()).param("acceptTerms", "true"))
                    .andExpect(status().isForbidden());

            verify(clinicRepository, never()).save(any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/terms/accept").param("acceptTerms", "true"))
                    .andExpect(status().isForbidden());

            verify(clinicRepository, never()).save(any());
        }
    }
}
