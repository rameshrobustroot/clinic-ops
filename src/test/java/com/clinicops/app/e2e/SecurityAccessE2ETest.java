package com.clinicops.app.e2e;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.repository.PatientRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Authentication, authorization, CSRF, platform-admin scoping and the terms gate over real HTTP. */
class SecurityAccessE2ETest extends BaseWebIntegrationTest {

    @Autowired PatientRepository patientRepository;

    // ── Anonymous access ─────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"/", "/patients", "/visits/1", "/sales", "/inventory", "/alerts", "/export/patients.csv"})
    void anonymousUserIsRedirectedToLogin(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/register", "/terms", "/actuator/health", "/manifest.webmanifest", "/sw.js"})
    void publicPagesAreReachableAnonymously(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void staticAssetsArePublic() throws Exception {
        // Any static resource under a permitted prefix must not trigger the login redirect.
        mvc.perform(get("/css/does-not-exist.css"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s == 302) throw new AssertionError("static path redirected to login");
                });
    }

    @Test
    void loginPageRendersClinicCodeUsernameAndPasswordFields() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"clinicCode\"")))
                .andExpect(content().string(Matchers.containsString("name=\"username\"")))
                .andExpect(content().string(Matchers.containsString("name=\"password\"")));
    }

    // ── Form login ───────────────────────────────────────────────────────

    @Test
    void formLoginWithClinicCodeAndValidPasswordSucceeds() throws Exception {
        AppUser doc = user(Role.DOCTOR);
        mvc.perform(formLogin("/login").user(loginName(clinic, doc)).password(PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(doc.getUsername()).withRoles("DOCTOR"));
    }

    @Test
    void formLoginWithWrongPasswordFails() throws Exception {
        mvc.perform(formLogin("/login").user(loginName(clinic, user(Role.OWNER))).password("wrong-password"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void formLoginWithWrongClinicCodeFails() throws Exception {
        var other = createClinic("e2eother" + suffix, "Other Clinic");
        resetSession();
        mvc.perform(formLogin("/login").user(loginName(other, user(Role.OWNER))).password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void clinicUserCannotLoginWithoutClinicCode() throws Exception {
        // No "code/" prefix is the platform-admin path; a clinic user must be rejected there.
        mvc.perform(formLogin("/login").user(user(Role.OWNER).getUsername()).password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        AppUser disabled = createUser(clinic.getId(), "disabled-" + suffix, Role.RECEPTIONIST, false);
        resetSession();
        mvc.perform(formLogin("/login").user(loginName(clinic, disabled)).password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void platformAdminLogsInWithoutClinicCode() throws Exception {
        AppUser pa = user(Role.PLATFORM_ADMIN);
        mvc.perform(formLogin("/login").user(pa.getUsername()).password(PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withRoles("PLATFORM_ADMIN"));
    }

    @Test
    void logoutRedirectsToLoginPage() throws Exception {
        mvc.perform(post("/logout").with(owner()).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(unauthenticated());
    }

    // ── CSRF ─────────────────────────────────────────────────────────────

    @Test
    void postWithoutCsrfTokenIsForbiddenAndCreatesNothing() throws Exception {
        long before = patientRepository.count();
        mvc.perform(post("/patients").with(owner())
                        .param("name", "Csrf Victim")
                        .param("phone", "9876501234"))
                .andExpect(status().isForbidden());
        resetSession();
        org.assertj.core.api.Assertions.assertThat(patientRepository.count()).isEqualTo(before);
    }

    @Test
    void postWithCsrfTokenIsAccepted() throws Exception {
        mvc.perform(post("/patients").with(owner()).with(csrf())
                        .param("name", "Csrf Ok")
                        .param("phone", "9876501234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/patients"));
    }

    // ── Role-based access ────────────────────────────────────────────────

    @Test
    void authenticatedClinicUserCanOpenDashboard() throws Exception {
        for (Role r : new Role[] {Role.OWNER, Role.DOCTOR, Role.PHARMACIST, Role.RECEPTIONIST}) {
            mvc.perform(get("/").with(as(r)))
                    .andExpect(status().isOk())
                    .andExpect(view().name("dashboard/home"));
        }
    }

    @Test
    void exportIsOwnerOnly() throws Exception {
        mvc.perform(get("/export/patients.csv").with(as(Role.RECEPTIONIST)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/export/patients.csv").with(owner()))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminIsConfinedToPlatformNamespace() throws Exception {
        for (String path : new String[] {"/patients", "/", "/sales", "/inventory", "/export/patients.csv"}) {
            mvc.perform(get(path).with(as(Role.PLATFORM_ADMIN)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform"));
        }
        mvc.perform(get("/platform").with(as(Role.PLATFORM_ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void clinicOwnerCannotReachPlatformConsole() throws Exception {
        mvc.perform(get("/platform").with(owner()))
                .andExpect(status().isForbidden());
    }

    // ── Terms gate ───────────────────────────────────────────────────────

    @Test
    void ownerWithOutdatedTermsIsGatedUntilAcceptance_otherRolesAreNot() throws Exception {
        termsDocumentRepository.save(TermsDocument.builder()
                .version("2099-01-01")
                .body("<p>New terms</p>")
                .effectiveDate(LocalDate.parse("2099-01-01"))
                .publishedAt(Instant.now().plusSeconds(60))
                .build());
        resetSession();

        mvc.perform(get("/patients").with(owner()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/terms/accept"));
        mvc.perform(get("/").with(owner()))
                .andExpect(redirectedUrl("/terms/accept"));

        // Non-owners keep working.
        mvc.perform(get("/patients").with(as(Role.DOCTOR))).andExpect(status().isOk());
        mvc.perform(get("/patients").with(as(Role.RECEPTIONIST))).andExpect(status().isOk());

        // The accept page itself is reachable and shows the new version.
        mvc.perform(get("/terms/accept").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("2099-01-01")));

        // Submitting without the tick box keeps the gate closed.
        mvc.perform(post("/terms/accept").with(owner()).with(csrf()))
                .andExpect(redirectedUrl("/terms/accept"));
        resetSession();
        mvc.perform(get("/patients").with(owner())).andExpect(redirectedUrl("/terms/accept"));

        // Accepting opens the gate.
        mvc.perform(post("/terms/accept").with(owner()).with(csrf()).param("acceptTerms", "true"))
                .andExpect(redirectedUrl("/"));
        resetSession();
        org.assertj.core.api.Assertions.assertThat(
                clinicRepository.findById(clinic.getId()).orElseThrow().getTermsVersion())
                .isEqualTo("2099-01-01");
        mvc.perform(get("/patients").with(owner())).andExpect(status().isOk());
    }
}
