package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Role;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.StockMovement;
import com.clinicops.app.domain.Supplier;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.StockBatchRepository;
import com.clinicops.app.repository.StockMovementRepository;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock-side behavior of {@link InventoryService}: batch receipt, stock totals, batch
 * ordering by expiry, adjustments, expiry write-off, update / soft delete, search, and the
 * platform-catalog (clinic_id NULL) medicine flows.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryServiceStockTest {

    @Autowired InventoryService inventoryService;
    @Autowired ClinicRepository clinicRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired SupplierRepository supplierRepository;
    @PersistenceContext EntityManager em;

    Long clinicId;
    Long otherClinicId;
    Long userId; // real app_user row: stock_movement.created_by is an FK on PostgreSQL
    Medicine medicine;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        clinicId = clinicRepository.save(Clinic.builder()
                .code("stk-" + System.nanoTime()).name("Stock Clinic").build()).getId();
        otherClinicId = clinicRepository.save(Clinic.builder()
                .code("stk-o-" + System.nanoTime()).name("Other Stock Clinic").build()).getId();
        userId = appUserRepository.save(AppUser.builder().clinicId(clinicId).username("stk-user-" + System.nanoTime())
                .fullName("Pharmacist").role(Role.PHARMACIST).passwordHash("n/a").enabled(true).build()).getId();
        medicine = inventoryService.createMedicine(clinicId,
                Medicine.builder().name("Dolo").strength("650mg").manufacturer("Micro").build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StockBatch receive(Long forClinic, Long medId, String batchNo, LocalDate expiry, int qty) {
        return inventoryService.addBatch(forClinic, medId, userId, batchNo, expiry, qty,
                new BigDecimal("1.50"), new BigDecimal("2.00"), null);
    }

    private List<StockMovement> movementsFor(Long batchId) {
        return stockMovementRepository.findAll().stream()
                .filter(m -> batchId.equals(m.getStockBatchId()))
                .toList();
    }

    private Medicine platformMedicine(String name) {
        return medicineRepository.save(Medicine.builder()
                .clinicId(null).name(name).strength("500mg").manufacturer("Platform Pharma")
                .active(true).build());
    }

    @Nested
    class AddBatch {

        @Test
        void add_batch_persists_batch_with_initial_quantity_and_prices() {
            Supplier s = supplierRepository.save(Supplier.builder()
                    .clinicId(clinicId).name("Distributor").creditBalance(BigDecimal.ZERO).build());
            LocalDate expiry = LocalDate.now().plusMonths(6);

            StockBatch b = inventoryService.addBatch(clinicId, medicine.getId(), userId, "B-001",
                    expiry, 100, new BigDecimal("1.25"), new BigDecimal("2.50"), s.getId());

            assertThat(b.getId()).isNotNull();
            assertThat(b.getClinicId()).isEqualTo(clinicId);
            assertThat(b.getMedicineId()).isEqualTo(medicine.getId());
            assertThat(b.getBatchNumber()).isEqualTo("B-001");
            assertThat(b.getExpiryDate()).isEqualTo(expiry);
            assertThat(b.getQuantity()).isEqualTo(100);
            assertThat(b.getInitialQuantity()).isEqualTo(100);
            assertThat(b.getPurchasePrice()).isEqualByComparingTo("1.25");
            assertThat(b.getSalePrice()).isEqualByComparingTo("2.50");
            assertThat(b.getSupplierId()).isEqualTo(s.getId());
            assertThat(b.getReceivedDate()).isEqualTo(LocalDate.now());
        }

        @Test
        void add_batch_records_an_IN_movement_for_received_quantity() {
            StockBatch b = receive(clinicId, medicine.getId(), "B-IN", LocalDate.now().plusDays(90), 40);

            List<StockMovement> moves = movementsFor(b.getId());
            assertThat(moves).hasSize(1);
            StockMovement m = moves.get(0);
            assertThat(m.getMovementType()).isEqualTo(StockMovement.MovementType.IN);
            assertThat(m.getQuantity()).isEqualTo(40);
            assertThat(m.getReferenceType()).isEqualTo("BATCH_RECEIVE");
            assertThat(m.getClinicId()).isEqualTo(clinicId);
            assertThat(m.getCreatedBy()).isEqualTo(userId);
        }

        @Test
        void add_batch_for_unknown_medicine_throws_and_saves_nothing() {
            long before = stockBatchRepository.count();

            assertThatThrownBy(() -> receive(clinicId, -1L, "X", LocalDate.now().plusDays(10), 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Medicine not found");
            assertThat(stockBatchRepository.count()).isEqualTo(before);
        }

        @Test
        void add_batch_for_another_clinics_private_medicine_is_blocked() {
            Medicine foreign = inventoryService.createMedicine(otherClinicId,
                    Medicine.builder().name("Foreign").strength("1").manufacturer("F").build());
            em.flush();
            em.clear();

            TenantContext.set(clinicId);
            assertThatThrownBy(() -> receive(clinicId, foreign.getId(), "X", LocalDate.now().plusDays(10), 5))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void add_batch_for_platform_medicine_belongs_to_purchasing_clinic() {
            Medicine platform = platformMedicine("Paracetamol Platform");
            em.flush();
            em.clear();

            TenantContext.set(clinicId);
            StockBatch b = receive(clinicId, platform.getId(), "P-1", LocalDate.now().plusDays(200), 30);

            assertThat(b.getClinicId()).isEqualTo(clinicId);
            assertThat(b.getMedicineId()).isEqualTo(platform.getId());
            assertThat(inventoryService.totalStock(platform.getId())).isEqualTo(30);
        }
    }

    @Nested
    class TotalsAndExpiryOrder {

        @Test
        void total_stock_is_zero_when_no_batches() {
            assertThat(inventoryService.totalStock(medicine.getId())).isZero();
        }

        @Test
        void total_stock_sums_positive_batches_only() {
            receive(clinicId, medicine.getId(), "A", LocalDate.now().plusDays(30), 10);
            receive(clinicId, medicine.getId(), "B", LocalDate.now().plusDays(60), 15);
            StockBatch empty = receive(clinicId, medicine.getId(), "C", LocalDate.now().plusDays(90), 5);
            inventoryService.adjustBatch(empty.getId(), userId, 0, "count");

            assertThat(inventoryService.totalStock(medicine.getId())).isEqualTo(25);
        }

        @Test
        void batches_for_returns_earliest_expiry_first() {
            receive(clinicId, medicine.getId(), "LATE", LocalDate.now().plusDays(300), 1);
            receive(clinicId, medicine.getId(), "SOON", LocalDate.now().plusDays(10), 1);
            receive(clinicId, medicine.getId(), "MID", LocalDate.now().plusDays(100), 1);

            assertThat(inventoryService.batchesFor(medicine.getId()))
                    .extracting(StockBatch::getBatchNumber)
                    .containsExactly("SOON", "MID", "LATE");
        }

        @Test
        void platform_medicine_stock_total_counts_only_current_tenants_batches() {
            Medicine platform = platformMedicine("Shared Cetirizine");
            receive(clinicId, platform.getId(), "MINE", LocalDate.now().plusDays(100), 10);
            receive(otherClinicId, platform.getId(), "THEIRS", LocalDate.now().plusDays(100), 70);
            em.flush();
            em.clear();

            TenantContext.set(clinicId);
            assertThat(inventoryService.totalStock(platform.getId())).isEqualTo(10);
            assertThat(inventoryService.batchesFor(platform.getId()))
                    .extracting(StockBatch::getBatchNumber).containsExactly("MINE");
        }
    }

    @Nested
    class AdjustBatch {

        @Test
        void adjust_down_sets_quantity_and_records_adjustment_of_absolute_delta() {
            StockBatch b = receive(clinicId, medicine.getId(), "ADJ", LocalDate.now().plusDays(90), 20);

            inventoryService.adjustBatch(b.getId(), userId, 12, "Damaged strips");

            assertThat(stockBatchRepository.findById(b.getId()).orElseThrow().getQuantity()).isEqualTo(12);
            List<StockMovement> adj = movementsFor(b.getId()).stream()
                    .filter(m -> m.getMovementType() == StockMovement.MovementType.ADJUSTMENT).toList();
            assertThat(adj).hasSize(1);
            assertThat(adj.get(0).getQuantity()).isEqualTo(8);
            assertThat(adj.get(0).getNotes()).isEqualTo("Damaged strips");
        }

        @Test
        void adjust_up_records_positive_quantity() {
            StockBatch b = receive(clinicId, medicine.getId(), "ADJ", LocalDate.now().plusDays(90), 20);

            inventoryService.adjustBatch(b.getId(), userId, 25, "Recount");

            assertThat(movementsFor(b.getId()))
                    .filteredOn(m -> m.getMovementType() == StockMovement.MovementType.ADJUSTMENT)
                    .extracting(StockMovement::getQuantity).containsExactly(5);
        }

        @Test
        void adjust_to_same_quantity_records_no_movement() {
            StockBatch b = receive(clinicId, medicine.getId(), "ADJ", LocalDate.now().plusDays(90), 20);

            inventoryService.adjustBatch(b.getId(), userId, 20, "No change");

            assertThat(movementsFor(b.getId()))
                    .extracting(StockMovement::getMovementType)
                    .containsExactly(StockMovement.MovementType.IN);
        }

        @Test
        void adjust_unknown_batch_throws() {
            assertThatThrownBy(() -> inventoryService.adjustBatch(-1L, userId, 1, "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Batch not found");
        }

        @Test
        void adjust_other_clinics_batch_is_blocked() {
            Medicine foreign = inventoryService.createMedicine(otherClinicId,
                    Medicine.builder().name("Foreign").strength("1").manufacturer("F").build());
            StockBatch b = receive(otherClinicId, foreign.getId(), "F", LocalDate.now().plusDays(90), 20);
            em.flush();
            em.clear();

            TenantContext.set(clinicId);
            assertThatThrownBy(() -> inventoryService.adjustBatch(b.getId(), userId, 0, "steal"))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    @Nested
    class WriteOffExpired {

        @Test
        void write_off_zeroes_expired_batches_and_records_EXPIRED_movement() {
            StockBatch expired = receive(clinicId, medicine.getId(), "OLD", LocalDate.now().minusDays(1), 7);
            StockBatch fresh = receive(clinicId, medicine.getId(), "NEW", LocalDate.now().plusDays(30), 9);

            inventoryService.writeOffExpired(clinicId, userId);

            assertThat(stockBatchRepository.findById(expired.getId()).orElseThrow().getQuantity()).isZero();
            assertThat(stockBatchRepository.findById(fresh.getId()).orElseThrow().getQuantity()).isEqualTo(9);
            assertThat(movementsFor(expired.getId()))
                    .filteredOn(m -> m.getMovementType() == StockMovement.MovementType.EXPIRED)
                    .extracting(StockMovement::getQuantity).containsExactly(7);
            assertThat(movementsFor(fresh.getId()))
                    .extracting(StockMovement::getMovementType)
                    .containsExactly(StockMovement.MovementType.IN);
        }

        @Test
        void batch_expiring_today_is_not_written_off() {
            StockBatch today = receive(clinicId, medicine.getId(), "TODAY", LocalDate.now(), 4);

            inventoryService.writeOffExpired(clinicId, userId);

            assertThat(stockBatchRepository.findById(today.getId()).orElseThrow().getQuantity()).isEqualTo(4);
        }

        @Test
        void write_off_is_idempotent() {
            StockBatch expired = receive(clinicId, medicine.getId(), "OLD", LocalDate.now().minusDays(5), 7);

            inventoryService.writeOffExpired(clinicId, userId);
            inventoryService.writeOffExpired(clinicId, userId);

            assertThat(movementsFor(expired.getId()))
                    .filteredOn(m -> m.getMovementType() == StockMovement.MovementType.EXPIRED)
                    .hasSize(1);
        }

        @Test
        void write_off_does_not_touch_other_clinics_batches() {
            Medicine foreign = inventoryService.createMedicine(otherClinicId,
                    Medicine.builder().name("Foreign").strength("1").manufacturer("F").build());
            StockBatch theirs = receive(otherClinicId, foreign.getId(), "THEIRS", LocalDate.now().minusDays(3), 6);

            inventoryService.writeOffExpired(clinicId, userId);

            assertThat(stockBatchRepository.findById(theirs.getId()).orElseThrow().getQuantity()).isEqualTo(6);
        }

        @Test
        void write_off_includes_expired_batches_of_platform_catalog_medicines() {
            Medicine platform = platformMedicine("Platform Amoxicillin");
            StockBatch expired = receive(clinicId, platform.getId(), "P-OLD", LocalDate.now().minusDays(2), 11);

            inventoryService.writeOffExpired(clinicId, userId);

            assertThat(stockBatchRepository.findById(expired.getId()).orElseThrow().getQuantity()).isZero();
        }
    }

    @Nested
    class UpdateAndSoftDelete {

        @Test
        void update_medicine_copies_editable_fields() {
            Medicine changes = Medicine.builder()
                    .name("Dolo Plus").genericName("Paracetamol").strength("1000mg").form("TAB")
                    .manufacturer("Micro Labs").unit("STRIP").lowStockThreshold(25).build();

            Medicine updated = inventoryService.updateMedicine(medicine.getId(), changes);

            assertThat(updated.getName()).isEqualTo("Dolo Plus");
            assertThat(updated.getGenericName()).isEqualTo("Paracetamol");
            assertThat(updated.getStrength()).isEqualTo("1000mg");
            assertThat(updated.getForm()).isEqualTo("TAB");
            assertThat(updated.getManufacturer()).isEqualTo("Micro Labs");
            assertThat(updated.getUnit()).isEqualTo("STRIP");
            assertThat(updated.getLowStockThreshold()).isEqualTo(25);
        }

        @Test
        void update_medicine_does_not_change_clinic_or_active() {
            Medicine changes = Medicine.builder().name("X").clinicId(otherClinicId).active(false).build();

            Medicine updated = inventoryService.updateMedicine(medicine.getId(), changes);

            assertThat(updated.getClinicId()).isEqualTo(clinicId);
            assertThat(updated.getActive()).isTrue();
        }

        @Test
        void update_unknown_medicine_throws() {
            assertThatThrownBy(() -> inventoryService.updateMedicine(-1L, Medicine.builder().name("x").build()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void soft_deleted_medicine_keeps_its_batches_and_stock_history() {
            StockBatch b = receive(clinicId, medicine.getId(), "KEEP", LocalDate.now().plusDays(60), 10);

            inventoryService.softDeleteMedicine(medicine.getId());

            assertThat(medicineRepository.findById(medicine.getId())).isPresent();
            assertThat(stockBatchRepository.findById(b.getId())).isPresent();
            assertThat(inventoryService.batchesFor(medicine.getId())).hasSize(1);
            assertThat(inventoryService.listMedicines(clinicId)).extracting(Medicine::getId)
                    .doesNotContain(medicine.getId());
        }

        @Test
        void soft_delete_of_unknown_medicine_throws() {
            assertThatThrownBy(() -> inventoryService.softDeleteMedicine(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void update_keeping_its_own_name_is_not_a_duplicate() {
            Medicine updated = inventoryService.updateMedicine(medicine.getId(), Medicine.builder()
                    .name("Dolo").strength("650mg").manufacturer("Micro").lowStockThreshold(5).build());
            assertThat(updated.getLowStockThreshold()).isEqualTo(5);
        }

        @Test
        void update_into_another_medicines_identity_is_rejected() {
            Medicine other = inventoryService.createMedicine(clinicId,
                    Medicine.builder().name("Crocin").strength("500mg").manufacturer("GSK").build());

            // Case-insensitive, like the create-time check and the DB unique index.
            assertThatThrownBy(() -> inventoryService.updateMedicine(medicine.getId(), Medicine.builder()
                    .name("CROCIN").strength("500MG").manufacturer("gsk").build()))
                    .isInstanceOf(InventoryService.DuplicateMedicineException.class)
                    .satisfies(e -> assertThat(((InventoryService.DuplicateMedicineException) e).getExistingId())
                            .isEqualTo(other.getId()));
        }

        @Test
        void create_ignores_a_client_supplied_id_and_never_overwrites() {
            Medicine attempt = Medicine.builder().id(medicine.getId())
                    .name("Overwrite Attempt").strength("1mg").manufacturer("X").build();

            Medicine created = inventoryService.createMedicine(clinicId, attempt);

            assertThat(created.getId()).isNotEqualTo(medicine.getId());
            em.flush();
            em.clear();
            assertThat(medicineRepository.findById(medicine.getId()).orElseThrow().getName()).isEqualTo("Dolo");
        }
    }

    @Nested
    class PlatformMedicineIsReadOnlyForClinics {

        @Test
        void update_of_platform_medicine_is_rejected_and_row_unchanged() {
            Medicine platform = platformMedicine("Shared Azithro");
            TenantContext.set(clinicId);

            assertThatThrownBy(() -> inventoryService.updateMedicine(platform.getId(),
                    Medicine.builder().name("Hijacked").build()))
                    .isInstanceOf(InventoryService.PlatformMedicineReadOnlyException.class);
            assertThat(medicineRepository.findById(platform.getId()).orElseThrow().getName())
                    .isEqualTo("Shared Azithro");
        }

        @Test
        void soft_delete_of_platform_medicine_is_rejected_and_row_stays_active() {
            Medicine platform = platformMedicine("Shared Pantop");
            TenantContext.set(clinicId);

            assertThatThrownBy(() -> inventoryService.softDeleteMedicine(platform.getId()))
                    .isInstanceOf(InventoryService.PlatformMedicineReadOnlyException.class);
            assertThat(medicineRepository.findById(platform.getId()).orElseThrow().getActive()).isTrue();
        }

        @Test
        void platform_medicine_is_still_readable_and_stockable() {
            Medicine platform = platformMedicine("Shared Cough Syrup");
            TenantContext.set(clinicId);

            assertThat(inventoryService.getMedicineOrThrow(platform.getId()).getName()).isEqualTo("Shared Cough Syrup");
            StockBatch b = receive(clinicId, platform.getId(), "PS-1", LocalDate.now().plusDays(100), 5);
            assertThat(b.getClinicId()).isEqualTo(clinicId);
        }
    }

    @Nested
    class ListingAndSearch {

        @Test
        // NB: the query orders platform rows NULLS FIRST; H2 (test DB) does not honour that the
        // same way PostgreSQL does, so only membership is asserted here.
        // On PostgreSQL the migrations seed a platform catalog, so assertions are scoped to the
        // rows this test created (own-clinic rows + uniquely named platform row) and every
        // returned row must be platform or own-clinic.
        void list_medicines_shows_platform_catalog_and_own_private_only() {
            String zinc = "Zinc Platform " + System.nanoTime();
            platformMedicine(zinc);
            inventoryService.createMedicine(otherClinicId,
                    Medicine.builder().name("Other Private").strength("1").manufacturer("O").build());
            inventoryService.createMedicine(clinicId,
                    Medicine.builder().name("Amlo").strength("5").manufacturer("A").build());

            List<Medicine> listed = inventoryService.listMedicines(clinicId);

            assertThat(listed).allSatisfy(m -> assertThat(m.getClinicId()).isIn(null, clinicId));
            assertThat(listed)
                    .filteredOn(m -> clinicId.equals(m.getClinicId()) || zinc.equals(m.getName()))
                    .extracting(Medicine::getName)
                    .containsExactlyInAnyOrder(zinc, "Amlo", "Dolo");
            assertThat(listed).extracting(Medicine::getName).doesNotContain("Other Private");
        }

        @Test
        void search_with_blank_query_returns_all_visible() {
            String zinc = "Zinc Platform " + System.nanoTime();
            platformMedicine(zinc);
            inventoryService.createMedicine(otherClinicId,
                    Medicine.builder().name("Other Private").strength("1").manufacturer("O").build());

            Page<Medicine> page = inventoryService.search(clinicId, "  ", 0);

            // Blank query == everything visible to the clinic (platform catalog + own private).
            assertThat(page.getContent()).extracting(Medicine::getId)
                    .containsExactlyInAnyOrderElementsOf(
                            inventoryService.listMedicines(clinicId).stream().map(Medicine::getId).toList());
            assertThat(page.getContent()).allSatisfy(m -> assertThat(m.getClinicId()).isIn(null, clinicId));
            assertThat(page.getContent())
                    .filteredOn(m -> clinicId.equals(m.getClinicId()) || zinc.equals(m.getName()))
                    .extracting(Medicine::getName)
                    .containsExactlyInAnyOrder(zinc, "Dolo");
        }

        @Test
        void search_matches_name_or_generic_name_case_insensitively() {
            // Unique token so the generic-name match cannot hit the seeded platform catalog
            // (which contains real "Paracetamol" rows on PostgreSQL).
            String tok = "zq" + System.nanoTime();
            inventoryService.createMedicine(clinicId, Medicine.builder()
                    .name("Calpol").genericName("Paracetamol-" + tok).strength("500").manufacturer("GSK").build());
            inventoryService.createMedicine(otherClinicId, Medicine.builder()
                    .name("Paracip").genericName("Paracetamol-" + tok).strength("500").manufacturer("Cipla").build());

            assertThat(inventoryService.search(clinicId, " " + ("ETAMOL-" + tok).toUpperCase() + " ", 0).getContent())
                    .extracting(Medicine::getName).containsExactly("Calpol");

            // "dol" may also match seeded platform rows (e.g. Tramadol); every hit must genuinely
            // match and be visible, and among this clinic's own rows only "Dolo" matches.
            List<Medicine> dol = inventoryService.search(clinicId, "dol", 0).getContent();
            assertThat(dol).allSatisfy(m -> {
                assertThat(m.getClinicId()).isIn(null, clinicId);
                String hay = (m.getName() + " " + (m.getGenericName() == null ? "" : m.getGenericName())).toLowerCase();
                assertThat(hay).contains("dol");
            });
            assertThat(dol).filteredOn(m -> m.getClinicId() != null)
                    .extracting(Medicine::getName).containsExactly("Dolo");
        }

        @Test
        void search_excludes_soft_deleted_medicines() {
            inventoryService.softDeleteMedicine(medicine.getId());

            assertThat(inventoryService.search(clinicId, "Dolo", 0).getContent()).isEmpty();
        }

        @Test
        void suppliers_are_scoped_to_clinic_and_sorted_by_name() {
            supplierRepository.save(Supplier.builder().clinicId(clinicId).name("Zeta")
                    .creditBalance(BigDecimal.ZERO).build());
            supplierRepository.save(Supplier.builder().clinicId(clinicId).name("Alpha")
                    .creditBalance(BigDecimal.ZERO).build());
            supplierRepository.save(Supplier.builder().clinicId(otherClinicId).name("Other")
                    .creditBalance(BigDecimal.ZERO).build());

            assertThat(inventoryService.suppliers(clinicId)).extracting(Supplier::getName)
                    .containsExactly("Alpha", "Zeta");
        }

        @Test
        void platform_medicine_is_readable_by_any_tenant() {
            Medicine platform = platformMedicine("Shared ORS");
            em.flush();
            em.clear();

            TenantContext.set(otherClinicId);
            assertThat(inventoryService.getMedicineOrThrow(platform.getId()).getName()).isEqualTo("Shared ORS");
        }

        @Test
        void duplicate_check_does_not_collide_with_platform_medicine_of_same_name() {
            platformMedicine("Crocin");

            Medicine priv = inventoryService.createMedicine(clinicId, Medicine.builder()
                    .name("Crocin").strength("500mg").manufacturer("Platform Pharma").build());

            assertThat(priv.getClinicId()).isEqualTo(clinicId);
        }
    }
}
