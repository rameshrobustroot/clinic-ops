package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.SaleItem;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.integration.BaseIntegrationTest;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.SaleItemRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.repository.StockBatchRepository;
import com.clinicops.app.repository.VisitRepository;
import com.clinicops.app.service.DashboardService.DashboardView;
import com.clinicops.app.service.DashboardService.TopMedicine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DashboardServiceTest extends BaseIntegrationTest {

    @Autowired DashboardService dashboardService;
    @Autowired VisitRepository visitRepository;
    @Autowired SaleRepository saleRepository;
    @Autowired SaleItemRepository saleItemRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;

    Long otherClinicId;
    Instant yesterday;
    Instant eightDaysAgo;

    @BeforeEach
    void setUp() {
        otherClinicId = clinicRepository.save(Clinic.builder()
                .code("dash-other-" + System.nanoTime()).name("Other").build()).getId();
        ZoneId zone = ZoneId.systemDefault();
        // Noon of the respective day keeps us clear of midnight boundaries
        yesterday = LocalDate.now().minusDays(1).atTime(12, 0).atZone(zone).toInstant();
        eightDaysAgo = LocalDate.now().minusDays(8).atTime(12, 0).atZone(zone).toInstant();
    }

    private Sale sale(Long clinic, String amount, Instant createdAt) {
        return saleRepository.save(Sale.builder()
                .clinicId(clinic).billNo("B-" + System.nanoTime())
                .totalAmount(new BigDecimal(amount)).createdAt(createdAt).build());
    }

    private void item(Sale sale, Long medicineId, int qty) {
        saleItemRepository.save(SaleItem.builder()
                .clinicId(sale.getClinicId()).saleId(sale.getId()).stockBatchId(batchFor(sale.getClinicId(), medicineId))
                .medicineId(medicineId).quantity(qty)
                .unitPrice(BigDecimal.ONE).grossAmount(BigDecimal.valueOf(qty))
                .lineTotal(BigDecimal.valueOf(qty)).build());
    }

    /** sale_item.stock_batch_id is an FK on PostgreSQL, so every item needs a real batch. */
    private Long batchFor(Long clinic, Long medicineId) {
        return stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinic).medicineId(medicineId).batchNumber("DB-" + System.nanoTime())
                .expiryDate(LocalDate.now().plusDays(365)).quantity(0).initialQuantity(100)
                .build()).getId();
    }

    private Medicine med(String name) {
        return medicineRepository.save(Medicine.builder().clinicId(clinicId).name(name).build());
    }

    @Nested
    class Counts {

        @Test
        void fresh_clinic_has_only_fixture_patient_and_zero_sales() {
            DashboardView v = dashboardService.load(clinicId);
            // BaseIntegrationTest creates one patient "now"
            assertThat(v.newPatientsToday()).isEqualTo(1);
            assertThat(v.visitsToday()).isZero();
            assertThat(v.salesCountToday()).isZero();
            assertThat(v.salesTotalToday()).isEqualByComparingTo("0");
            assertThat(v.topMedicines7d()).isEmpty();
        }

        @Test
        void counts_only_todays_patients_for_this_clinic() {
            patientRepository.save(Patient.builder().clinicId(clinicId).name("Today")
                    .phone("9000000001").mrn("MRN-D-1").build());
            patientRepository.save(Patient.builder().clinicId(clinicId).name("Old")
                    .phone("9000000002").mrn("MRN-D-2").createdAt(yesterday).build());
            patientRepository.save(Patient.builder().clinicId(otherClinicId).name("Foreign")
                    .phone("9000000003").mrn("MRN-D-3").build());

            assertThat(dashboardService.load(clinicId).newPatientsToday()).isEqualTo(2);
        }

        @Test
        void counts_only_todays_visits_for_this_clinic() {
            visitRepository.save(Visit.builder().clinicId(clinicId).patientId(patientId)
                    .encounterType("OPD").build());
            visitRepository.save(Visit.builder().clinicId(clinicId).patientId(patientId)
                    .encounterType("OPD").build());
            visitRepository.save(Visit.builder().clinicId(clinicId).patientId(patientId)
                    .encounterType("OPD").visitDate(yesterday).build());
            visitRepository.save(Visit.builder().clinicId(otherClinicId).patientId(patientId)
                    .encounterType("OPD").build());

            assertThat(dashboardService.load(clinicId).visitsToday()).isEqualTo(2);
        }

        @Test
        void sums_and_counts_only_todays_sales_for_this_clinic() {
            sale(clinicId, "100.50", null);
            sale(clinicId, "49.50", null);
            sale(clinicId, "999.00", yesterday);
            sale(otherClinicId, "777.00", null);

            DashboardView v = dashboardService.load(clinicId);
            assertThat(v.salesCountToday()).isEqualTo(2);
            assertThat(v.salesTotalToday()).isEqualByComparingTo("150.00");
        }
    }

    @Nested
    class TopMedicines {

        @Test
        void ranks_by_total_quantity_desc_and_caps_at_five() {
            Medicine[] meds = new Medicine[6];
            for (int i = 0; i < 6; i++) meds[i] = med("Med" + i);

            Sale s1 = sale(clinicId, "10", null);
            Sale s2 = sale(clinicId, "10", yesterday);
            // quantities aggregated across sales: Med0=3, Med1=7(5+2), Med2=9, Med3=1, Med4=4, Med5=6
            item(s1, meds[0].getId(), 3);
            item(s1, meds[1].getId(), 5);
            item(s2, meds[1].getId(), 2);
            item(s1, meds[2].getId(), 9);
            item(s2, meds[3].getId(), 1);
            item(s1, meds[4].getId(), 4);
            item(s2, meds[5].getId(), 6);

            assertThat(dashboardService.load(clinicId).topMedicines7d())
                    .extracting(TopMedicine::name, TopMedicine::quantitySold)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Med2", 9L),
                            org.assertj.core.groups.Tuple.tuple("Med1", 7L),
                            org.assertj.core.groups.Tuple.tuple("Med5", 6L),
                            org.assertj.core.groups.Tuple.tuple("Med4", 4L),
                            org.assertj.core.groups.Tuple.tuple("Med0", 3L));
        }

        @Test
        void excludes_sales_older_than_seven_days_and_other_clinics() {
            Medicine m = med("Recent");
            Medicine stale = med("Stale");
            item(sale(clinicId, "1", null), m.getId(), 2);
            item(sale(clinicId, "1", eightDaysAgo), stale.getId(), 50);
            item(sale(otherClinicId, "1", null), m.getId(), 100);

            assertThat(dashboardService.load(clinicId).topMedicines7d())
                    .singleElement()
                    .satisfies(t -> {
                        assertThat(t.medicineId()).isEqualTo(m.getId());
                        assertThat(t.quantitySold()).isEqualTo(2);
                    });
        }

        @Test
        void medicine_not_visible_to_this_clinic_falls_back_to_placeholder_name() {
            // sale_item.medicine_id is an FK, so the row exists — but the tenant filter hides
            // another clinic's private medicine from findAllById. The name lookup must not leak it.
            Medicine foreign = medicineRepository.save(Medicine.builder()
                    .clinicId(otherClinicId).name("Foreign Secret Drug").build());
            item(sale(clinicId, "1", null), foreign.getId(), 3);

            com.clinicops.app.tenant.TenantContext.set(clinicId);
            try {
                assertThat(dashboardService.load(clinicId).topMedicines7d())
                        .singleElement()
                        .satisfies(t -> {
                            assertThat(t.medicineId()).isEqualTo(foreign.getId());
                            assertThat(t.name()).isEqualTo("Medicine " + foreign.getId());
                        });
            } finally {
                com.clinicops.app.tenant.TenantContext.clear();
            }
        }
    }
}
