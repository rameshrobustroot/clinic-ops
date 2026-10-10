package com.clinicops.app.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public AppUserDetails get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new IllegalStateException("No authenticated user in security context");
        }
        return details;
    }

    public Long clinicId() {
        return get().getClinicId();
    }

    public Long userId() {
        return get().getUserId();
    }
}
