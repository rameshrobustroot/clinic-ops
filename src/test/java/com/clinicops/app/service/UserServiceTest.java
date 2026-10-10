package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.integration.BaseIntegrationTest;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTest extends BaseIntegrationTest {

    @Autowired UserService userService;
    @Autowired AppUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    Long otherClinicId;

    @BeforeEach
    void setUpOtherClinic() {
        otherClinicId = clinicRepository.save(Clinic.builder()
                .code("usr-other-" + System.nanoTime()).name("Other").build()).getId();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Suffix usernames per test so fixtures never collide within the shared test clinic. */
    private String uname(String base) {
        return base + System.nanoTime();
    }

    private AppUser user(Long clinic, String name, Role role, boolean enabled) {
        return userRepository.save(AppUser.builder()
                .clinicId(clinic).username(uname("u")).fullName(name).role(role)
                .passwordHash(passwordEncoder.encode("secret1")).enabled(enabled).build());
    }

    @Nested
    class Create {

        @Test
        void normalizes_username_trims_name_and_encodes_password() {
            String raw = "  Dr.RAMESH" + System.nanoTime() + "  ";
            AppUser u = userService.create(clinicId, raw, "  Ramesh R  ", Role.DOCTOR, "9876543210", "secret1");

            assertThat(u.getId()).isNotNull();
            assertThat(u.getUsername()).isEqualTo(raw.trim().toLowerCase());
            assertThat(u.getFullName()).isEqualTo("Ramesh R");
            assertThat(u.getClinicId()).isEqualTo(clinicId);
            assertThat(u.getRole()).isEqualTo(Role.DOCTOR);
            assertThat(u.getPhone()).isEqualTo("9876543210");
            assertThat(u.getEnabled()).isTrue();
            assertThat(u.getTheme()).isEqualTo("violet");
            assertThat(u.getPasswordHash()).isNotEqualTo("secret1");
            assertThat(passwordEncoder.matches("secret1", u.getPasswordHash())).isTrue();
        }

        @Test
        void rejects_missing_username() {
            assertThatThrownBy(() -> userService.create(clinicId, null, "N", Role.DOCTOR, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Username is required");
            assertThatThrownBy(() -> userService.create(clinicId, "   ", "N", Role.DOCTOR, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Username is required");
        }

        @Test
        void rejects_missing_full_name() {
            assertThatThrownBy(() -> userService.create(clinicId, uname("a"), " ", Role.DOCTOR, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Full name is required");
            assertThatThrownBy(() -> userService.create(clinicId, uname("a"), null, Role.DOCTOR, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Full name is required");
        }

        @Test
        void rejects_missing_role() {
            assertThatThrownBy(() -> userService.create(clinicId, uname("a"), "N", null, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Role is required");
        }

        @Test
        void rejects_platform_admin_role() {
            // Clinics must never be able to mint a platform-console account.
            assertThatThrownBy(() -> userService.create(clinicId, uname("pa"), "N", Role.PLATFORM_ADMIN, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be assigned");
        }

        @Test
        void rejects_duplicate_username_case_and_whitespace_insensitively() {
            String name = uname("alice");
            userService.create(clinicId, name, "Alice", Role.PHARMACIST, null, "secret1");

            assertThatThrownBy(() ->
                    userService.create(clinicId, "  " + name.toUpperCase() + " ", "Alice 2", Role.DOCTOR, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Username already taken: " + name);
        }

        @Test
        void rejects_duplicate_username_within_same_clinic_under_tenant_context() {
            String name = uname("bob");
            userService.create(clinicId, name, "Bob", Role.DOCTOR, null, "secret1");

            TenantContext.set(clinicId);
            assertThatThrownBy(() -> userService.create(clinicId, name, "Bob 2", Role.DOCTOR, null, "secret1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Username already taken");
        }

        @Test
        void same_username_is_allowed_in_a_different_clinic() {
            // Matches production (Liquibase 008: UNIQUE(clinic_id, username)); login is code/username.
            Long otherClinic = clinicRepository.save(com.clinicops.app.domain.Clinic.builder()
                    .code("usr-o-" + System.nanoTime()).name("Other").build()).getId();
            String name = uname("carol");
            userService.create(otherClinic, name, "Carol elsewhere", Role.DOCTOR, null, "secret1");

            AppUser mine = userService.create(clinicId, name, "Carol here", Role.DOCTOR, null, "secret1");

            assertThat(mine.getClinicId()).isEqualTo(clinicId);
            assertThat(mine.getUsername()).isEqualTo(name);
        }

        @Test
        void password_must_be_at_least_six_characters() {
            assertThatThrownBy(() -> userService.create(clinicId, uname("a"), "N", Role.DOCTOR, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Password must be at least 6 characters");
            assertThatThrownBy(() -> userService.create(clinicId, uname("a"), "N", Role.DOCTOR, null, "12345"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Password must be at least 6 characters");
            // exactly 6 is the boundary and is accepted
            assertThatCode(() -> userService.create(clinicId, uname("a"), "N", Role.DOCTOR, null, "123456"))
                    .doesNotThrowAnyException();
        }

        @Test
        void phone_is_optional() {
            assertThat(userService.create(clinicId, uname("a"), "N", Role.DOCTOR, null, "secret1").getPhone())
                    .isNull();
            assertThatCode(() -> userService.create(clinicId, uname("b"), "N", Role.DOCTOR, "  ", "secret1"))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejects_phone_not_matching_indian_mobile_pattern() {
            // too short / starts with 5 / 11 digits / country code / letters
            for (String bad : new String[]{"12345", "5876543210", "98765432101", "+919876543210", "98765abcde"}) {
                assertThatThrownBy(() -> userService.create(clinicId, uname("p"), "N", Role.DOCTOR, bad, "secret1"))
                        .as("phone=%s", bad)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("10-digit mobile");
            }
        }

        @Test
        void accepts_phones_starting_with_6_to_9() {
            for (String ok : new String[]{"6000000000", "7000000000", "8000000000", "9999999999"}) {
                assertThatCode(() -> userService.create(clinicId, uname("p"), "N", Role.DOCTOR, ok, "secret1"))
                        .as("phone=%s", ok).doesNotThrowAnyException();
            }
        }
    }

    @Nested
    class Listing {

        @Test
        void listDoctors_keeps_only_enabled_owners_and_doctors_sorted_by_name() {
            user(clinicId, "Zara Owner", Role.OWNER, true);
            user(clinicId, "Arun Doctor", Role.DOCTOR, true);
            user(clinicId, "Disabled Doctor", Role.DOCTOR, false);
            user(clinicId, "Priya Pharmacist", Role.PHARMACIST, true);
            user(clinicId, "Ravi Reception", Role.RECEPTIONIST, true);
            user(otherClinicId, "Foreign Doctor", Role.DOCTOR, true);

            // "Integration User" is the OWNER that BaseIntegrationTest creates in every test clinic.
            assertThat(userService.listDoctors(clinicId))
                    .extracting(AppUser::getFullName)
                    .containsExactly("Arun Doctor", "Integration User", "Zara Owner");
        }

        @Test
        void listForClinic_returns_all_clinic_users_including_disabled() {
            user(clinicId, "B", Role.PHARMACIST, false);
            user(clinicId, "A", Role.DOCTOR, true);
            user(otherClinicId, "C", Role.DOCTOR, true);

            // plus BaseIntegrationTest's fixture OWNER ("Integration User")
            assertThat(userService.listForClinic(clinicId))
                    .extracting(AppUser::getFullName).containsExactly("A", "B", "Integration User");
        }
    }

    @Nested
    class UpdateOwnProfile {

        @Test
        void trims_name_and_phone() {
            AppUser u = user(clinicId, "Old", Role.DOCTOR, true);
            AppUser saved = userService.updateOwnProfile(u.getId(), "  New Name ", "9876543210");
            assertThat(saved.getFullName()).isEqualTo("New Name");
            assertThat(saved.getPhone()).isEqualTo("9876543210");
        }

        @Test
        void blank_phone_is_stored_as_null() {
            AppUser u = user(clinicId, "Old", Role.DOCTOR, true);
            u.setPhone("9876543210");
            assertThat(userService.updateOwnProfile(u.getId(), "Name", "  ").getPhone()).isNull();
            assertThat(userService.updateOwnProfile(u.getId(), "Name", null).getPhone()).isNull();
        }

        @Test
        void rejects_blank_name_and_invalid_phone() {
            AppUser u = user(clinicId, "Old", Role.DOCTOR, true);
            assertThatThrownBy(() -> userService.updateOwnProfile(u.getId(), " ", null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Full name is required");
            assertThatThrownBy(() -> userService.updateOwnProfile(u.getId(), "Name", "12345"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10-digit");
        }

        @Test
        void unknown_user_is_rejected() {
            assertThatThrownBy(() -> userService.updateOwnProfile(-1L, "Name", null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("User not found: -1");
        }

        @Test
        void cross_tenant_user_is_rejected() {
            AppUser u = user(otherClinicId, "Foreign", Role.DOCTOR, true);
            TenantContext.set(clinicId);
            assertThatThrownBy(() -> userService.updateOwnProfile(u.getId(), "Name", null))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    @Nested
    class Theme {

        @Test
        void accepts_each_valid_theme() {
            AppUser u = user(clinicId, "T", Role.DOCTOR, true);
            for (String theme : UserService.VALID_THEMES) {
                userService.updateTheme(u.getId(), theme);
                assertThat(userRepository.findById(u.getId()).orElseThrow().getTheme()).isEqualTo(theme);
            }
        }

        @Test
        void valid_themes_are_violet_sky_indigo() {
            assertThat(UserService.VALID_THEMES).containsExactlyInAnyOrder("violet", "sky", "indigo");
        }

        @Test
        void rejects_unknown_or_null_theme_before_loading_user() {
            // validation happens first, so even a non-existent user id yields the theme error
            assertThatThrownBy(() -> userService.updateTheme(-1L, "emerald"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown theme: emerald");
            assertThatThrownBy(() -> userService.updateTheme(-1L, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown theme: null");
            assertThatThrownBy(() -> userService.updateTheme(-1L, "VIOLET"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void cross_tenant_user_is_rejected() {
            AppUser u = user(otherClinicId, "Foreign", Role.DOCTOR, true);
            TenantContext.set(clinicId);
            assertThatThrownBy(() -> userService.updateTheme(u.getId(), "sky"))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    @Nested
    class SetEnabled {

        @Test
        void toggles_enabled_flag() {
            AppUser u = user(clinicId, "E", Role.DOCTOR, true);
            userService.setEnabled(u.getId(), false);
            assertThat(userRepository.findById(u.getId()).orElseThrow().getEnabled()).isFalse();
            userService.setEnabled(u.getId(), true);
            assertThat(userRepository.findById(u.getId()).orElseThrow().getEnabled()).isTrue();
        }

        @Test
        void unknown_user_is_rejected() {
            assertThatThrownBy(() -> userService.setEnabled(-1L, true))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("User not found: -1");
        }

        @Test
        void cross_tenant_user_is_rejected() {
            AppUser u = user(otherClinicId, "Foreign", Role.DOCTOR, true);
            TenantContext.set(clinicId);
            assertThatThrownBy(() -> userService.setEnabled(u.getId(), false))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    @Nested
    class ChangePassword {

        @Test
        void wrong_current_password_is_rejected() {
            AppUser u = user(clinicId, "C", Role.DOCTOR, true);
            assertThatThrownBy(() -> userService.changePassword(u.getId(), "wrong!!", "newsecret"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Current password is incorrect");
        }

        @Test
        void short_or_null_new_password_is_rejected() {
            AppUser u = user(clinicId, "C", Role.DOCTOR, true);
            assertThatThrownBy(() -> userService.changePassword(u.getId(), "secret1", "12345"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("New password must be at least 6 characters");
            assertThatThrownBy(() -> userService.changePassword(u.getId(), "secret1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("New password must be at least 6 characters");
        }

        @Test
        void success_re_encodes_new_password() {
            AppUser u = user(clinicId, "C", Role.DOCTOR, true);
            userService.changePassword(u.getId(), "secret1", "brandnew");

            String hash = userRepository.findById(u.getId()).orElseThrow().getPasswordHash();
            assertThat(hash).isNotEqualTo("brandnew");
            assertThat(passwordEncoder.matches("brandnew", hash)).isTrue();
            assertThat(passwordEncoder.matches("secret1", hash)).isFalse();
        }

        @Test
        void unknown_user_is_rejected() {
            assertThatThrownBy(() -> userService.changePassword(-1L, "a", "bbbbbb"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("User not found: -1");
        }
    }

    @Nested
    class ResetPassword {

        @Test
        void owner_can_reset_password_of_user_in_same_clinic() {
            AppUser u = user(clinicId, "R", Role.PHARMACIST, true);
            userService.resetPassword(clinicId, u.getId(), "resetpw");
            assertThat(passwordEncoder.matches("resetpw",
                    userRepository.findById(u.getId()).orElseThrow().getPasswordHash())).isTrue();
        }

        @Test
        void cross_clinic_reset_is_rejected_and_password_unchanged() {
            AppUser u = user(otherClinicId, "Foreign", Role.DOCTOR, true);
            assertThatThrownBy(() -> userService.resetPassword(clinicId, u.getId(), "resetpw"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cannot reset a user from a different clinic");
            assertThat(passwordEncoder.matches("secret1",
                    userRepository.findById(u.getId()).orElseThrow().getPasswordHash())).isTrue();
        }

        @Test
        void short_password_is_rejected_before_lookup() {
            assertThatThrownBy(() -> userService.resetPassword(clinicId, -1L, "123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("New password must be at least 6 characters");
        }

        @Test
        void unknown_user_is_rejected() {
            assertThatThrownBy(() -> userService.resetPassword(clinicId, -1L, "resetpw"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("User not found: -1");
        }
    }
}
