package com.clinicops.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Bootstrap} is excluded from the test profile ({@code @Profile("!test")}), so it is
 * constructed by hand against the real repositories. The key contract is idempotency:
 * every restart runs it again and it must never duplicate users or clinics.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BootstrapTest {

    @Autowired AppUserRepository userRepository;
    @Autowired ClinicRepository clinicRepository;
    @Autowired PasswordEncoder passwordEncoder;

    MockEnvironment env;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        env = new MockEnvironment();
    }

    private Bootstrap bootstrap() {
        return new Bootstrap(userRepository, clinicRepository, passwordEncoder, env);
    }

    private java.util.Optional<AppUser> platformAdmin(String username) {
        return userRepository.findByRoleAndUsername(Role.PLATFORM_ADMIN, username);
    }

    @Test
    void creates_platform_admin_with_null_clinic_and_encoded_password() {
        bootstrap().run();

        AppUser pa = platformAdmin("superadmin").orElseThrow();
        assertThat(pa.getRole()).isEqualTo(Role.PLATFORM_ADMIN);
        assertThat(pa.getClinicId()).isNull();
        assertThat(pa.getEnabled()).isTrue();
        assertThat(pa.getPasswordHash()).isNotEqualTo("superadmin");
        assertThat(passwordEncoder.matches("superadmin", pa.getPasswordHash())).isTrue();
    }

    @Test
    void creates_second_verification_tenant_with_owner() {
        bootstrap().run();

        Long demo2 = clinicRepository.findByCode("demo2").orElseThrow().getId();
        AppUser admin2 = userRepository.findByClinicIdAndUsername(demo2, "admin2").orElseThrow();
        assertThat(admin2.getRole()).isEqualTo(Role.OWNER);
        assertThat(clinicRepository.findById(admin2.getClinicId()).orElseThrow().getCode())
                .isEqualTo("demo2");
    }

    @Test
    void creates_default_owner_only_when_no_users_exist() {
        boolean emptyBefore = userRepository.count() == 0;

        bootstrap().run();

        assertThat(userRepository.findByClinicIdAndUsername(1L, "admin").isPresent()).isEqualTo(emptyBefore);
    }

    @Test
    void running_twice_does_not_duplicate_users_or_clinics() {
        bootstrap().run();
        long users = userRepository.count();
        long clinics = clinicRepository.count();

        bootstrap().run();

        assertThat(userRepository.count()).isEqualTo(users);
        assertThat(clinicRepository.count()).isEqualTo(clinics);
    }

    @Test
    void platform_admin_credentials_can_be_overridden_by_environment() {
        env.setProperty("PLATFORM_ADMIN_USERNAME", "ops-root");
        env.setProperty("PLATFORM_ADMIN_PASSWORD", "S3cret!pw");

        bootstrap().run();

        AppUser pa = platformAdmin("ops-root").orElseThrow();
        assertThat(pa.getRole()).isEqualTo(Role.PLATFORM_ADMIN);
        assertThat(passwordEncoder.matches("S3cret!pw", pa.getPasswordHash())).isTrue();
        assertThat(platformAdmin("superadmin")).isEmpty();
    }

    @Test
    void existing_platform_admin_password_is_not_reset_on_restart() {
        bootstrap().run();
        String originalHash = platformAdmin("superadmin").orElseThrow().getPasswordHash();

        env.setProperty("PLATFORM_ADMIN_PASSWORD", "different");
        bootstrap().run();

        assertThat(platformAdmin("superadmin").orElseThrow().getPasswordHash())
                .isEqualTo(originalHash);
    }

    @Test
    void clinic_users_named_superadmin_or_admin2_do_not_break_startup() {
        // Usernames are only unique per clinic, so any clinic may pick these names. A bare
        // findByUsername in Bootstrap would then match 2 rows and crash every restart.
        bootstrap().run();
        Long someClinic = clinicRepository.save(com.clinicops.app.domain.Clinic.builder()
                .code("boot-" + System.nanoTime()).name("Other").build()).getId();
        for (String name : new String[] {"superadmin", "admin2"}) {
            userRepository.save(AppUser.builder().clinicId(someClinic).username(name)
                    .fullName("Clinic " + name).role(Role.RECEPTIONIST)
                    .passwordHash("x").enabled(true).build());
        }

        bootstrap().run();

        assertThat(platformAdmin("superadmin")).isPresent();
    }

    @Test
    void mixed_case_platform_admin_username_is_stored_lower_cased_so_login_can_match() {
        env.setProperty("PLATFORM_ADMIN_USERNAME", "  Ops-Root ");

        bootstrap().run();

        assertThat(platformAdmin("ops-root")).isPresent();
    }
}
