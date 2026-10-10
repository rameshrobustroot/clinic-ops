package com.clinicops.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import com.clinicops.app.security.AppUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

class TenantRequestFilterTest {

    private final TenantRequestFilter filter = new TenantRequestFilter();
    private final AtomicReference<Long> seenTenant = new AtomicReference<>();
    private final FilterChain capturingChain = (req, res) -> seenTenant.set(TenantContext.get());

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Role role, Long clinicId) {
        AppUserDetails d = new AppUserDetails(AppUser.builder().id(1L).clinicId(clinicId)
                .username("u").passwordHash("p").role(role).enabled(true).build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(d, null, d.getAuthorities()));
    }

    private void run(FilterChain chain) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), chain);
    }

    @Test
    void sets_clinic_id_of_normal_user_during_chain_and_clears_after() throws Exception {
        loginAs(Role.DOCTOR, 42L);

        run(capturingChain);

        assertThat(seenTenant.get()).isEqualTo(42L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void platform_admin_gets_sentinel_not_their_nominal_clinic() throws Exception {
        loginAs(Role.PLATFORM_ADMIN, 1L);

        run(capturingChain);

        assertThat(seenTenant.get())
                .isEqualTo(TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL)
                .isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void platform_admin_with_null_clinic_still_gets_sentinel() throws Exception {
        loginAs(Role.PLATFORM_ADMIN, null);

        run(capturingChain);

        assertThat(seenTenant.get()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void unauthenticated_request_leaves_tenant_unset() throws Exception {
        run(capturingChain);

        assertThat(seenTenant.get()).isNull();
    }

    @Test
    void non_app_user_details_principal_leaves_tenant_unset() throws Exception {
        var principal = User.withUsername("x").password("p").roles("OWNER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        run(capturingChain);

        assertThat(seenTenant.get()).isNull();
    }

    @Test
    void stale_tenant_from_previous_request_on_pooled_thread_is_cleared() throws Exception {
        TenantContext.set(999L);

        run(capturingChain);

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void clears_context_even_when_chain_throws() {
        loginAs(Role.OWNER, 7L);
        FilterChain throwing = (req, res) -> {
            seenTenant.set(TenantContext.get());
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> run(throwing)).isInstanceOf(ServletException.class);
        assertThat(seenTenant.get()).isEqualTo(7L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void clears_context_even_when_chain_throws_runtime_exception() {
        loginAs(Role.OWNER, 7L);
        FilterChain throwing = (req, res) -> { throw new IllegalStateException("boom"); };

        assertThatThrownBy(() -> run(throwing)).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.get()).isNull();
    }
}
