package com.clinicops.app.web;

import com.clinicops.app.security.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes {@code theme} as a model attribute on every page render so the Tailwind palette
 * configured in the head fragment can swap accent colours per-user.
 */
@ControllerAdvice
public class ThemeAdvice {

    @ModelAttribute("theme")
    public String theme() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails details) {
            return details.getTheme();
        }
        return "violet";
    }
}
