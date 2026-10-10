package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.TermsService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link AdminTermsController} — PLATFORM_ADMIN-only terms CRUD. */
@WebMvcTest(AdminTermsController.class)
@Import(SecurityConfig.class)
class AdminTermsControllerTest {

    @Autowired MockMvc mvc;

    @MockBean TermsService termsService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean ClinicRepository clinicRepository;

    private TermsDocument v1;
    private TermsDocument v2;

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn(99L);
        v1 = TermsDocument.builder().id(1L).version("2025-01-01").body("Old terms")
                .effectiveDate(LocalDate.of(2025, 1, 1)).publishedAt(Instant.parse("2025-01-01T00:00:00Z")).build();
        v2 = TermsDocument.builder().id(2L).version("2026-09-01").body("New terms")
                .effectiveDate(LocalDate.of(2026, 9, 1)).publishedAt(Instant.parse("2026-09-01T00:00:00Z")).build();
        when(termsService.listAll()).thenReturn(List.of(v2, v1));
        when(termsService.getCurrent()).thenReturn(Optional.of(v2));
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_is_forbidden_from_listing() throws Exception {
            mvc.perform(get("/platform/terms")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_is_forbidden_from_new_form() throws Exception {
            mvc.perform(get("/platform/terms/new")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_cannot_publish_terms() throws Exception {
            mvc.perform(post("/platform/terms").with(csrf())
                            .param("version", "evil").param("body", "x").param("effectiveDate", "2026-10-01"))
                    .andExpect(status().isForbidden());

            verify(termsService, never()).publish(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_view_version() throws Exception {
            mvc.perform(get("/platform/terms/1")).andExpect(status().isForbidden());
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/platform/terms"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    // ── GET /platform/terms ───────────────────────────────────────────

    @Nested
    class ListVersions {

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void lists_all_versions_and_current() throws Exception {
            mvc.perform(get("/platform/terms"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/terms/list"))
                    .andExpect(model().attribute("versions", List.of(v2, v1)))
                    .andExpect(model().attribute("current", v2))
                    .andExpect(model().attribute("today", LocalDate.now().toString()))
                    .andExpect(content().string(containsString("2026-09-01")));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void renders_when_nothing_published_yet() throws Exception {
            when(termsService.listAll()).thenReturn(List.of());
            when(termsService.getCurrent()).thenReturn(Optional.empty());

            mvc.perform(get("/platform/terms"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("current", (Object) null));
        }
    }

    // ── GET /platform/terms/new ───────────────────────────────────────

    @Nested
    class NewForm {

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void prefills_body_from_latest_version() throws Exception {
            mvc.perform(get("/platform/terms/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/terms/new"))
                    .andExpect(model().attribute("latestBody", "New terms"))
                    .andExpect(model().attribute("today", LocalDate.now().toString()));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void empty_body_when_nothing_published() throws Exception {
            when(termsService.getCurrent()).thenReturn(Optional.empty());

            mvc.perform(get("/platform/terms/new"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("latestBody", ""));
        }
    }

    // ── POST /platform/terms ──────────────────────────────────────────

    @Nested
    class Publish {

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void publishes_with_parsed_date_and_acting_user() throws Exception {
            mvc.perform(post("/platform/terms").with(csrf())
                            .param("version", "2026-10-01")
                            .param("body", "Fresh terms")
                            .param("effectiveDate", "2026-10-01"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform/terms"))
                    .andExpect(flash().attribute("flash", containsString("Published 2026-10-01")));

            verify(termsService).publish("2026-10-01", "Fresh terms", LocalDate.of(2026, 10, 1), 99L);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void service_rejection_redirects_back_to_form_preserving_input() throws Exception {
            when(termsService.publish(eq("dup"), anyString(), any(), anyLong()))
                    .thenThrow(new IllegalArgumentException("Version already exists"));

            mvc.perform(post("/platform/terms").with(csrf())
                            .param("version", "dup")
                            .param("body", "Body text")
                            .param("effectiveDate", "2026-10-01"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform/terms/new"))
                    .andExpect(flash().attribute("error", "Version already exists"))
                    .andExpect(flash().attribute("formVersion", "dup"))
                    .andExpect(flash().attribute("formBody", "Body text"))
                    .andExpect(flash().attribute("formEffectiveDate", "2026-10-01"));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void malformed_date_redirects_back_without_publishing() throws Exception {
            mvc.perform(post("/platform/terms").with(csrf())
                            .param("version", "v9")
                            .param("body", "Body text")
                            .param("effectiveDate", "01/10/2026"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform/terms/new"))
                    .andExpect(flash().attributeExists("error"))
                    .andExpect(flash().attribute("formEffectiveDate", "01/10/2026"));

            verify(termsService, never()).publish(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/platform/terms")
                            .param("version", "v9").param("body", "b").param("effectiveDate", "2026-10-01"))
                    .andExpect(status().isForbidden());

            verify(termsService, never()).publish(anyString(), anyString(), any(), anyLong());
        }
    }

    // ── GET /platform/terms/{id} ──────────────────────────────────────

    @Nested
    class ViewVersion {

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void shows_requested_version() throws Exception {
            mvc.perform(get("/platform/terms/1"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/terms/view"))
                    .andExpect(model().attribute("doc", v1))
                    .andExpect(content().string(containsString("Old terms")));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void unknown_version_is_404() throws Exception {
            mvc.perform(get("/platform/terms/404"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }
}
