package com.clinicops.app.legal;

import com.clinicops.app.domain.Role;
import com.clinicops.app.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Confines PLATFORM_ADMIN to the {@code /platform/*} namespace plus a small whitelist
 * (logout, terms, static assets). Attempts to reach clinical pages
 * (/patients, /sales, /inventory, …) are redirected to {@code /platform}.
 *
 * <p>This mirrors the principle that publishers don't see tenant data. Even if a future
 * controller forgets to add a role guard, this interceptor stops the leak at the gate.
 */
@Component
public class PlatformAdminScopeInterceptor implements HandlerInterceptor {

    /** Paths a PLATFORM_ADMIN may visit. Anything else redirects to /platform. */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "/platform",
            "/terms",          // public read-only view of the published version
            "/login",
            "/logout",
            "/css/", "/js/", "/vendor/", "/icons/",
            "/actuator",
            "/manifest.webmanifest",
            "/sw.js"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        if (!(auth.getPrincipal() instanceof AppUserDetails principal)) return true;
        if (principal.getUser().getRole() != Role.PLATFORM_ADMIN) return true;

        String uri = request.getRequestURI();
        for (String prefix : ALLOWED_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                return true;
            }
        }
        response.sendRedirect("/platform");
        return false;
    }
}
