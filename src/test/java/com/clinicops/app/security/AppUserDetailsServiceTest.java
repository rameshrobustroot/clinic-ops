package com.clinicops.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login resolution: {@code "code/username"} for clinic users, bare {@code "username"} for
 * PLATFORM_ADMIN only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AppUserDetailsServiceTest {

    @Autowired AppUserDetailsService service;
    @Autowired AppUserRepository userRepository;
    @Autowired ClinicRepository clinicRepository;

    Clinic clinicA;
    Clinic clinicB;
    AppUser ownerA;
    AppUser doctorB;
    AppUser platformAdmin;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        clinicA = clinicRepository.save(Clinic.builder().code("logina").name("Login A").build());
        clinicB = clinicRepository.save(Clinic.builder().code("loginb").name("Login B").build());
        ownerA = userRepository.save(AppUser.builder().clinicId(clinicA.getId())
                .username("owner-a").passwordHash("h").fullName("Owner A")
                .role(Role.OWNER).enabled(true).build());
        doctorB = userRepository.save(AppUser.builder().clinicId(clinicB.getId())
                .username("doc-b").passwordHash("h").fullName("Doc B")
                .role(Role.DOCTOR).enabled(true).build());
        platformAdmin = userRepository.save(AppUser.builder().clinicId(null)
                .username("pa-login").passwordHash("h").fullName("PA")
                .role(Role.PLATFORM_ADMIN).enabled(true).build());
    }

    private Long idOf(UserDetails d) {
        return ((AppUserDetails) d).getUserId();
    }

    @Nested
    class ClinicLogin {

        @Test
        void code_slash_username_resolves_user_in_that_clinic() {
            UserDetails d = service.loadUserByUsername("logina/owner-a");

            assertThat(d).isInstanceOf(AppUserDetails.class);
            assertThat(idOf(d)).isEqualTo(ownerA.getId());
            assertThat(d.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OWNER");
        }

        @Test
        void code_and_username_are_trimmed_and_lowercased() {
            assertThat(idOf(service.loadUserByUsername("  LoginA / OWNER-A  "))).isEqualTo(ownerA.getId());
        }

        @Test
        void user_from_another_clinic_code_is_rejected() {
            assertThatThrownBy(() -> service.loadUserByUsername("logina/doc-b"))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessage("Invalid credentials");
        }

        @Test
        void unknown_clinic_code_is_rejected() {
            assertThatThrownBy(() -> service.loadUserByUsername("nope/owner-a"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        void platform_admin_cannot_log_in_with_a_clinic_prefix() {
            assertThatThrownBy(() -> service.loadUserByUsername("logina/pa-login"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        void only_first_slash_splits_code_from_username() {
            assertThatThrownBy(() -> service.loadUserByUsername("logina/owner-a/extra"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }
    }

    @Nested
    class BareUsername {

        @Test
        void platform_admin_logs_in_without_clinic_code() {
            UserDetails d = service.loadUserByUsername("  PA-Login ");

            assertThat(idOf(d)).isEqualTo(platformAdmin.getId());
            assertThat(d.getAuthorities()).extracting(Object::toString)
                    .containsExactly("ROLE_PLATFORM_ADMIN");
        }

        @Test
        void clinic_user_sharing_the_platform_admin_username_does_not_break_platform_login() {
            // Usernames are unique per clinic only, so a clinic can legitimately create "pa-login".
            AppUser lookalike = userRepository.save(AppUser.builder().clinicId(clinicA.getId())
                    .username("pa-login").passwordHash("h").fullName("Clinic PA lookalike")
                    .role(Role.RECEPTIONIST).enabled(true).build());

            assertThat(idOf(service.loadUserByUsername("pa-login"))).isEqualTo(platformAdmin.getId());
            assertThat(idOf(service.loadUserByUsername("logina/pa-login"))).isEqualTo(lookalike.getId());
        }

        @Test
        void same_username_in_two_clinics_resolves_by_clinic_code() {
            AppUser inB = userRepository.save(AppUser.builder().clinicId(clinicB.getId())
                    .username("owner-a").passwordHash("h").fullName("Namesake in B")
                    .role(Role.OWNER).enabled(true).build());

            assertThat(idOf(service.loadUserByUsername("logina/owner-a"))).isEqualTo(ownerA.getId());
            assertThat(idOf(service.loadUserByUsername("loginb/owner-a"))).isEqualTo(inB.getId());
        }

        @Test
        void clinic_user_without_clinic_code_is_rejected() {
            assertThatThrownBy(() -> service.loadUserByUsername("owner-a"))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessage("Invalid credentials");
        }

        @Test
        void leading_slash_is_treated_as_bare_username_and_rejected_for_clinic_user() {
            assertThatThrownBy(() -> service.loadUserByUsername("/owner-a"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        void trailing_slash_is_treated_as_bare_username() {
            assertThatThrownBy(() -> service.loadUserByUsername("logina/"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        void null_or_unknown_username_is_rejected_without_npe() {
            assertThatThrownBy(() -> service.loadUserByUsername(null))
                    .isInstanceOf(UsernameNotFoundException.class);
            assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }
    }
}
