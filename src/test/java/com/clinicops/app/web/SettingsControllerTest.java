package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetails;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.ClinicProfileService;
import com.clinicops.app.service.ClinicProfileService.ProfileForm;
import com.clinicops.app.service.FileStorageService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.service.UserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

/**
 * Controller-layer tests for {@link SettingsController}: settings home, theme, own profile,
 * OWNER-only clinic profile + logo, and password change. Also proves the terms gate only
 * applies to OWNERs.
 */
@WebMvcTest(SettingsController.class)
@Import(SecurityConfig.class)
class SettingsControllerTest {

    @Autowired MockMvc mvc;

    @MockBean ClinicRepository clinicRepository;
    @MockBean AppUserRepository userRepository;
    @MockBean UserService userService;
    @MockBean ClinicProfileService clinicProfileService;
    @MockBean FileStorageService fileStorage;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;

    private Clinic clinic;
    private AppUser me;

    @BeforeEach
    void setUp() {
        // Delegate CurrentUser to whatever principal @WithMockAppUser installed.
        when(currentUser.get()).thenAnswer(inv ->
                (AppUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        when(currentUser.clinicId()).thenReturn(1L);
        when(currentUser.userId()).thenReturn(99L);

        clinic = Clinic.builder().id(1L).code("sunrise").name("Sunrise Clinic").phone("9876543210")
                .address("1 Main St").termsVersion("2026-09-01")
                .termsAcceptedAt(Instant.parse("2026-09-02T10:00:00Z")).build();
        me = AppUser.builder().id(99L).clinicId(1L).username("testuser").fullName("Test User")
                .role(Role.OWNER).phone("9876543210").enabled(true).build();
        when(userRepository.findById(99L)).thenReturn(Optional.of(me));
        when(clinicProfileService.getOrThrow(1L)).thenReturn(clinic);
    }

    // ── GET /settings ─────────────────────────────────────────────────

    @Nested
    class SettingsHome {

        @Test
        @WithMockAppUser(role = "OWNER")
        void shows_clinic_users_and_theme() throws Exception {
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(userRepository.findByClinicIdOrderByFullNameAsc(1L)).thenReturn(List.of(me));

            mvc.perform(get("/settings"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("settings/view"))
                    .andExpect(model().attribute("clinic", clinic))
                    .andExpect(model().attribute("users", List.of(me)))
                    .andExpect(model().attribute("currentTheme", "violet"))
                    .andExpect(content().string(containsString("Sunrise Clinic")));
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void any_clinic_role_can_open_settings() throws Exception {
            mvc.perform(get("/settings"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("clinic", (Object) null));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_redirected_to_platform() throws Exception {
            mvc.perform(get("/settings"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform"));
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/settings"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    // ── Terms acceptance gate ─────────────────────────────────────────

    @Nested
    class TermsGate {

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_requiring_acceptance_is_redirected() throws Exception {
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(termsService.requiresAcceptance(clinic)).thenReturn(true);

            mvc.perform(get("/settings"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/terms/accept"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void non_owner_is_not_gated_even_if_clinic_needs_acceptance() throws Exception {
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(termsService.requiresAcceptance(any())).thenReturn(true);

            mvc.perform(get("/settings")).andExpect(status().isOk());
        }
    }

    // ── POST /settings/theme ──────────────────────────────────────────

    @Nested
    class Theme {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void saves_theme_and_refreshes_principal() throws Exception {
            mvc.perform(post("/settings/theme").with(csrf()).param("theme", "sky"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/settings"))
                    .andExpect(flash().attribute("themeSaved", true));

            verify(userService).updateTheme(99L, "sky");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_theme_is_reported() throws Exception {
            doThrow(new IllegalArgumentException("Unknown theme: neon"))
                    .when(userService).updateTheme(99L, "neon");

            mvc.perform(post("/settings/theme").with(csrf()).param("theme", "neon"))
                    .andExpect(redirectedUrl("/settings"))
                    .andExpect(flash().attribute("themeError", "Unknown theme: neon"))
                    .andExpect(flash().attributeCount(1));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/settings/theme").param("theme", "sky"))
                    .andExpect(status().isForbidden());
            verify(userService, never()).updateTheme(anyLong(), anyString());
        }
    }

    // ── /settings/profile ─────────────────────────────────────────────

    @Nested
    class Profile {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void shows_own_profile() throws Exception {
            mvc.perform(get("/settings/profile"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("settings/profile"))
                    .andExpect(model().attribute("me", me));
        }

        @Test
        @WithMockAppUser(role = "OWNER", clinicId = 1L)
        void profile_of_user_in_other_clinic_is_404() throws Exception {
            me.setClinicId(2L);

            mvc.perform(get("/settings/profile"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void updates_own_profile() throws Exception {
            AppUser updated = AppUser.builder().id(99L).clinicId(1L).fullName("New Name").phone("9123456789").build();
            when(userService.updateOwnProfile(99L, "New Name", "9123456789")).thenReturn(updated);

            mvc.perform(post("/settings/profile").with(csrf())
                            .param("fullName", "New Name").param("phone", "9123456789"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/settings/profile"))
                    .andExpect(flash().attribute("success", "Profile updated."));

            verify(userService).updateOwnProfile(99L, "New Name", "9123456789");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_profile_rerenders_form_with_error() throws Exception {
            when(userService.updateOwnProfile(eq(99L), anyString(), any()))
                    .thenThrow(new IllegalArgumentException("Full name is required"));

            mvc.perform(post("/settings/profile").with(csrf()).param("fullName", " "))
                    .andExpect(status().isOk())
                    .andExpect(view().name("settings/profile"))
                    .andExpect(model().attribute("error", "Full name is required"))
                    .andExpect(model().attribute("me", me))
                    .andExpect(content().string(containsString("Full name is required")));
        }
    }

    // ── /settings/clinic ──────────────────────────────────────────────

    @Nested
    class ClinicProfile {

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_sees_clinic_profile_form() throws Exception {
            mvc.perform(get("/settings/clinic"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("settings/clinic"))
                    .andExpect(model().attribute("clinic", clinic));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_open_clinic_profile() throws Exception {
            mvc.perform(get("/settings/clinic")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_update_clinic_profile() throws Exception {
            mvc.perform(multipart("/settings/clinic").with(csrf()).param("name", "Hijacked"))
                    .andExpect(status().isForbidden());
            verify(clinicProfileService, never()).updateProfile(anyLong(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_remove_logo() throws Exception {
            mvc.perform(post("/settings/clinic/logo/remove").with(csrf()))
                    .andExpect(status().isForbidden());
            verify(clinicProfileService, never()).removeLogo(anyLong());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_updates_profile_with_logo() throws Exception {
            MockMultipartFile logo = new MockMultipartFile("logoFile", "logo.png", "image/png", new byte[] {1, 2, 3});

            mvc.perform(multipart("/settings/clinic").file(logo).with(csrf())
                            .param("name", "Sunrise Clinic")
                            .param("gstin", "22AAAAA0000A1Z5")
                            .param("tagline", "Care first"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/settings/clinic"))
                    .andExpect(flash().attribute("success", "Clinic profile updated."));

            ArgumentCaptor<ProfileForm> form = ArgumentCaptor.forClass(ProfileForm.class);
            ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
            verify(clinicProfileService).updateProfile(eq(1L), form.capture(), file.capture());
            assertThat(form.getValue().getName()).isEqualTo("Sunrise Clinic");
            assertThat(form.getValue().getGstin()).isEqualTo("22AAAAA0000A1Z5");
            assertThat(form.getValue().getTagline()).isEqualTo("Care first");
            assertThat(file.getValue().getOriginalFilename()).isEqualTo("logo.png");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_failure_is_reported_in_flash() throws Exception {
            when(clinicProfileService.updateProfile(anyLong(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Logo must be PNG or JPG"));

            mvc.perform(multipart("/settings/clinic").with(csrf()).param("name", "X"))
                    .andExpect(redirectedUrl("/settings/clinic"))
                    .andExpect(flash().attribute("error", "Logo must be PNG or JPG"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_removes_logo() throws Exception {
            mvc.perform(post("/settings/clinic/logo/remove").with(csrf()))
                    .andExpect(redirectedUrl("/settings/clinic"))
                    .andExpect(flash().attribute("success", "Logo removed."));
            verify(clinicProfileService).removeLogo(1L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_without_csrf_is_forbidden() throws Exception {
            mvc.perform(multipart("/settings/clinic").param("name", "X"))
                    .andExpect(status().isForbidden());
            verify(clinicProfileService, never()).updateProfile(anyLong(), any(), any());
        }
    }

    // ── GET /settings/clinic/logo ─────────────────────────────────────

    @Nested
    class Logo {

        @Test
        @WithMockAppUser(role = "OWNER")
        void returns_404_when_clinic_has_no_logo() throws Exception {
            mvc.perform(get("/settings/clinic/logo")).andExpect(status().isNotFound());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void streams_logo_file(@TempDir Path dir) throws Exception {
            Path file = Files.write(dir.resolve("logo.png"), new byte[] {9, 8, 7});
            clinic.setLogoPath("logos/1/logo.png");
            when(fileStorage.resolve("logos/1/logo.png")).thenReturn(file);

            mvc.perform(get("/settings/clinic/logo"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(content().bytes(new byte[] {9, 8, 7}));
        }
    }

    // ── /settings/password ────────────────────────────────────────────

    @Nested
    class Password {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void shows_password_form() throws Exception {
            mvc.perform(get("/settings/password"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("settings/password"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void changes_password() throws Exception {
            mvc.perform(post("/settings/password").with(csrf())
                            .param("currentPassword", "oldpass")
                            .param("newPassword", "newpass1")
                            .param("confirmPassword", "newpass1"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("settings/password"))
                    .andExpect(model().attribute("success", "Password changed."));
            verify(userService).changePassword(99L, "oldpass", "newpass1");
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void mismatched_confirmation_is_rejected_without_calling_service() throws Exception {
            mvc.perform(post("/settings/password").with(csrf())
                            .param("currentPassword", "oldpass")
                            .param("newPassword", "newpass1")
                            .param("confirmPassword", "newpass2"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("error", "New password and confirmation do not match"))
                    .andExpect(content().string(containsString("New password and confirmation do not match")));
            verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void wrong_current_password_shows_error() throws Exception {
            doThrow(new IllegalArgumentException("Current password is incorrect"))
                    .when(userService).changePassword(99L, "bad", "newpass1");

            mvc.perform(post("/settings/password").with(csrf())
                            .param("currentPassword", "bad")
                            .param("newPassword", "newpass1")
                            .param("confirmPassword", "newpass1"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("error", "Current password is incorrect"))
                    .andExpect(model().attributeDoesNotExist("success"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/settings/password")
                            .param("currentPassword", "a").param("newPassword", "b").param("confirmPassword", "b"))
                    .andExpect(status().isForbidden());
            verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
        }
    }
}
