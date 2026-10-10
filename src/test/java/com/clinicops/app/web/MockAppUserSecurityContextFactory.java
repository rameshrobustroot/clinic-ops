package com.clinicops.app.web;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import com.clinicops.app.security.AppUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Factory that creates a Spring Security context containing a real {@link AppUserDetails}
 * principal for {@link WithMockAppUser}-annotated tests.
 *
 * <p>This is necessary because Thymeleaf's {@code sec:authentication="principal.fullName"}
 * attribute uses reflection to access the {@code fullName} property of the authenticated
 * principal.  {@code @WithMockUser} supplies a generic {@code User} which lacks that
 * property, causing a {@code TemplateProcessingException} at rendering time.
 */
public class MockAppUserSecurityContextFactory
        implements WithSecurityContextFactory<WithMockAppUser> {

    @Override
    public SecurityContext createSecurityContext(WithMockAppUser annotation) {
        AppUser user = AppUser.builder()
                .id(99L)
                .clinicId(annotation.clinicId())
                .username(annotation.username())
                .fullName(annotation.fullName())
                .role(Role.valueOf(annotation.role()))
                .enabled(true)
                .passwordHash("n/a")
                .build();

        AppUserDetails principal = new AppUserDetails(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }
}
