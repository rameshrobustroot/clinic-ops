package com.clinicops.app.security;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class Bootstrap implements CommandLineRunner {

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";
    private static final Long DEFAULT_CLINIC_ID = 1L;

    /** Platform admin defaults — must be changed before going live.
     *  Override via env: PLATFORM_ADMIN_USERNAME, PLATFORM_ADMIN_PASSWORD. */
    private static final String PLATFORM_ADMIN_USERNAME_DEFAULT = "superadmin";
    private static final String PLATFORM_ADMIN_PASSWORD_DEFAULT = "superadmin";

    private final AppUserRepository userRepository;
    private final com.clinicops.app.repository.ClinicRepository clinicRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.core.env.Environment env;

    @Override
    public void run(String... args) {
        // Ensure existing seeded clinic gets a code (idempotent — only sets if missing)
        clinicRepository.findById(DEFAULT_CLINIC_ID).ifPresent(c -> {
            if (c.getCode() == null || c.getCode().isBlank()) {
                c.setCode("demo1");
                clinicRepository.save(c);
            }
        });

        if (userRepository.count() == 0) {
            AppUser admin = AppUser.builder()
                    .clinicId(DEFAULT_CLINIC_ID)
                    .username(DEFAULT_USERNAME)
                    .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                    .fullName("Admin Owner")
                    .role(Role.OWNER)
                    .enabled(true)
                    .build();
            userRepository.save(admin);
            log.info("Bootstrapped default admin user. Clinic code: demo1  Username: {}  Password: {}",
                    DEFAULT_USERNAME, DEFAULT_PASSWORD);
            log.warn("CHANGE THE DEFAULT PASSWORD AFTER FIRST LOGIN.");
        }

        // Platform admin — the software publisher's terms-management identity.
        // Tenant-scoped to clinic 1 because every AppUser has clinic_id NOT NULL,
        // but the PLATFORM_ADMIN role makes their tenant attachment cosmetic.
        // Lower-cased because login lower-cases what the user types; "SuperAdmin" could never sign in.
        String paUsername = env.getProperty("PLATFORM_ADMIN_USERNAME", PLATFORM_ADMIN_USERNAME_DEFAULT).trim().toLowerCase();
        String paPassword = env.getProperty("PLATFORM_ADMIN_PASSWORD", PLATFORM_ADMIN_PASSWORD_DEFAULT);
        if (userRepository.findByRoleAndUsername(Role.PLATFORM_ADMIN, paUsername).isEmpty()) {
            AppUser platformAdmin = AppUser.builder()
                    // clinic_id intentionally NULL — platform admins don't belong to any clinic.
                    .clinicId(null)
                    .username(paUsername)
                    .passwordHash(passwordEncoder.encode(paPassword))
                    .fullName("Platform Admin")
                    .role(Role.PLATFORM_ADMIN)
                    .enabled(true)
                    .build();
            userRepository.save(platformAdmin);
            log.info("Bootstrapped PLATFORM_ADMIN. Username: {}  Password: {}  (no clinic code needed)",
                    paUsername, paPassword);
            if (PLATFORM_ADMIN_PASSWORD_DEFAULT.equals(paPassword)) {
                log.warn("PLATFORM_ADMIN is using the default password. SET PLATFORM_ADMIN_PASSWORD env var before going live.");
            }
        }

        // Verification tenant — a second clinic with its own admin, so isolation can be
        // confirmed by switching login. Comment out for prod.
        if (!clinicRepository.existsByCode("demo2")) {
            com.clinicops.app.domain.Clinic second = clinicRepository.save(
                    com.clinicops.app.domain.Clinic.builder()
                            .code("demo2")
                            .name("Demo Clinic 2")
                            .phone("0000000000")
                            .address("Tenant-2 verification")
                            .build());
            AppUser secondAdmin = AppUser.builder()
                    .clinicId(second.getId())
                    .username("admin2")
                    .passwordHash(passwordEncoder.encode("admin123"))
                    .fullName("Tenant-2 Owner")
                    .role(Role.OWNER)
                    .enabled(true)
                    .build();
            userRepository.save(secondAdmin);
            log.info("Bootstrapped second tenant clinic id={} code={} with admin user 'admin2' / admin123",
                    second.getId(), second.getCode());
        }
    }
}
