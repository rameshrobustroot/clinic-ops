package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.service.UserService;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.clinicops.app.security.AppUserDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Controller-layer tests for {@link UserController} — OWNER-only staff management.
 * Also exercises the PlatformAdminScopeInterceptor / TermsAcceptanceInterceptor gates.
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired MockMvc mvc;

    @MockBean UserService userService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
    }

    /** Per-invocation principal for parameterized role tests (same shape as {@link WithMockAppUser}). */
    private static RequestPostProcessor as(String role) {
        AppUserDetails principal = new AppUserDetails(AppUser.builder()
                .id(99L).clinicId(1L).username("testuser").fullName("Test User")
                .role(Role.valueOf(role)).enabled(true).passwordHash("n/a").build());
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static MockHttpServletRequestBuilder createUser(String role) {
        return post("/users").with(csrf())
                .param("username", "ravi")
                .param("fullName", "Ravi Kumar")
                .param("role", role)
                .param("phone", "9876543210")
                .param("password", "secret1");
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @ParameterizedTest
        @ValueSource(strings = {"DOCTOR", "PHARMACIST", "RECEPTIONIST"})
        void non_owner_staff_cannot_list_users(String role) throws Exception {
            mvc.perform(get("/users").with(as(role)))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(userService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"DOCTOR", "PHARMACIST", "RECEPTIONIST"})
        void non_owner_staff_cannot_create_users(String role) throws Exception {
            mvc.perform(createUser("OWNER").with(as(role)))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(userService);
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_disable_users() throws Exception {
            mvc.perform(post("/users/5/toggle").with(csrf()).param("enabled", "false"))
                    .andExpect(status().isForbidden());
            verify(userService, never()).setEnabled(anyLong(), anyBoolean());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_reset_passwords() throws Exception {
            mvc.perform(post("/users/5/reset-password").with(csrf()).param("newPassword", "hacked1"))
                    .andExpect(status().isForbidden());
            verify(userService, never()).resetPassword(anyLong(), anyLong(), anyString());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_open_new_user_form() throws Exception {
            mvc.perform(get("/users/new")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_confined_to_platform_namespace() throws Exception {
            mvc.perform(get("/users"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform"));
            verifyNoInteractions(userService);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_cannot_post_to_clinic_endpoints() throws Exception {
            mvc.perform(createUser("OWNER"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform"));
            verifyNoInteractions(userService);
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/users"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/users").param("username", "x").param("fullName", "X")
                            .param("role", "DOCTOR").param("password", "secret1"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(userService);
        }
    }

    // ── Terms acceptance gate ─────────────────────────────────────────

    @Nested
    class TermsGate {

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_on_outdated_terms_is_redirected_to_accept_page() throws Exception {
            Clinic clinic = Clinic.builder().id(1L).termsVersion("old").build();
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(termsService.requiresAcceptance(clinic)).thenReturn(true);

            mvc.perform(get("/users"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/terms/accept"));
            verifyNoInteractions(userService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_on_current_terms_passes_through() throws Exception {
            Clinic clinic = Clinic.builder().id(1L).termsVersion("current").build();
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(termsService.requiresAcceptance(clinic)).thenReturn(false);

            mvc.perform(get("/users")).andExpect(status().isOk());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void gate_also_blocks_mutations() throws Exception {
            Clinic clinic = Clinic.builder().id(1L).build();
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(termsService.requiresAcceptance(clinic)).thenReturn(true);

            mvc.perform(createUser("DOCTOR"))
                    .andExpect(redirectedUrl("/terms/accept"));
            verifyNoInteractions(userService);
        }
    }

    // ── GET /users ────────────────────────────────────────────────────

    @Nested
    class ListUsers {

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_sees_clinic_users() throws Exception {
            List<AppUser> users = List.of(
                    AppUser.builder().id(1L).clinicId(1L).username("owner").fullName("Olivia Owner")
                            .role(Role.OWNER).enabled(true).build(),
                    AppUser.builder().id(2L).clinicId(1L).username("rec").fullName("Rita Reception")
                            .role(Role.RECEPTIONIST).enabled(false).build());
            when(userService.listForClinic(1L)).thenReturn(users);

            mvc.perform(get("/users"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("user/list"))
                    .andExpect(model().attribute("users", users))
                    .andExpect(content().string(containsString("Rita Reception")));
            verify(userService).listForClinic(1L);
        }
    }

    // ── GET /users/new ────────────────────────────────────────────────

    @Nested
    class NewForm {

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_sees_form_with_roles() throws Exception {
            mvc.perform(get("/users/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("user/form"))
                    .andExpect(model().attributeExists("roles"))
                    .andExpect(content().string(containsString("RECEPTIONIST")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void form_does_not_offer_platform_admin_role() throws Exception {
            mvc.perform(get("/users/new"))
                    .andExpect(status().isOk())
                    // The shared layout mentions PLATFORM_ADMIN in an HTML comment, so assert on the
                    // <option> value and the model rather than the raw text.
                    .andExpect(model().attribute("roles", not(hasItem(Role.PLATFORM_ADMIN))))
                    .andExpect(content().string(not(containsString("value=\"PLATFORM_ADMIN\""))));
        }
    }

    // ── POST /users ───────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "OWNER")
        void creates_user_in_owner_clinic() throws Exception {
            mvc.perform(createUser("PHARMACIST"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/users"));

            verify(userService).create(1L, "ravi", "Ravi Kumar", Role.PHARMACIST, "9876543210", "secret1");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void phone_is_optional() throws Exception {
            mvc.perform(post("/users").with(csrf())
                            .param("username", "ravi").param("fullName", "Ravi")
                            .param("role", "DOCTOR").param("password", "secret1"))
                    .andExpect(redirectedUrl("/users"));

            verify(userService).create(eq(1L), eq("ravi"), eq("Ravi"), eq(Role.DOCTOR), isNull(), eq("secret1"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void service_validation_error_rerenders_form() throws Exception {
            when(userService.create(anyLong(), anyString(), anyString(), any(), any(), anyString()))
                    .thenThrow(new IllegalArgumentException("Username already taken: ravi"));

            mvc.perform(createUser("DOCTOR"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("user/form"))
                    .andExpect(model().attribute("error", "Username already taken: ravi"))
                    .andExpect(model().attributeExists("roles"))
                    .andExpect(content().string(containsString("Username already taken: ravi")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void unknown_role_is_rejected_with_400() throws Exception {
            mvc.perform(createUser("SUPERUSER")).andExpect(status().isBadRequest());
            verifyNoInteractions(userService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void missing_required_param_is_rejected_with_400() throws Exception {
            mvc.perform(post("/users").with(csrf()).param("username", "ravi"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(userService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_cannot_create_platform_admin() throws Exception {
            mvc.perform(createUser("PLATFORM_ADMIN"));
            verify(userService, never()).create(anyLong(), anyString(), anyString(),
                    eq(Role.PLATFORM_ADMIN), any(), anyString());
        }
    }

    // ── POST /users/{id}/toggle ───────────────────────────────────────

    @Nested
    class Toggle {

        @Test
        @WithMockAppUser(role = "OWNER")
        void disables_user() throws Exception {
            mvc.perform(post("/users/5/toggle").with(csrf()).param("enabled", "false"))
                    .andExpect(redirectedUrl("/users"));
            verify(userService).setEnabled(5L, false);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void enables_user() throws Exception {
            mvc.perform(post("/users/5/toggle").with(csrf()).param("enabled", "true"))
                    .andExpect(redirectedUrl("/users"));
            verify(userService).setEnabled(5L, true);
        }
    }

    // ── POST /users/{id}/reset-password ───────────────────────────────

    @Nested
    class ResetPassword {

        @Test
        @WithMockAppUser(role = "OWNER")
        void resets_password_scoped_to_owner_clinic() throws Exception {
            mvc.perform(post("/users/5/reset-password").with(csrf()).param("newPassword", "newpass1"))
                    .andExpect(redirectedUrl("/users"))
                    .andExpect(flash().attribute("flash", "Password reset."));
            verify(userService).resetPassword(1L, 5L, "newpass1");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void service_rejection_is_reported_in_flash() throws Exception {
            doThrow(new IllegalArgumentException("Password too short"))
                    .when(userService).resetPassword(1L, 5L, "x");

            mvc.perform(post("/users/5/reset-password").with(csrf()).param("newPassword", "x"))
                    .andExpect(redirectedUrl("/users"))
                    .andExpect(flash().attribute("flash", "Couldn't reset: Password too short"));
        }
    }
}
