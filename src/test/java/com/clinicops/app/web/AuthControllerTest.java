package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.TermsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link AuthController} — the public login page. */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired MockMvc mvc;

    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    // WebMvcConfig → TermsAcceptanceInterceptor → TermsService + ClinicRepository
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @Test
    void login_page_is_public() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    void login_page_shows_registered_clinic_code() throws Exception {
        mvc.perform(get("/login").param("registered", "").param("code", "sunrise-clinic"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sunrise-clinic")));
    }

    @Test
    void login_page_renders_with_error_param() throws Exception {
        mvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    @WithMockAppUser(role = "PLATFORM_ADMIN")
    void login_page_is_reachable_by_platform_admin() throws Exception {
        // /login is on the PlatformAdminScopeInterceptor whitelist
        mvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void protected_page_redirects_anonymous_user_to_login() throws Exception {
        mvc.perform(get("/settings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
