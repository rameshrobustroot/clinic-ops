package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.MedicineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryServiceTest {

    @Autowired InventoryService inventoryService;
    @Autowired MedicineRepository medicineRepository;
    @Autowired ClinicRepository clinicRepository;

    Long clinicId;

    @BeforeEach
    void setUp() {
        Clinic c = clinicRepository.save(
                Clinic.builder().code("inv-test").name("Inv Test Clinic").build());
        clinicId = c.getId();
    }

    @Test
    void createMedicine_succeeds_for_unique_record() {
        Medicine m = Medicine.builder().name("Crocin").strength("500mg").manufacturer("GSK").build();
        Medicine saved = inventoryService.createMedicine(clinicId, m);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    void createMedicine_rejects_duplicate_same_name_strength_manufacturer() {
        inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Pan").strength("40").manufacturer("Alkem").build());

        // Same (clinic, name, strength, manufacturer) — should reject
        assertThatThrownBy(() -> inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Pan").strength("40").manufacturer("Alkem").build()))
                .isInstanceOf(InventoryService.DuplicateMedicineException.class);
    }

    @Test
    void createMedicine_treats_case_insensitively_for_duplicate_check() {
        inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Pan").strength("40").manufacturer("Alkem").build());

        assertThatThrownBy(() -> inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("pan").strength("40").manufacturer("ALKEM").build()))
                .isInstanceOf(InventoryService.DuplicateMedicineException.class);
    }

    @Test
    void different_strength_or_manufacturer_is_allowed() {
        inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Pan").strength("40").manufacturer("Alkem").build());

        // Different strength → allowed
        Medicine pan20 = inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Pan").strength("20").manufacturer("Alkem").build());
        assertThat(pan20.getId()).isNotNull();

        // Different manufacturer → allowed
        Medicine panCipla = inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Pan").strength("40").manufacturer("Cipla").build());
        assertThat(panCipla.getId()).isNotNull();
    }

    @Test
    void softDeleteMedicine_sets_active_false_keeps_row() {
        Medicine m = inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Crocin").strength("500mg").manufacturer("GSK").build());

        inventoryService.softDeleteMedicine(m.getId());

        // Row still exists in DB
        Medicine reloaded = medicineRepository.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getActive()).isFalse();
        // ... but doesn't appear in active list
        assertThat(medicineRepository.findByClinicIdAndActiveTrueOrderByNameAsc(clinicId))
                .extracting(Medicine::getId)
                .doesNotContain(m.getId());
    }

    @Test
    void re_adding_after_soft_delete_is_allowed() {
        Medicine m = inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Crocin").strength("500mg").manufacturer("GSK").build());
        inventoryService.softDeleteMedicine(m.getId());

        // Same (name, strength, manufacturer) should be re-addable now
        Medicine readded = inventoryService.createMedicine(clinicId, Medicine.builder()
                .name("Crocin").strength("500mg").manufacturer("GSK").build());

        assertThat(readded.getId()).isNotNull();
        assertThat(readded.getId()).isNotEqualTo(m.getId());
    }
}
