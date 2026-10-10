package com.clinicops.app.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetails;
import com.clinicops.app.service.TermsService;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class TermsAcceptanceInterceptorTest {

    private static final Long CLINIC_ID = 77L;

    TermsService termsService;
    ClinicRepository clinicRepository;
    TermsAcceptanceInterceptor interceptor;
    Clinic clinic;

    @BeforeEach
    void setUp() {
        termsService = mock(TermsService.class);
        clinicRepository = mock(ClinicRepository.class);
        interceptor = new TermsAcceptanceInterceptor(termsService, clinicRepository);
        clinic = Clinic.builder().id(CLINIC_ID).code("c").name("C").build();
        when(clinicRepository.findById(CLINIC_ID)).thenReturn(Optional.of(clinic));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Role role) {
        AppUser user = AppUser.builder().id(1L).clinicId(role == Role.PLATFORM_ADMIN ? null : CLINIC_ID)
                .username("u").passwordHash("p").role(role).enabled(true).build();
        AppUserDetails details = new AppUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private MockHttpServletResponse response;

    private boolean preHandle(String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        response = new MockHttpServletResponse();
        return interceptor.preHandle(req, response, new Object());
    }

    @Nested
    class ExemptPaths {

        @BeforeEach
        void ownerNeedsAcceptance() {
            loginAs(Role.OWNER);
            when(termsService.requiresAcceptance(any())).thenReturn(true);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/terms", "/terms/accept", "/login", "/logout", "/register", "/register/step2",
                "/css/app.css", "/js/app.js", "/vendor/x/y.js", "/icons/i.png",
                "/actuator", "/actuator/health", "/manifest.webmanifest", "/sw.js"})
        void exempt_paths_pass_even_when_owner_must_accept(String uri) throws Exception {
            assertThat(preHandle(uri)).isTrue();
            assertThat(response.getRedirectedUrl()).isNull();
            verifyNoInteractions(termsService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/termsXYZ", "/terms-and-more", "/loginx", "/registerAdmin",
                "/actuators", "/sw.js.map", "/css", "/jsx/a.js"})
        void lookalike_paths_are_not_exempt(String uri) throws Exception {
            assertThat(preHandle(uri)).isFalse();
            assertThat(response.getRedirectedUrl()).isEqualTo("/terms/accept");
        }
    }

    @Nested
    class Authentication {

        @Test
        void unauthenticated_request_passes() throws Exception {
            assertThat(preHandle("/patients")).isTrue();
            verifyNoInteractions(termsService, clinicRepository);
        }

        @Test
        void anonymous_token_with_string_principal_passes() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                    "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

            assertThat(preHandle("/patients")).isTrue();
            verifyNoInteractions(termsService);
        }

        @Test
        void not_authenticated_token_passes() throws Exception {
            AppUserDetails d = new AppUserDetails(AppUser.builder().clinicId(CLINIC_ID)
                    .username("u").passwordHash("p").role(Role.OWNER).build());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(d, null)); // unauthenticated ctor

            assertThat(preHandle("/patients")).isTrue();
            verifyNoInteractions(termsService);
        }
    }

    @Nested
    class Roles {

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"PLATFORM_ADMIN", "DOCTOR", "PHARMACIST", "RECEPTIONIST"})
        void non_owner_roles_are_never_gated(Role role) throws Exception {
            loginAs(role);
            when(termsService.requiresAcceptance(any())).thenReturn(true);

            assertThat(preHandle("/patients")).isTrue();
            verify(termsService, never()).requiresAcceptance(any());
        }

        @Test
        void owner_needing_acceptance_is_redirected_to_accept_page() throws Exception {
            loginAs(Role.OWNER);
            when(termsService.requiresAcceptance(clinic)).thenReturn(true);

            assertThat(preHandle("/patients")).isFalse();
            assertThat(response.getRedirectedUrl()).isEqualTo("/terms/accept");
        }

        @Test
        void owner_with_current_acceptance_passes() throws Exception {
            loginAs(Role.OWNER);
            when(termsService.requiresAcceptance(clinic)).thenReturn(false);

            assertThat(preHandle("/patients")).isTrue();
            assertThat(response.getRedirectedUrl()).isNull();
        }

        @Test
        void owner_whose_clinic_is_missing_passes() throws Exception {
            loginAs(Role.OWNER);
            when(clinicRepository.findById(CLINIC_ID)).thenReturn(Optional.empty());

            assertThat(preHandle("/patients")).isTrue();
            verifyNoInteractions(termsService);
        }

        @Test
        void root_path_is_gated_for_owner() throws Exception {
            loginAs(Role.OWNER);
            when(termsService.requiresAcceptance(clinic)).thenReturn(true);

            assertThat(preHandle("/")).isFalse();
        }
    }

}
