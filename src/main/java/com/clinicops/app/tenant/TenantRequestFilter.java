package com.clinicops.app.tenant;

import com.clinicops.app.domain.Role;
import com.clinicops.app.security.AppUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after Spring Security has authenticated the request. Pulls the clinic id from the
 * authenticated principal and stashes it in {@link TenantContext} for the duration of the request.
 * Always clears the context on the way out — even on exceptions — to avoid leaking state to
 * pooled threads.
 *
 * <p>For {@link Role#PLATFORM_ADMIN} we bind the tenant to a sentinel
 * ({@link #PLATFORM_ADMIN_TENANT_SENTINEL}) rather than their nominal clinic_id. The platform
 * admin is supposed to live on {@code /platform/**} only (the scope interceptor enforces this);
 * if that ever fails the sentinel ensures every clinical query / load returns zero rows — there
 * is no real clinic with that id, so the Hibernate filter and {@link TenantEntityListener} both
 * refuse to surface any data.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)   // run AFTER the Spring Security filter chain
public class TenantRequestFilter extends OncePerRequestFilter {

    /** A clinic_id that intentionally matches no real clinic. */
    public static final Long PLATFORM_ADMIN_TENANT_SENTINEL = Long.MIN_VALUE;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AppUserDetails details) {
                if (details.getUser().getRole() == Role.PLATFORM_ADMIN) {
                    TenantContext.set(PLATFORM_ADMIN_TENANT_SENTINEL);
                } else {
                    TenantContext.set(details.getClinicId());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
