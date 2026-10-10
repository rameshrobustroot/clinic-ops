package com.clinicops.app.tenant;

import java.util.function.Function;

/**
 * Defense for {@code findById}/{@code EntityManager.find}, which (on Hibernate 6.6) bypasses the
 * {@code tenantFilter}. Call after every load-by-PK to ensure the loaded row belongs to the
 * current tenant. Throws a generic not-found error to avoid leaking the existence of cross-tenant
 * rows.
 *
 * <pre>{@code
 * Patient p = repo.findById(id).orElseThrow(...);
 * TenantGuard.check(p, Patient::getClinicId);
 * }</pre>
 */
public final class TenantGuard {

    /** Throws if {@code entity} belongs to a different tenant than the current request.
     *
     * <p>A {@code null} clinic_id on the entity is allowed when the entity implements
     * {@link TenantScoped} and returns {@code true} from {@link TenantScoped#nullClinicMeansPlatformShared()}
     * — these are platform-catalog rows (e.g. medicines, master lists) that every clinic
     * is permitted to read. Any other {@code null} clinic_id row is platform-only and readable
     * solely by the platform-admin sentinel (mirrors {@link TenantEntityListener}).
     */
    public static <T> T check(T entity, Function<T, Long> clinicIdOf) {
        if (entity == null) return null;
        Long currentTenant = TenantContext.get();
        if (currentTenant == null) {
            // No request context (e.g. bootstrap/seed code) — skip check
            return entity;
        }
        Long entityTenant = clinicIdOf.apply(entity);
        if (entityTenant == null) {
            // Null clinic_id = platform-shared row — readable by all authenticated tenants
            if (entity instanceof TenantScoped ts && ts.nullClinicMeansPlatformShared()) {
                return entity;
            }
            // Platform-only row (e.g. the PLATFORM_ADMIN's own AppUser): only the platform-admin
            // sentinel may touch it — same rule as TenantEntityListener.
            if (TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL.equals(currentTenant)) {
                return entity;
            }
            throw new CrossTenantAccessException();
        }
        if (!currentTenant.equals(entityTenant)) {
            throw new CrossTenantAccessException();
        }
        return entity;
    }

    private TenantGuard() {}
}
