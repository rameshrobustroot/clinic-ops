package com.clinicops.app.tenant;

/**
 * Thread-local holder for the current request's tenant (clinic) id.
 *
 * <p>Set by {@link TenantRequestFilter} from the authenticated user; cleared at the end of every
 * request. Read by Hibernate's {@code tenantFilter} (enabled in {@link TenantFilterEnabler}) so
 * every clinic-scoped read query is automatically scoped to the caller's clinic.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    public static void set(Long clinicId) {
        if (clinicId == null) CURRENT.remove();
        else CURRENT.set(clinicId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    private TenantContext() {}
}
