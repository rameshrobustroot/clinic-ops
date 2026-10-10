package com.clinicops.app.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import com.clinicops.app.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

class PlatformAdminScopeInterceptorTest {

    private final PlatformAdminScopeInterceptor interceptor = new PlatformAdminScopeInterceptor();
    private MockHttpServletResponse response;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Role role) {
        AppUserDetails d = new AppUserDetails(AppUser.builder().id(1L)
                .clinicId(role == Role.PLATFORM_ADMIN ? null : 5L)
                .username("u").passwordHash("p").role(role).enabled(true).build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(d, null, d.getAuthorities()));
    }

    private boolean preHandle(String uri) throws Exception {
        response = new MockHttpServletResponse();
        return interceptor.preHandle(new MockHttpServletRequest("GET", uri), response, new Object());
    }

    @Nested
    class PlatformAdmin {

        @ParameterizedTest
        @ValueSource(strings = {"/platform", "/platform/terms", "/platform/masters/degrees",
                "/terms", "/terms/accept", "/login", "/logout",
                "/css/a.css", "/js/a.js", "/vendor/v.js", "/icons/i.svg",
                "/actuator", "/actuator/health", "/manifest.webmanifest", "/sw.js"})
        void allowed_paths_pass(String uri) throws Exception {
            loginAs(Role.PLATFORM_ADMIN);

            assertThat(preHandle(uri)).isTrue();
            assertThat(response.getRedirectedUrl()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/", "/patients", "/patients/1", "/sales", "/inventory",
                "/settings", "/register", "/platformx", "/platform-evil", "/termsXYZ", "/sw.jsx"})
        void clinical_and_lookalike_paths_redirect_to_platform(String uri) throws Exception {
            loginAs(Role.PLATFORM_ADMIN);

            assertThat(preHandle(uri)).isFalse();
            assertThat(response.getRedirectedUrl()).isEqualTo("/platform");
        }
    }

    @Nested
    class OtherUsers {

        @ParameterizedTest
        @EnumSource(value = Role.class, names = "PLATFORM_ADMIN", mode = EnumSource.Mode.EXCLUDE)
        void clinic_roles_are_not_confined(Role role) throws Exception {
            loginAs(role);

            assertThat(preHandle("/patients")).isTrue();
            assertThat(response.getRedirectedUrl()).isNull();
        }

        @Test
        void unauthenticated_request_passes() throws Exception {
            assertThat(preHandle("/patients")).isTrue();
        }

        @Test
        void non_app_user_details_principal_passes() throws Exception {
            var principal = User.withUsername("x").password("p").roles("PLATFORM_ADMIN").build();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

            assertThat(preHandle("/patients")).isTrue();
        }
    }
}
