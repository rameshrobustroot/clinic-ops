package com.clinicops.app.e2e;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Doctor;
import com.clinicops.app.domain.Role;
import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.legal.TermsVersion;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.repository.TermsDocumentRepository;
import com.clinicops.app.security.AppUserDetails;
import com.clinicops.app.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base for full-stack web tests: real HTTP (MockMvc) → security filter chain → tenant filter →
 * interceptors → controller → service → H2 → rendered Thymeleaf template.
 *
 * <p>Each test gets a fresh clinic ("clinic A") with one real {@link AppUser} per clinic role,
 * plus a PLATFORM_ADMIN user (no clinic). Passwords are encoded with the app's
 * {@link PasswordEncoder} so form login works. A terms document at {@link TermsVersion#CURRENT}
 * is published and clinic A has accepted it, so the OWNER is not bounced by the terms gate.
 *
 * <p><b>Session note:</b> because the test is {@code @Transactional}, every MockMvc request shares
 * one Hibernate session with the test. {@link #resetSession()} flushes + clears it and disables
 * the tenant filter, so each request loads entities fresh from the DB (like production, where each
 * request gets its own session) and test-side verification queries are not tenant-filtered.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseWebIntegrationTest {

    protected static final String PASSWORD = "secret-pass-123";

    @Autowired protected MockMvc mvc;
    @Autowired protected EntityManager em;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected ClinicRepository clinicRepository;
    @Autowired protected AppUserRepository userRepository;
    @Autowired protected DoctorRepository doctorRepository;
    @Autowired protected TermsDocumentRepository termsDocumentRepository;

    protected Clinic clinic;
    protected Doctor doctor;
    /** One user per role. PLATFORM_ADMIN has no clinic. */
    protected final Map<Role, AppUser> users = new EnumMap<>(Role.class);
    protected String suffix;

    @BeforeEach
    void baseWebSetUp() {
        TenantContext.clear();
        suffix = Long.toString(System.nanoTime(), 36);

        termsDocumentRepository.save(TermsDocument.builder()
                .version(TermsVersion.CURRENT)
                .body("<p>Test terms</p>")
                .effectiveDate(LocalDate.parse(TermsVersion.CURRENT))
                .publishedAt(Instant.now().minusSeconds(3600))
                .build());

        clinic = createClinic("e2ea" + suffix, "E2E Clinic A");
        for (Role role : Role.values()) {
            Long clinicId = role == Role.PLATFORM_ADMIN ? null : clinic.getId();
            users.put(role, createUser(clinicId, role.name().toLowerCase().replace('_', '-') + "-" + suffix, role, true));
        }
        doctor = createDoctor(clinic.getId(), "Dr. Asha Rao");
        resetSession();
    }

    @AfterEach
    void baseWebTearDown() {
        TenantContext.clear();
    }

    // ── Fixture helpers ──────────────────────────────────────────────────

    protected Clinic createClinic(String code, String name) {
        return clinicRepository.save(Clinic.builder()
                .code(code.toLowerCase())
                .name(name)
                .termsVersion(TermsVersion.CURRENT)
                .termsAcceptedAt(Instant.now())
                .build());
    }

    protected AppUser createUser(Long clinicId, String username, Role role, boolean enabled) {
        return userRepository.save(AppUser.builder()
                .clinicId(clinicId)
                .username(username.toLowerCase())
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("E2E " + role.name())
                .role(role)
                .enabled(enabled)
                .build());
    }

    protected Doctor createDoctor(Long clinicId, String name) {
        return doctorRepository.save(Doctor.builder()
                .clinicId(clinicId)
                .fullName(name)
                .active(true)
                .build());
    }

    protected AppUser user(Role role) {
        return users.get(role);
    }

    /** Authenticates the request as {@code user} with a real {@link AppUserDetails} principal. */
    protected static RequestPostProcessor as(AppUser user) {
        return SecurityMockMvcRequestPostProcessors.user(new AppUserDetails(user));
    }

    protected RequestPostProcessor as(Role role) {
        return as(user(role));
    }

    protected RequestPostProcessor owner() {
        return as(Role.OWNER);
    }

    /** Login identifier as the login page's JS composes it: {@code clinicCode/username}. */
    protected static String loginName(Clinic c, AppUser u) {
        return c.getCode() + "/" + u.getUsername();
    }

    /**
     * Flush pending writes, detach everything and turn off the Hibernate tenant filter, so the
     * next request (or verification query) reads fresh rows from the DB without leftover state.
     */
    protected void resetSession() {
        em.flush();
        em.clear();
        Session session = em.unwrap(Session.class);
        if (session.getEnabledFilter("tenantFilter") != null) {
            session.disableFilter("tenantFilter");
        }
        TenantContext.clear();
    }
}
