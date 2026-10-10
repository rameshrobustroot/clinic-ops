package com.clinicops.app.tenant;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * Defensive last-line-of-defence on tenant isolation.
 *
 * <p>Hooked to every {@link TenantScoped} JPA entity via
 * {@code @EntityListeners(TenantEntityListener.class)}. Fires on every load,
 * every insert, every update — so a forgotten {@link TenantGuard#check}, a
 * forgotten {@code AND clinic_id = ?} in a custom query, or a malicious
 * form field that smuggles a different clinic_id into a save will all be
 * rejected with {@link CrossTenantAccessException}, which the global
 * handler translates to 404.
 *
 * <p>Bypass conditions (intentional):
 * <ul>
 *   <li><strong>No tenant context</strong> — e.g. the Bootstrap routine, the
 *       login-resolution query, or unit tests outside a request scope. The
 *       caller must be code we trust, since there is no user to scope to.</li>
 *   <li><strong>Entity tenant is null</strong> — platform-shared row
 *       (master lists). Read is always allowed; writes are gated at the
 *       controller layer by role.</li>
 * </ul>
 */
public class TenantEntityListener {

    @PostLoad
    public void afterLoad(Object entity) {
        assertSameTenant(entity);
    }

    @PrePersist
    @PreUpdate
    public void beforeWrite(Object entity) {
        assertSameTenant(entity);
    }

    private static void assertSameTenant(Object entity) {
        if (!(entity instanceof TenantScoped scoped)) return;
        Long current = TenantContext.get();
        if (current == null) return;            // bootstrap / login / test path
        Long entityTenant = scoped.getClinicId();
        if (entityTenant == null) {
            if (scoped.nullClinicMeansPlatformShared()) return;   // master list — shared
            // Platform-only row (e.g. PLATFORM_ADMIN user). Only the sentinel may touch it.
            if (TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL.equals(current)) return;
            throw new CrossTenantAccessException();
        }
        if (!current.equals(entityTenant)) {
            throw new CrossTenantAccessException();
        }
    }
}
