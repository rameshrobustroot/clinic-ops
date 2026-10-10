package com.clinicops.app.tenant;

/**
 * Marker interface for entities that belong to a specific clinic tenant.
 *
 * <p>Every clinic-scoped JPA entity implements this and is annotated with
 * {@code @EntityListeners(TenantEntityListener.class)}. The listener
 * enforces tenant isolation on <em>every</em> load (postLoad) and write
 * (prePersist / preUpdate) — defense-in-depth on top of the Hibernate
 * filter and any controller-level checks.
 *
 * <p>An entity row's tenant is identified by {@link #getClinicId()}. A
 * {@code null} value has two possible meanings, distinguished by
 * {@link #nullClinicMeansPlatformShared()}:
 * <ul>
 *   <li><strong>true</strong> — the row is shared with every clinic (e.g.
 *       {@code medical_degree} platform rows). Every authenticated user
 *       can read it; writes are role-gated at the controller layer.</li>
 *   <li><strong>false</strong> (default) — the row is owned by the
 *       platform admin alone (e.g. the PLATFORM_ADMIN row in
 *       {@code app_user}). Only the PLATFORM_ADMIN tenant sentinel can
 *       touch it; clinic users get 404.</li>
 * </ul>
 */
public interface TenantScoped {
    Long getClinicId();

    /** Master lists override to {@code true}. Defaults to {@code false} so a
     *  forgotten override fails safe (any null clinic_id row is platform-only). */
    default boolean nullClinicMeansPlatformShared() {
        return false;
    }
}
