package com.clinicops.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Appointment;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.AppointmentRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.repository.StockBatchRepository;
import com.clinicops.app.repository.VisitRepository;
import com.clinicops.app.service.AppointmentForm;
import com.clinicops.app.service.AppointmentService;
import com.clinicops.app.service.SaleService;
import com.clinicops.app.service.VisitService;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests that verify the tenant-isolation invariants hold end-to-end.
 *
 * <p>Strategy: two clinics — clinic A (from {@link BaseIntegrationTest}) and clinic B created
 * in {@link #setUpSecondClinic()}. Each test sets {@link TenantContext} to the acting clinic,
 * calls a service method with data belonging to the other clinic, and asserts that access is
 * blocked via {@link CrossTenantAccessException}.
 *
 * <p>The explicit {@link com.clinicops.app.tenant.TenantGuard} calls in service methods are the
 * primary guard here — they fire even when the entity is returned from the JPA first-level cache
 * (which bypasses both the Hibernate SQL filter and the {@code @PostLoad} entity listener).
 */
class TenantIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired SaleService saleService;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired SaleRepository saleRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired VisitService visitService;
    @Autowired VisitRepository visitRepository;
    @Autowired StockBatchRepository stockBatchRepository;

    /** Clinic B — the "other" tenant for cross-clinic access tests. */
    Long clinic2Id;
    Long patient2Id;

    @BeforeEach
    void setUpSecondClinic() {
        Clinic clinic2 = clinicRepository.save(
                Clinic.builder()
                        .code("iso-test-2-" + System.nanoTime())
                        .name("Isolation Test Clinic 2")
                        .build());
        clinic2Id = clinic2.getId();

        Patient p2 = patientRepository.save(
                Patient.builder()
                        .clinicId(clinic2Id)
                        .name("Patient B")
                        .phone("9800000002")
                        .mrn("MRN-B-" + System.nanoTime())
                        .build());
        patient2Id = p2.getId();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── AppointmentService.getOrThrow ────────────────────────────────────

    @Nested
    class AppointmentGetOrThrow {

        @Test
        void cross_tenant_appointment_is_rejected() {
            Appointment appt2 = appointmentRepository.save(Appointment.builder()
                    .clinicId(clinic2Id)
                    .patientName("Clinic-B Patient")
                    .appointmentDate(LocalDate.now())
                    .status("SCHEDULED")
                    .tokenNo(1)
                    .build());

            TenantContext.set(clinicId); // acting as clinic A
            assertThatThrownBy(() -> appointmentService.getOrThrow(appt2.getId()))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void same_tenant_appointment_loads_successfully() {
            Appointment appt = appointmentRepository.save(Appointment.builder()
                    .clinicId(clinicId)
                    .patientName("My Patient")
                    .appointmentDate(LocalDate.now())
                    .status("SCHEDULED")
                    .tokenNo(1)
                    .build());

            TenantContext.set(clinicId);
            Appointment loaded = appointmentService.getOrThrow(appt.getId());
            assertThat(loaded.getId()).isEqualTo(appt.getId());
            assertThat(loaded.getClinicId()).isEqualTo(clinicId);
        }
    }

    // ── AppointmentService.book with cross-clinic patientId ──────────────

    @Nested
    class AppointmentBookPatientGuard {

        @Test
        void book_with_cross_tenant_patient_id_is_rejected() {
            TenantContext.set(clinicId); // clinic A is acting

            AppointmentForm form = new AppointmentForm();
            form.setPatientId(patient2Id); // patient belongs to clinic B
            form.setAppointmentDate(LocalDate.now());
            form.setPatientName("Override Name");

            assertThatThrownBy(() -> appointmentService.book(clinicId, form))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void book_with_own_clinic_patient_id_succeeds() {
            TenantContext.set(clinicId);

            AppointmentForm form = new AppointmentForm();
            form.setPatientId(patientId); // patient belongs to clinic A
            form.setAppointmentDate(LocalDate.now());
            form.setPatientName("Walk-in");

            Appointment appt = appointmentService.book(clinicId, form);
            assertThat(appt.getClinicId()).isEqualTo(clinicId);
            assertThat(appt.getPatientId()).isEqualTo(patientId);
        }

        @Test
        void book_with_null_patient_id_uses_walk_in_name() {
            TenantContext.set(clinicId);

            AppointmentForm form = new AppointmentForm();
            form.setPatientId(null); // walk-in, no patientId
            form.setAppointmentDate(LocalDate.now());
            form.setPatientName("Walk-in Guest");

            Appointment appt = appointmentService.book(clinicId, form);
            assertThat(appt.getPatientName()).isEqualTo("Walk-in Guest");
            assertThat(appt.getPatientId()).isNull();
        }
    }

    // ── SaleService.salesToday isolation ─────────────────────────────────

    @Nested
    class SalesTodayIsolation {

        @Test
        void salesToday_excludes_other_clinic_sales() {
            saleRepository.save(Sale.builder()
                    .clinicId(clinicId).billNo("A-001")
                    .totalAmount(BigDecimal.TEN).build());
            saleRepository.save(Sale.builder()
                    .clinicId(clinic2Id).billNo("B-001")
                    .totalAmount(BigDecimal.TEN).build());

            TenantContext.set(clinicId);
            List<Sale> today = saleService.salesToday(clinicId);

            assertThat(today).allSatisfy(s -> assertThat(s.getClinicId()).isEqualTo(clinicId));
            assertThat(today).extracting(Sale::getBillNo).contains("A-001");
            assertThat(today).extracting(Sale::getBillNo).doesNotContain("B-001");
        }

        @Test
        void salesToday_returns_all_of_current_clinic_sales_made_today() {
            saleRepository.save(Sale.builder()
                    .clinicId(clinicId).billNo("A-TODAY-1")
                    .totalAmount(new BigDecimal("100.00")).build());
            saleRepository.save(Sale.builder()
                    .clinicId(clinicId).billNo("A-TODAY-2")
                    .totalAmount(new BigDecimal("200.00")).build());

            TenantContext.set(clinicId);
            List<Sale> today = saleService.salesToday(clinicId);

            assertThat(today).extracting(Sale::getBillNo)
                    .contains("A-TODAY-1", "A-TODAY-2");
        }
    }

    // ── Cross-clinic patient / visit references on writes ────────────────
    // These guards compare against the explicit clinicId, so they must hold even when no
    // TenantContext is bound (background jobs, future callers).

    @Nested
    class CrossClinicReferencesOnWrite {

        @Test
        void visit_create_for_other_clinic_patient_is_rejected_without_tenant_context() {
            assertThatThrownBy(() -> visitService.create(patient2Id, doctorId, clinicId, null,
                    "dx", null, null, null, null, null, null, null))
                    .isInstanceOf(CrossTenantAccessException.class);
            assertThat(visitRepository.findAll()).noneMatch(v -> patient2Id.equals(v.getPatientId()));
        }

        @Test
        void visit_create_for_unknown_patient_is_rejected() {
            assertThatThrownBy(() -> visitService.create(-1L, doctorId, clinicId, null,
                    "dx", null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Patient not found");
        }

        @Test
        void sale_for_other_clinic_patient_is_rejected_without_tenant_context() {
            assertThatThrownBy(() -> saleService.record(clinicId, null, patient2Id, null,
                    "CASH", null, null, List.of(SaleService.SaleLine.of(1L, 1)), null, null))
                    .isInstanceOf(CrossTenantAccessException.class);
            assertThat(saleRepository.findAll()).noneMatch(s -> patient2Id.equals(s.getPatientId()));
        }

        @Test
        void sale_of_shared_medicine_never_dispenses_another_clinics_batch() {
            // Platform medicine (clinic_id NULL) stocked by BOTH clinics. Clinic B's batch expires
            // first, so a clinic-blind FEFO query would pick it.
            Medicine shared = medicineRepository.save(Medicine.builder()
                    .clinicId(null).name("Shared Dolo").active(true).build());
            StockBatch bBatch = stockBatchRepository.save(StockBatch.builder()
                    .clinicId(clinic2Id).medicineId(shared.getId()).batchNumber("B-EARLY")
                    .expiryDate(LocalDate.now().plusDays(10)).quantity(50).initialQuantity(50)
                    .salePrice(BigDecimal.ONE).build());
            StockBatch aBatch = stockBatchRepository.save(StockBatch.builder()
                    .clinicId(clinicId).medicineId(shared.getId()).batchNumber("A-LATE")
                    .expiryDate(LocalDate.now().plusDays(300)).quantity(5).initialQuantity(5)
                    .salePrice(BigDecimal.ONE).build());

            // No TenantContext: the guard must come from the query itself, not the session filter.
            saleService.record(clinicId, null, patientId, null, "CASH", null, null,
                    List.of(SaleService.SaleLine.of(shared.getId(), 3)), null, null);

            assertThat(stockBatchRepository.findById(bBatch.getId()).orElseThrow().getQuantity()).isEqualTo(50);
            assertThat(stockBatchRepository.findById(aBatch.getId()).orElseThrow().getQuantity()).isEqualTo(2);
        }

        @Test
        void sale_of_shared_medicine_cannot_borrow_other_clinics_stock_to_cover_a_shortfall() {
            Medicine shared = medicineRepository.save(Medicine.builder()
                    .clinicId(null).name("Shared Cetzine").active(true).build());
            stockBatchRepository.save(StockBatch.builder()
                    .clinicId(clinic2Id).medicineId(shared.getId()).batchNumber("B-ONLY")
                    .expiryDate(LocalDate.now().plusDays(100)).quantity(100).initialQuantity(100)
                    .salePrice(BigDecimal.ONE).build());

            assertThatThrownBy(() -> saleService.record(clinicId, null, patientId, null, "CASH", null, null,
                    List.of(SaleService.SaleLine.of(shared.getId(), 1)), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insufficient stock");
        }

        @Test
        void sale_linked_to_other_clinic_visit_is_rejected() {
            Visit visit2 = visitRepository.save(Visit.builder()
                    .clinicId(clinic2Id).patientId(patient2Id).encounterType("OPD")
                    .visitDate(Instant.now()).build());

            assertThatThrownBy(() -> saleService.record(clinicId, null, null, visit2.getId(),
                    "CASH", "Walk-in", null, List.of(SaleService.SaleLine.of(1L, 1)), null, null))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    // ── SaleService.getOrThrow ───────────────────────────────────────────

    @Nested
    class SaleGetOrThrow {

        @Test
        void cross_tenant_sale_is_rejected() {
            Sale sale2 = saleRepository.save(Sale.builder()
                    .clinicId(clinic2Id).billNo("B-SALE-1")
                    .totalAmount(BigDecimal.TEN).build());

            TenantContext.set(clinicId);
            assertThatThrownBy(() -> saleService.getOrThrow(sale2.getId()))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void same_tenant_sale_loads_successfully() {
            Sale sale = saleRepository.save(Sale.builder()
                    .clinicId(clinicId).billNo("A-SALE-1")
                    .totalAmount(BigDecimal.TEN).build());

            TenantContext.set(clinicId);
            Sale loaded = saleService.getOrThrow(sale.getId());
            assertThat(loaded.getId()).isEqualTo(sale.getId());
        }
    }

    // ── SaleService.record medicine guard ────────────────────────────────

    @Nested
    class SaleRecordMedicineGuard {

        @Test
        void record_sale_with_cross_tenant_medicine_is_rejected() {
            // Create a medicine in clinic B
            Medicine med2 = medicineRepository.save(Medicine.builder()
                    .clinicId(clinic2Id).name("Clinic-B Drug")
                    .form("TABLET").unit("STRIP").lowStockThreshold(5).build());

            TenantContext.set(clinicId); // acting as clinic A
            List<SaleService.SaleLine> lines = List.of(SaleService.SaleLine.of(med2.getId(), 1));

            assertThatThrownBy(() ->
                    saleService.record(clinicId, null, null, null,
                            "CASH", "Walk-in", null, lines, null, null))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    // ── Hibernate filter: list queries respect tenant boundary ───────────

    @Nested
    class HibernateFilterBaseline {

        @Test
        void patient_list_query_excludes_other_clinic_patients() {
            TenantContext.set(clinicId);
            List<Patient> patients =
                    patientRepository.findTop200ByClinicIdOrderByUpdatedAtDesc(clinicId);

            assertThat(patients).allSatisfy(p -> assertThat(p.getClinicId()).isEqualTo(clinicId));
            assertThat(patients).extracting(Patient::getId).doesNotContain(patient2Id);
        }

        @Test
        void appointment_list_excludes_other_clinic_appointments() {
            appointmentRepository.save(Appointment.builder()
                    .clinicId(clinic2Id).patientName("Leaked")
                    .appointmentDate(LocalDate.now()).status("SCHEDULED").tokenNo(1).build());

            TenantContext.set(clinicId);
            List<Appointment> list = appointmentService.listByDate(clinicId, LocalDate.now());

            assertThat(list).allSatisfy(a -> assertThat(a.getClinicId()).isEqualTo(clinicId));
        }
    }
}
