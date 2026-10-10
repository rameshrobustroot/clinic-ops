package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.integration.BaseIntegrationTest;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.StockBatchRepository;
import com.clinicops.app.service.AlertsService.AlertsView;
import com.clinicops.app.service.AlertsService.LowStockEntry;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Expiry buckets use application-test.yml: expiry-critical-days = 30, expiry-near-days = 90.
 * <ul>
 *   <li>expired: expiry &lt; today</li>
 *   <li>critical: today ≤ expiry ≤ today+30</li>
 *   <li>near: today+30 &lt; expiry ≤ today+90</li>
 * </ul>
 */
class AlertsServiceTest extends BaseIntegrationTest {

    @Autowired AlertsService alertsService;
    @Autowired MedicineRepository medicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired EntityManager entityManager;

    final LocalDate today = LocalDate.now();
    Medicine med;

    @BeforeEach
    void setUpMedicine() {
        med = medicine(clinicId, "Amoxicillin", 10);
    }

    private Medicine medicine(Long clinic, String name, Integer threshold) {
        return medicineRepository.save(Medicine.builder()
                .clinicId(clinic).name(name).lowStockThreshold(threshold).build());
    }

    private StockBatch batch(Medicine m, Long clinic, String no, LocalDate expiry, int qty) {
        return stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinic).medicineId(m.getId()).batchNumber(no)
                .expiryDate(expiry).quantity(qty).initialQuantity(qty).build());
    }

    @Nested
    class ExpiryBuckets {

        @Test
        void classifies_batches_at_every_boundary() {
            StockBatch expired = batch(med, clinicId, "EXP", today.minusDays(1), 5);
            StockBatch dueToday = batch(med, clinicId, "D0", today, 5);
            StockBatch d30 = batch(med, clinicId, "D30", today.plusDays(30), 5);
            StockBatch d31 = batch(med, clinicId, "D31", today.plusDays(31), 5);
            StockBatch d90 = batch(med, clinicId, "D90", today.plusDays(90), 5);
            batch(med, clinicId, "D91", today.plusDays(91), 5);   // beyond near window → not listed

            AlertsView view = alertsService.load(clinicId);

            assertThat(view.expired()).extracting(StockBatch::getId).containsExactly(expired.getId());
            // expiring today is still sellable → critical, not expired
            assertThat(view.critical()).extracting(StockBatch::getId)
                    .containsExactly(dueToday.getId(), d30.getId());
            assertThat(view.near()).extracting(StockBatch::getId)
                    .containsExactly(d31.getId(), d90.getId());
        }

        @Test
        void empty_batches_are_ignored() {
            batch(med, clinicId, "ZERO", today.minusDays(10), 0);
            AlertsView view = alertsService.load(clinicId);
            assertThat(view.expired()).isEmpty();
            assertThat(view.daysToExpiry()).isEmpty();
        }

        @Test
        void days_to_expiry_is_signed_day_difference_keyed_by_batch_id() {
            StockBatch expired = batch(med, clinicId, "EXP", today.minusDays(3), 5);
            StockBatch dueToday = batch(med, clinicId, "D0", today, 5);
            StockBatch d45 = batch(med, clinicId, "D45", today.plusDays(45), 5);
            StockBatch far = batch(med, clinicId, "FAR", today.plusDays(200), 5);

            AlertsView view = alertsService.load(clinicId);

            assertThat(view.daysToExpiry())
                    .containsEntry(expired.getId(), -3L)
                    .containsEntry(dueToday.getId(), 0L)
                    .containsEntry(d45.getId(), 45L)
                    .doesNotContainKey(far.getId());
        }

        @Test
        void other_clinic_batches_are_excluded() {
            Long other = clinicRepository.save(Clinic.builder()
                    .code("alert-other-" + System.nanoTime()).name("O").build()).getId();
            Medicine foreignMed = medicine(other, "Foreign", 10);
            batch(foreignMed, other, "F1", today.minusDays(1), 5);

            AlertsView view = alertsService.load(clinicId);
            assertThat(view.expired()).isEmpty();
        }

        @Test
        void batches_of_platform_catalog_medicines_are_included() {
            Medicine platform = medicine(null, "Catalog Paracetamol", 10);
            StockBatch b = batch(platform, clinicId, "PLAT", today.minusDays(1), 5);

            assertThat(alertsService.load(clinicId).expired())
                    .extracting(StockBatch::getId).containsExactly(b.getId());
        }
    }

    @Nested
    class LowStock {

        private LowStockEntry entryFor(AlertsView view, Medicine m) {
            return view.lowStock().stream()
                    .filter(e -> e.medicine().getId().equals(m.getId()))
                    .findFirst().orElse(null);
        }

        @Test
        void stock_equal_to_threshold_is_low() {
            batch(med, clinicId, "B1", today.plusDays(400), 6);
            batch(med, clinicId, "B2", today.plusDays(500), 4);   // 6 + 4 = 10 == threshold

            LowStockEntry e = entryFor(alertsService.load(clinicId), med);
            assertThat(e).isNotNull();
            assertThat(e.currentStock()).isEqualTo(10);
            assertThat(e.threshold()).isEqualTo(10);
        }

        @Test
        void stock_above_threshold_is_not_low() {
            batch(med, clinicId, "B1", today.plusDays(400), 11);
            assertThat(entryFor(alertsService.load(clinicId), med)).isNull();
        }

        @Test
        void medicine_without_any_batches_is_low_with_zero_stock() {
            LowStockEntry e = entryFor(alertsService.load(clinicId), med);
            assertThat(e).isNotNull();
            assertThat(e.currentStock()).isZero();
        }

        @Test
        void custom_threshold_is_respected() {
            Medicine strict = medicine(clinicId, "Insulin", 50);
            batch(strict, clinicId, "I1", today.plusDays(400), 40);

            LowStockEntry e = entryFor(alertsService.load(clinicId), strict);
            assertThat(e).isNotNull();
            assertThat(e.threshold()).isEqualTo(50);
        }

        @Test
        void null_threshold_uses_configured_default() {
            // clinic.low-stock-default is 10 in application-test.yml (see LowStockDefaultConfigTest for 3)
            Medicine m = medicine(clinicId, "NoThreshold", null);
            // @PrePersist fills 10 on insert; null it out afterwards to exercise the service default
            m.setLowStockThreshold(null);
            entityManager.flush();
            batch(m, clinicId, "N1", today.plusDays(400), 10);

            AlertsView view = alertsService.load(clinicId);
            LowStockEntry e = entryFor(view, m);
            assertThat(e).isNotNull();
            assertThat(e.threshold()).isEqualTo(10);

            batch(m, clinicId, "N2", today.plusDays(400), 1);   // 11 > 10 → no longer low
            assertThat(entryFor(alertsService.load(clinicId), m)).isNull();
        }

        @Test
        void inactive_medicines_are_not_reported() {
            med.setActive(false);
            medicineRepository.save(med);
            assertThat(entryFor(alertsService.load(clinicId), med)).isNull();
        }

        @Test
        void empty_batches_do_not_count_toward_stock() {
            batch(med, clinicId, "Z", today.plusDays(400), 0);
            batch(med, clinicId, "B", today.plusDays(400), 3);
            assertThat(entryFor(alertsService.load(clinicId), med).currentStock()).isEqualTo(3);
        }

        // The medicine list includes the whole shared catalog (hundreds of seeded rows in a real DB).
        // Only catalog medicines this clinic actually carries may raise low-stock alerts.

        @Test
        void never_stocked_catalog_medicine_is_not_reported_as_low() {
            Medicine catalog = medicine(null, "Catalog Never Stocked", 10);
            assertThat(entryFor(alertsService.load(clinicId), catalog)).isNull();
        }

        @Test
        void catalog_medicine_the_clinic_stocks_is_checked_like_its_own() {
            Medicine catalog = medicine(null, "Catalog Stocked", 10);
            batch(catalog, clinicId, "C1", today.plusDays(400), 4);

            LowStockEntry e = entryFor(alertsService.load(clinicId), catalog);
            assertThat(e).isNotNull();
            assertThat(e.currentStock()).isEqualTo(4);
        }

        @Test
        void sold_out_catalog_medicine_is_still_reported() {
            // Once carried, running out is exactly what the alert is for.
            Medicine catalog = medicine(null, "Catalog Sold Out", 10);
            batch(catalog, clinicId, "C0", today.plusDays(400), 0);

            assertThat(entryFor(alertsService.load(clinicId), catalog).currentStock()).isZero();
        }

        @Test
        void catalog_medicine_stocked_only_by_another_clinic_is_not_reported() {
            Long otherClinic = clinicRepository.save(com.clinicops.app.domain.Clinic.builder()
                    .code("alrt-o-" + System.nanoTime()).name("Other").build()).getId();
            Medicine catalog = medicine(null, "Catalog Elsewhere", 10);
            batch(catalog, otherClinic, "X1", today.plusDays(400), 2);

            assertThat(entryFor(alertsService.load(clinicId), catalog)).isNull();
        }
    }

    @Test
    void medicines_by_id_contains_active_clinic_medicines() {
        AlertsView view = alertsService.load(clinicId);
        assertThat(view.medicinesById()).containsKey(med.getId());
        assertThat(view.medicinesById().get(med.getId()).getName()).isEqualTo("Amoxicillin");
    }
}
