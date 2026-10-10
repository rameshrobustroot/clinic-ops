package com.clinicops.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TenantGuard#check}. (TenantIsolationTest covers the JPA
 * listener path; this covers the explicit guard used after findById in services.)
 */
class TenantGuardTest {

    /** Tenant-scoped row whose null clinic means "platform admin only" (default). */
    record PrivateRow(Long clinicId) implements TenantScoped {
        @Override public Long getClinicId() { return clinicId; }
    }

    /** Master-list style row where null clinic means "shared with every clinic". */
    record SharedRow(Long clinicId) implements TenantScoped {
        @Override public Long getClinicId() { return clinicId; }
        @Override public boolean nullClinicMeansPlatformShared() { return true; }
    }

    /** Not TenantScoped at all (e.g. Clinic, guarded by its own id). */
    record PlainRow(Long clinicId) {}

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void null_entity_returns_null() {
        TenantContext.set(1L);
        assertThat(TenantGuard.<PrivateRow>check(null, PrivateRow::clinicId)).isNull();
    }

    @Nested
    class NoTenantContext {

        @Test
        void any_entity_passes_including_null_clinic() {
            PrivateRow other = new PrivateRow(99L);
            PrivateRow orphan = new PrivateRow(null);

            assertThat(TenantGuard.check(other, PrivateRow::clinicId)).isSameAs(other);
            assertThat(TenantGuard.check(orphan, PrivateRow::clinicId)).isSameAs(orphan);
        }
    }

    @Nested
    class ClinicTenant {

        @Test
        void same_clinic_returns_entity() {
            TenantContext.set(5L);
            PrivateRow row = new PrivateRow(5L);

            assertThat(TenantGuard.check(row, PrivateRow::clinicId)).isSameAs(row);
        }

        @Test
        void other_clinic_throws() {
            TenantContext.set(5L);

            assertThatThrownBy(() -> TenantGuard.check(new PrivateRow(6L), PrivateRow::clinicId))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void equal_but_not_identical_long_values_match() {
            TenantContext.set(Long.valueOf(100_000L));
            PrivateRow row = new PrivateRow(Long.valueOf(100_000L));

            assertThat(TenantGuard.check(row, PrivateRow::clinicId)).isSameAs(row);
        }

        @Test
        void null_clinic_on_platform_shared_row_is_readable() {
            TenantContext.set(5L);
            SharedRow row = new SharedRow(null);

            assertThat(TenantGuard.check(row, SharedRow::clinicId)).isSameAs(row);
        }

        @Test
        void null_clinic_on_platform_only_row_throws() {
            TenantContext.set(5L);

            assertThatThrownBy(() -> TenantGuard.check(new PrivateRow(null), PrivateRow::clinicId))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void null_clinic_on_non_tenant_scoped_object_throws() {
            TenantContext.set(5L);

            assertThatThrownBy(() -> TenantGuard.check(new PlainRow(null), PlainRow::clinicId))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void shared_row_owned_by_other_clinic_still_throws() {
            TenantContext.set(5L);

            assertThatThrownBy(() -> TenantGuard.check(new SharedRow(6L), SharedRow::clinicId))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    @Nested
    class PlatformAdminSentinel {

        @Test
        void sentinel_cannot_read_any_real_clinic_row() {
            TenantContext.set(TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL);

            assertThatThrownBy(() -> TenantGuard.check(new PrivateRow(1L), PrivateRow::clinicId))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void sentinel_can_read_platform_shared_rows() {
            TenantContext.set(TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL);
            SharedRow row = new SharedRow(null);

            assertThat(TenantGuard.check(row, SharedRow::clinicId)).isSameAs(row);
        }

        @Test
        void sentinel_can_read_platform_only_rows_consistent_with_entity_listener() {
            TenantContext.set(TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL);
            PrivateRow row = new PrivateRow(null);

            assertThat(TenantGuard.check(row, PrivateRow::clinicId)).isSameAs(row);
        }
    }
}
