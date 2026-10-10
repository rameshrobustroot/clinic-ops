package com.clinicops.app.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Sets up the Spring Security test context with a real {@code AppUserDetails} principal
 * so Thymeleaf's {@code sec:authentication="principal.fullName"} works in
 * {@code @WebMvcTest} controller tests.
 *
 * <p>Usage:
 * <pre>{@code
 * @Test
 * @WithMockAppUser(role = "OWNER", clinicId = 1L, fullName = "Test User")
 * void myTest() { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = MockAppUserSecurityContextFactory.class)
public @interface WithMockAppUser {

    /** Spring Security role without the ROLE_ prefix, e.g. "OWNER". */
    String role() default "OWNER";

    long clinicId() default 1L;

    String fullName() default "Test User";

    String username() default "testuser";
}
