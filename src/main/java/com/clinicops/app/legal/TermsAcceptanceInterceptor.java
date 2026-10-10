package com.clinicops.app.legal;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetails;
import com.clinicops.app.service.TermsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Forces an OWNER on an outdated terms version to re-accept before they can keep using
 * the app. Non-OWNER users (doctors, pharmacists, receptionists) are not blocked — only
 * the clinic's owner can record acceptance on the clinic's behalf.
 *
 * <p>Whitelisted paths: the accept form itself, login/logout, public terms page, static
 * assets, and the health endpoint. Everything else for an authenticated OWNER routes
 * through this gate.
 */
@Component
@RequiredArgsConstructor
public class TermsAcceptanceInterceptor implements HandlerInterceptor {

    /** URI prefixes that bypass the gate so the user can actually see the accept page + log out. */
    private static final Set<String> EXEMPT_PREFIXES = Set.of(
            "/terms",        // includes /terms and /terms/accept
            "/login",
            "/logout",
            "/register",
            "/css/", "/js/", "/vendor/", "/icons/",
            "/actuator",
            "/manifest.webmanifest",
            "/sw.js"
    );

    private final TermsService termsService;
    private final ClinicRepository clinicRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        for (String prefix : EXEMPT_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                return true;
            }
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        if (!(auth.getPrincipal() instanceof AppUserDetails principal)) return true;

        AppUser user = principal.getUser();
        // Platform admins publish terms; they don't accept on behalf of a clinic.
        if (user.getRole() == Role.PLATFORM_ADMIN) return true;
        // Among clinic-bound roles, only OWNERs can bind the clinic by accepting.
        if (user.getRole() != Role.OWNER) return true;

        Clinic clinic = clinicRepository.findById(user.getClinicId()).orElse(null);
        if (clinic == null) return true;

        if (termsService.requiresAcceptance(clinic)) {
            response.sendRedirect("/terms/accept");
            return false;
        }
        return true;
    }
}
