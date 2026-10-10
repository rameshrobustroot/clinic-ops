package com.clinicops.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.legal.TermsVersion;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Public clinic onboarding: POST /register creates a clinic + OWNER who can log straight in. */
class RegistrationE2ETest extends BaseWebIntegrationTest {

    private MockHttpServletRequestBuilder registration(String clinicName, String username) {
        return registrationNoTerms(clinicName, username).param("acceptTerms", "true");
    }

    private MockHttpServletRequestBuilder registrationNoTerms(String clinicName, String username) {
        return form(clinicName, username, "owner-pass-1");
    }

    private MockHttpServletRequestBuilder form(String clinicName, String username, String confirm) {
        return post("/register").with(csrf())
                .param("clinicName", clinicName)
                .param("clinicPhone", "9811122233")
                .param("clinicAddress", "12 MG Road")
                .param("ownerFullName", "Dr. Owner Person")
                .param("username", username)
                .param("ownerPhone", "9811122244")
                .param("password", "owner-pass-1")
                .param("confirmPassword", confirm);
    }

    @Test
    void registeredOwnerCanLogInAndUseTheAppWithoutTermsPrompt() throws Exception {
        String username = "newowner" + suffix;
        MvcResult r = mvc.perform(registration("Sunrise Family Clinic " + suffix, username))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = r.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/login?registered&code=");
        String code = location.substring(location.indexOf("code=") + 5);
        assertThat(code).startsWith("sunrise-family-clinic-");
        resetSession();

        Clinic created = clinicRepository.findByCode(code).orElseThrow();
        assertThat(created.getName()).isEqualTo("Sunrise Family Clinic " + suffix);
        assertThat(created.getTermsVersion()).isEqualTo(TermsVersion.CURRENT);
        assertThat(created.getTermsAcceptedAt()).isNotNull();
        AppUser owner = userRepository.findByClinicIdAndUsername(created.getId(), username).orElseThrow();
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        assertThat(owner.getPasswordHash()).isNotEqualTo("owner-pass-1");

        // Real form login with clinic code + username.
        mvc.perform(formLogin("/login").user(code + "/" + username).password("owner-pass-1"))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withRoles("OWNER"));
        resetSession();

        // The new owner lands on their (empty) dashboard — not the terms gate.
        mvc.perform(get("/").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/home"));
    }

    @Test
    void registrationValidationErrorsStayOnFormAndCreateNothing() throws Exception {
        long clinicsBefore = clinicRepository.count();

        mvc.perform(form("Mismatch Clinic", "mismatch" + suffix, "different").param("acceptTerms", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("error", containsString("do not match")));

        mvc.perform(registrationNoTerms("No Terms Clinic", "noterms" + suffix))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("error", containsString("accept the Terms")));

        mvc.perform(registration("Bad User Clinic", "Bad User!"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("Username")));
        resetSession();
        assertThat(clinicRepository.count()).isEqualTo(clinicsBefore);
    }

    /**
     * Usernames are unique per clinic (Liquibase 008), so a username another clinic already uses
     * is fine for a brand-new clinic — each owner signs in with their own clinic code.
     */
    @Test
    void usernameUsedByAnotherClinicCanStillRegisterAndBothCanLogIn() throws Exception {
        String shared = "drshared" + suffix;
        String codeA = registeredCode(mvc.perform(registration("Shared Name A " + suffix, shared))
                .andExpect(status().is3xxRedirection()).andReturn());
        resetSession();
        String codeB = registeredCode(mvc.perform(registration("Shared Name B " + suffix, shared))
                .andExpect(status().is3xxRedirection()).andReturn());
        resetSession();

        assertThat(codeA).isNotEqualTo(codeB);
        Long clinicA = clinicRepository.findByCode(codeA).orElseThrow().getId();
        Long clinicB = clinicRepository.findByCode(codeB).orElseThrow().getId();
        mvc.perform(formLogin("/login").user(codeA + "/" + shared).password("owner-pass-1"))
                .andExpect(authenticated().withAuthentication(a -> assertThat(
                        ((com.clinicops.app.security.AppUserDetails) a.getPrincipal()).getClinicId())
                        .isEqualTo(clinicA)));
        mvc.perform(formLogin("/login").user(codeB + "/" + shared).password("owner-pass-1"))
                .andExpect(authenticated().withAuthentication(a -> assertThat(
                        ((com.clinicops.app.security.AppUserDetails) a.getPrincipal()).getClinicId())
                        .isEqualTo(clinicB)));
    }

    private static String registeredCode(MvcResult r) {
        String location = r.getResponse().getRedirectedUrl();
        return location.substring(location.indexOf("code=") + 5);
    }
}