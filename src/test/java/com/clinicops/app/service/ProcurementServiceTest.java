package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.integration.BaseIntegrationTest;
import com.clinicops.app.repository.ProcurementItemRepository;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProcurementServiceTest extends BaseIntegrationTest {

    @Autowired ProcurementService procurementService;
    @Autowired ProcurementItemRepository repository;

    Long otherClinicId;

    @BeforeEach
    void setUpOtherClinic() {
        otherClinicId = clinicRepository.save(Clinic.builder()
                .code("proc-other-" + System.nanoTime()).name("Other").build()).getId();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private ProcurementItem item(Long clinic, String name, ProcurementItem.Status status, Instant createdAt) {
        return repository.save(ProcurementItem.builder()
                .clinicId(clinic).medicineName(name).status(status).createdAt(createdAt).build());
    }

    @Nested
    class AddManual {

        @Test
        void creates_pending_manual_item_with_requester() {
            ProcurementItem p = procurementService.addManual(clinicId, userId, "Zincovit", "Patient asked");

            ProcurementItem loaded = repository.findById(p.getId()).orElseThrow();
            assertThat(loaded.getClinicId()).isEqualTo(clinicId);
            assertThat(loaded.getMedicineName()).isEqualTo("Zincovit");
            assertThat(loaded.getSource()).isEqualTo(ProcurementItem.Source.MANUAL);
            assertThat(loaded.getStatus()).isEqualTo(ProcurementItem.Status.PENDING);
            assertThat(loaded.getNotes()).isEqualTo("Patient asked");
            assertThat(loaded.getCreatedBy()).isEqualTo(userId);
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getUpdatedAt()).isNotNull();
            assertThat(loaded.getSourceVisitId()).isNull();
        }
    }

    @Nested
    class Queries {

        @Test
        void pending_returns_only_pending_items_of_this_clinic_newest_first() {
            Instant now = Instant.now();
            ProcurementItem older = item(clinicId, "Older", ProcurementItem.Status.PENDING, now.minus(2, ChronoUnit.HOURS));
            ProcurementItem newer = item(clinicId, "Newer", ProcurementItem.Status.PENDING, now);
            item(clinicId, "Ordered", ProcurementItem.Status.ORDERED, now);
            item(clinicId, "Received", ProcurementItem.Status.RECEIVED, now);
            item(otherClinicId, "Foreign", ProcurementItem.Status.PENDING, now);

            assertThat(procurementService.pending(clinicId))
                    .extracting(ProcurementItem::getId).containsExactly(newer.getId(), older.getId());
            assertThat(procurementService.pendingCount(clinicId)).isEqualTo(2);
        }

        @Test
        void all_returns_every_status_for_this_clinic_newest_first() {
            Instant now = Instant.now();
            ProcurementItem a = item(clinicId, "A", ProcurementItem.Status.CANCELLED, now.minus(3, ChronoUnit.HOURS));
            ProcurementItem b = item(clinicId, "B", ProcurementItem.Status.PENDING, now.minus(1, ChronoUnit.HOURS));
            ProcurementItem c = item(clinicId, "C", ProcurementItem.Status.RECEIVED, now);
            item(otherClinicId, "Foreign", ProcurementItem.Status.PENDING, now);

            assertThat(procurementService.all(clinicId))
                    .extracting(ProcurementItem::getId).containsExactly(c.getId(), b.getId(), a.getId());
        }

        @Test
        void empty_clinic_has_zero_pending() {
            assertThat(procurementService.pending(clinicId)).isEmpty();
            assertThat(procurementService.pendingCount(clinicId)).isZero();
        }
    }

    @Nested
    class UpdateStatus {

        @Test
        void changes_status_and_drops_out_of_pending() {
            ProcurementItem p = procurementService.addManual(clinicId, userId, "X", null);

            ProcurementItem updated = procurementService.updateStatus(p.getId(), ProcurementItem.Status.ORDERED);

            assertThat(updated.getStatus()).isEqualTo(ProcurementItem.Status.ORDERED);
            assertThat(procurementService.pendingCount(clinicId)).isZero();
        }

        @Test
        void unknown_item_is_rejected() {
            assertThatThrownBy(() -> procurementService.updateStatus(-1L, ProcurementItem.Status.ORDERED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Procurement item not found: -1");
        }

        @Test
        void cross_tenant_item_is_rejected() {
            ProcurementItem foreign = item(otherClinicId, "Foreign", ProcurementItem.Status.PENDING, Instant.now());
            TenantContext.set(clinicId);
            assertThatThrownBy(() -> procurementService.updateStatus(foreign.getId(), ProcurementItem.Status.CANCELLED))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }
}
