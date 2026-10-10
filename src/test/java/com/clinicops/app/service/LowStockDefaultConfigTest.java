package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.StockBatchRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code clinic.low-stock-default} must actually drive the threshold for medicines saved without
 * one. Uses 3 (not the usual 10) so a hard-coded fallback can't pass by accident.
 */
@SpringBootTest(properties = "clinic.low-stock-default=3")
@ActiveProfiles("test")
@Transactional
class LowStockDefaultConfigTest {

    @Autowired InventoryService inventoryService;
    @Autowired AlertsService alertsService;
    @Autowired ClinicRepository clinicRepository;
    @Autowired StockBatchRepository stockBatchRepository;

    Long clinicId;

    @BeforeEach
    void setUp() {
        clinicId = clinicRepository.save(Clinic.builder()
                .code("lsd-" + System.nanoTime()).name("Low Stock Default").build()).getId();
    }

    private void stock(Medicine m, int qty) {
        stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinicId).medicineId(m.getId()).batchNumber("L-" + System.nanoTime())
                .expiryDate(LocalDate.now().plusDays(365)).quantity(qty).initialQuantity(qty).build());
    }

    @Test
    void medicine_created_without_threshold_gets_the_configured_default() {
        Medicine m = inventoryService.createMedicine(clinicId, Medicine.builder().name("Blank Threshold").build());
        assertThat(m.getLowStockThreshold()).isEqualTo(3);
    }

    @Test
    void explicit_threshold_is_kept() {
        Medicine m = inventoryService.createMedicine(clinicId,
                Medicine.builder().name("Own Threshold").lowStockThreshold(25).build());
        assertThat(m.getLowStockThreshold()).isEqualTo(25);
    }

    @Test
    void clearing_the_threshold_on_edit_falls_back_to_the_configured_default() {
        Medicine m = inventoryService.createMedicine(clinicId,
                Medicine.builder().name("Edited").lowStockThreshold(25).build());
        Medicine updated = inventoryService.updateMedicine(m.getId(),
                Medicine.builder().name("Edited").lowStockThreshold(null).build());
        assertThat(updated.getLowStockThreshold()).isEqualTo(3);
    }

    @Test
    void alerts_use_the_configured_default() {
        Medicine atDefault = inventoryService.createMedicine(clinicId, Medicine.builder().name("At Three").build());
        Medicine aboveDefault = inventoryService.createMedicine(clinicId, Medicine.builder().name("At Four").build());
        stock(atDefault, 3);
        stock(aboveDefault, 4);   // would be "low" under a hard-coded 10

        assertThat(alertsService.load(clinicId).lowStock())
                .extracting(e -> e.medicine().getName())
                .containsExactly("At Three");
    }
}
