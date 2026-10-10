package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.MedicalDegree;
import com.clinicops.app.domain.MedicalInvestigation;
import com.clinicops.app.domain.MedicalSpecialization;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.MedicalDegreeRepository;
import com.clinicops.app.repository.MedicalInvestigationRepository;
import com.clinicops.app.repository.MedicalSpecializationRepository;
import com.clinicops.app.tenant.TenantContext;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master lists (degrees, specializations, investigations): platform rows
 * ({@code clinicId == null}) vs clinic-specific rows, case-insensitive de-duplication,
 * soft delete / restore and the comma-separated normalization helpers.
 *
 * <p>On PostgreSQL the Liquibase migrations seed a platform catalog (MBBS, MD, CBC, ...),
 * so every name used here carries a per-test unique suffix ({@link #n(String)}) and list
 * assertions are scoped to the rows this test created ({@link #mine}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MedicalMasterServiceTest {

    @Autowired MedicalMasterService service;
    @Autowired ClinicRepository clinicRepository;
    @Autowired MedicalDegreeRepository degreeRepository;
    @Autowired MedicalSpecializationRepository specRepository;
    @Autowired MedicalInvestigationRepository investigationRepository;

    Long clinicA;
    Long clinicB;
    /** Per-test unique suffix so names never collide with seeded platform rows. */
    String u;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        u = "-" + System.nanoTime();
        clinicA = clinicRepository.save(Clinic.builder()
                .code("mm-a-" + System.nanoTime()).name("Master A").build()).getId();
        clinicB = clinicRepository.save(Clinic.builder()
                .code("mm-b-" + System.nanoTime()).name("Master B").build()).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Test-unique version of a name. */
    private String n(String base) {
        return base + u;
    }

    /** Only the rows created by this test (identified by the unique suffix), order preserved. */
    private <T> List<T> mine(List<T> rows, Function<T, String> name) {
        return rows.stream().filter(r -> name.apply(r).endsWith(u)).toList();
    }

    @Nested
    class Investigations {

        private List<MedicalInvestigation> mineI(List<MedicalInvestigation> rows) {
            return mine(rows, MedicalInvestigation::getName);
        }

        @Test
        void create_clinic_investigation_trims_name_and_scopes_to_clinic() {
            MedicalInvestigation i = service.createClinicInvestigation(clinicA, "  " + n("CBC") + "  ");

            assertThat(i.getId()).isNotNull();
            assertThat(i.getName()).isEqualTo(n("CBC"));
            assertThat(i.getClinicId()).isEqualTo(clinicA);
            assertThat(i.getActive()).isTrue();
        }

        @Test
        void create_clinic_investigation_reuses_existing_clinic_row_case_insensitively() {
            MedicalInvestigation first = service.createClinicInvestigation(clinicA, n("Lipid Profile"));
            MedicalInvestigation again = service.createClinicInvestigation(clinicA, n("lipid profile"));

            assertThat(again.getId()).isEqualTo(first.getId());
            assertThat(again.getName()).isEqualTo(n("Lipid Profile"));
            assertThat(mineI(service.listAllInvestigations(clinicA))).hasSize(1);
        }

        @Test
        void create_clinic_investigation_returns_platform_row_instead_of_shadowing_it() {
            MedicalInvestigation platform = service.createPlatformInvestigation(n("ECG"));

            MedicalInvestigation result = service.createClinicInvestigation(clinicA, n("ecg"));

            assertThat(result.getId()).isEqualTo(platform.getId());
            assertThat(result.getClinicId()).isNull();
            assertThat(investigationRepository.findClinicByNameIgnoreCase(clinicA, n("ECG"))).isEmpty();
        }

        @Test
        void same_name_in_two_clinics_creates_two_independent_rows() {
            MedicalInvestigation a = service.createClinicInvestigation(clinicA, n("HbA1c"));
            MedicalInvestigation b = service.createClinicInvestigation(clinicB, n("HbA1c"));

            assertThat(a.getId()).isNotEqualTo(b.getId());
            assertThat(a.getClinicId()).isEqualTo(clinicA);
            assertThat(b.getClinicId()).isEqualTo(clinicB);
        }

        @Test
        void create_platform_investigation_is_idempotent_case_insensitively() {
            int before = service.listPlatformInvestigations().size();
            MedicalInvestigation first = service.createPlatformInvestigation(n("CT Brain"));
            MedicalInvestigation again = service.createPlatformInvestigation("  " + n("ct brain") + " ");

            assertThat(again.getId()).isEqualTo(first.getId());
            assertThat(service.listPlatformInvestigations()).hasSize(before + 1);
            assertThat(mineI(service.listPlatformInvestigations()))
                    .extracting(MedicalInvestigation::getId).containsExactly(first.getId());
        }

        @Test
        void create_platform_investigation_ignores_clinic_rows_with_same_name() {
            MedicalInvestigation clinicRow = service.createClinicInvestigation(clinicA, n("USG Abdomen"));
            MedicalInvestigation platformRow = service.createPlatformInvestigation(n("USG Abdomen"));

            assertThat(platformRow.getId()).isNotEqualTo(clinicRow.getId());
            assertThat(platformRow.getClinicId()).isNull();
        }

        @Test
        void list_available_returns_union_of_platform_and_own_clinic_but_not_other_clinic() {
            service.createPlatformInvestigation(n("CBC"));
            service.createClinicInvestigation(clinicA, n("A-only Test"));
            service.createClinicInvestigation(clinicB, n("B-only Test"));

            assertThat(mineI(service.listAllInvestigations(clinicA)))
                    .extracting(MedicalInvestigation::getName)
                    .containsExactly(n("A-only Test"), n("CBC"));
            assertThat(mineI(service.listActiveInvestigations(clinicB)))
                    .extracting(MedicalInvestigation::getName)
                    .containsExactly(n("B-only Test"), n("CBC"));
            assertThat(mineI(service.listPlatformInvestigations()))
                    .extracting(MedicalInvestigation::getName)
                    .containsExactly(n("CBC"));
            // Other clinics' rows never leak into a clinic's list; platform list has no clinic rows.
            assertThat(service.listAllInvestigations(clinicA))
                    .allSatisfy(i -> assertThat(i.getClinicId()).isIn(null, clinicA));
            assertThat(service.listPlatformInvestigations())
                    .allSatisfy(i -> assertThat(i.getClinicId()).isNull());
        }

        @Test
        void soft_delete_hides_from_active_list_but_keeps_row_in_all_list() {
            MedicalInvestigation i = service.createClinicInvestigation(clinicA, n("X-Ray Chest"));

            service.softDeleteInvestigation(i.getId());

            assertThat(investigationRepository.findById(i.getId())).isPresent()
                    .get().extracting(MedicalInvestigation::getActive).isEqualTo(false);
            assertThat(mineI(service.listActiveInvestigations(clinicA))).isEmpty();
            assertThat(mineI(service.listAllInvestigations(clinicA)))
                    .extracting(MedicalInvestigation::getId).containsExactly(i.getId());
        }

        @Test
        void restore_brings_soft_deleted_row_back_to_active_list() {
            MedicalInvestigation i = service.createClinicInvestigation(clinicA, n("X-Ray Chest"));
            service.softDeleteInvestigation(i.getId());

            service.restoreInvestigation(i.getId());

            assertThat(mineI(service.listActiveInvestigations(clinicA)))
                    .extracting(MedicalInvestigation::getId).containsExactly(i.getId());
        }

        @Test
        void rename_trims_new_name() {
            MedicalInvestigation i = service.createClinicInvestigation(clinicA, n("Urine RE"));

            MedicalInvestigation renamed = service.renameInvestigation(i.getId(), "  " + n("Urine Routine") + "  ");

            assertThat(renamed.getName()).isEqualTo(n("Urine Routine"));
        }

        @Test
        void rename_to_blank_is_rejected() {
            MedicalInvestigation i = service.createClinicInvestigation(clinicA, n("Urine RE"));

            assertThatThrownBy(() -> service.renameInvestigation(i.getId(), "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Name is required");
        }

        // Renames must fail with a friendly IllegalArgumentException (shown as "Couldn't rename: …"),
        // never reach the DB unique index and surface as a 500.

        @Test
        void rename_onto_another_clinic_row_name_is_rejected_case_insensitively() {
            service.createClinicInvestigation(clinicA, n("Lipid Profile"));
            MedicalInvestigation other = service.createClinicInvestigation(clinicA, n("Lipid"));

            assertThatThrownBy(() -> service.renameInvestigation(other.getId(), n("LIPID PROFILE")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
            assertThat(investigationRepository.findById(other.getId()).orElseThrow().getName()).isEqualTo(n("Lipid"));
        }

        @Test
        void clinic_row_cannot_be_renamed_onto_a_platform_name() {
            // Same no-shadowing rule as createClinicInvestigation.
            service.createPlatformInvestigation(n("HbA1c"));
            MedicalInvestigation mine = service.createClinicInvestigation(clinicA, n("Sugar Test"));

            assertThatThrownBy(() -> service.renameInvestigation(mine.getId(), n("hba1c")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void rename_may_reuse_a_name_held_by_another_clinic() {
            service.createClinicInvestigation(clinicB, n("Vitamin D"));
            MedicalInvestigation mine = service.createClinicInvestigation(clinicA, n("Vit D3"));

            assertThat(service.renameInvestigation(mine.getId(), n("Vitamin D")).getName()).isEqualTo(n("Vitamin D"));
        }

        @Test
        void rename_to_a_new_casing_of_its_own_name_is_allowed() {
            MedicalInvestigation i = service.createClinicInvestigation(clinicA, n("esr"));

            assertThat(service.renameInvestigation(i.getId(), n("ESR")).getName()).isEqualTo(n("ESR"));
        }

        @Test
        void platform_rename_onto_another_platform_name_is_rejected() {
            service.createPlatformInvestigation(n("Serum Creatinine"));
            MedicalInvestigation other = service.createPlatformInvestigation(n("Creatinine"));

            assertThatThrownBy(() -> service.renameInvestigation(other.getId(), n("serum creatinine")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void get_or_throw_for_unknown_id_throws_illegal_argument() {
            assertThatThrownBy(() -> service.getInvestigationOrThrow(-999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Investigation not found");
        }

        @Test
        void create_with_null_or_blank_name_is_rejected() {
            assertThatThrownBy(() -> service.createClinicInvestigation(clinicA, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.createPlatformInvestigation("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void normalize_investigation_returns_null_for_null_or_blank() {
            int before = service.listAllInvestigations(clinicA).size();

            assertThat(service.normalizeInvestigation(clinicA, null)).isNull();
            assertThat(service.normalizeInvestigation(clinicA, "   ")).isNull();

            assertThat(service.listAllInvestigations(clinicA)).hasSize(before);
            assertThat(service.listAllInvestigations(clinicA))
                    .filteredOn(i -> clinicA.equals(i.getClinicId())).isEmpty();
        }

        @Test
        void normalize_investigation_trims_and_auto_adds_clinic_row() {
            String result = service.normalizeInvestigation(clinicA, "  " + n("Serum Creatinine") + " ");

            assertThat(result).isEqualTo(n("Serum Creatinine"));
            assertThat(investigationRepository.findClinicByNameIgnoreCase(clinicA, n("serum creatinine")))
                    .isPresent();
        }

        @Test
        void normalize_investigation_does_not_add_clinic_row_when_platform_row_exists() {
            service.createPlatformInvestigation(n("TSH"));
            int before = service.listAllInvestigations(clinicA).size();

            service.normalizeInvestigation(clinicA, n("tsh"));

            assertThat(investigationRepository.findClinicByNameIgnoreCase(clinicA, n("TSH"))).isEmpty();
            assertThat(service.listAllInvestigations(clinicA)).hasSize(before);
            assertThat(mineI(service.listAllInvestigations(clinicA))).hasSize(1);
        }

        @Test
        void normalize_investigation_never_promotes_to_platform() {
            int before = service.listPlatformInvestigations().size();

            service.normalizeInvestigation(clinicA, n("Rare Panel"));

            assertThat(service.listPlatformInvestigations()).hasSize(before);
            assertThat(mineI(service.listPlatformInvestigations())).isEmpty();
            assertThat(investigationRepository.findClinicByNameIgnoreCase(clinicA, n("Rare Panel"))).isPresent();
        }
    }

    @Nested
    class Degrees {

        private List<MedicalDegree> mineD(List<MedicalDegree> rows) {
            return mine(rows, MedicalDegree::getName);
        }

        @Test
        void create_clinic_degree_returns_platform_row_when_it_exists() {
            MedicalDegree platform = service.createPlatformDegree(n("MBBS"));

            MedicalDegree result = service.createClinicDegree(clinicA, n("mbbs"));

            assertThat(result.getId()).isEqualTo(platform.getId());
            assertThat(degreeRepository.findClinicByNameIgnoreCase(clinicA, n("MBBS"))).isEmpty();
        }

        @Test
        void create_clinic_degree_dedupes_within_clinic() {
            MedicalDegree a = service.createClinicDegree(clinicA, n("DNB"));
            MedicalDegree b = service.createClinicDegree(clinicA, " " + n("dnb") + " ");

            assertThat(b.getId()).isEqualTo(a.getId());
        }

        @Test
        void normalize_qualification_returns_null_for_null_blank_or_only_commas() {
            assertThat(service.normalizeQualification(clinicA, null)).isNull();
            assertThat(service.normalizeQualification(clinicA, "  ")).isNull();
            assertThat(service.normalizeQualification(clinicA, " , ,, ")).isNull();
        }

        @Test
        void normalize_qualification_splits_trims_drops_empties_and_joins_with_comma_space() {
            String result = service.normalizeQualification(clinicA,
                    " " + n("MBBS") + " ," + n("MD") + ",, " + n("DM") + " ");

            assertThat(result).isEqualTo(n("MBBS") + ", " + n("MD") + ", " + n("DM"));
            assertThat(mineD(service.listAllDegrees(clinicA)))
                    .extracting(MedicalDegree::getName)
                    .containsExactly(n("DM"), n("MBBS"), n("MD"));
        }

        @Test
        void normalize_qualification_removes_exact_duplicates() {
            String result = service.normalizeQualification(clinicA,
                    n("MBBS") + ", " + n("MBBS") + ", " + n("MD"));

            assertThat(result).isEqualTo(n("MBBS") + ", " + n("MD"));
            assertThat(mineD(service.listAllDegrees(clinicA))).hasSize(2);
        }

        @Test
        void normalize_qualification_inserts_only_missing_values_as_clinic_rows() {
            service.createPlatformDegree(n("MBBS"));
            int platformBefore = service.listPlatformDegrees().size();

            service.normalizeQualification(clinicA, n("MBBS") + ", " + n("FRCS"));

            assertThat(service.listPlatformDegrees()).hasSize(platformBefore);
            assertThat(mineD(service.listPlatformDegrees())).extracting(MedicalDegree::getName)
                    .containsExactly(n("MBBS"));
            assertThat(degreeRepository.findClinicByNameIgnoreCase(clinicA, n("FRCS"))).isPresent();
            assertThat(degreeRepository.findClinicByNameIgnoreCase(clinicA, n("MBBS"))).isEmpty();
        }

        @Test
        void soft_delete_and_restore_degree_toggle_active_flag() {
            MedicalDegree d = service.createClinicDegree(clinicA, n("BDS"));

            service.softDeleteDegree(d.getId());
            assertThat(mineD(service.listActiveDegrees(clinicA))).isEmpty();
            assertThat(mineD(service.listAllDegrees(clinicA))).hasSize(1);

            service.restoreDegree(d.getId());
            assertThat(mineD(service.listActiveDegrees(clinicA)))
                    .extracting(MedicalDegree::getId).containsExactly(d.getId());
        }

        @Test
        void rename_degree_trims() {
            MedicalDegree d = service.createClinicDegree(clinicA, n("BAMS"));

            assertThat(service.renameDegree(d.getId(), " " + n("B.A.M.S.") + " ").getName())
                    .isEqualTo(n("B.A.M.S."));
        }

        @Test
        void rename_degree_onto_existing_name_is_rejected() {
            service.createClinicDegree(clinicA, n("BHMS"));
            MedicalDegree d = service.createClinicDegree(clinicA, n("B.H.M.S"));

            assertThatThrownBy(() -> service.renameDegree(d.getId(), n("bhms")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void get_degree_or_throw_for_unknown_id_throws() {
            assertThatThrownBy(() -> service.getDegreeOrThrow(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Degree not found");
        }

        @Test
        void platform_degree_is_readable_by_clinic_tenant() {
            MedicalDegree platform = service.createPlatformDegree(n("MS"));

            TenantContext.set(clinicA);
            assertThat(service.getDegreeOrThrow(platform.getId()).getName()).isEqualTo(n("MS"));
        }
    }

    @Nested
    class Specializations {

        private List<MedicalSpecialization> mineS(List<MedicalSpecialization> rows) {
            return mine(rows, MedicalSpecialization::getName);
        }

        @Test
        void normalize_specialization_trims_and_auto_adds_clinic_row() {
            String result = service.normalizeSpecialization(clinicA, "  " + n("Cardiology") + " ");

            assertThat(result).isEqualTo(n("Cardiology"));
            assertThat(specRepository.findClinicByNameIgnoreCase(clinicA, n("cardiology"))).isPresent();
        }

        @Test
        void normalize_specialization_returns_null_for_blank() {
            assertThat(service.normalizeSpecialization(clinicA, " ")).isNull();
            assertThat(service.normalizeSpecialization(clinicA, null)).isNull();
        }

        @Test
        void normalize_specialization_keeps_whole_string_and_does_not_split_on_comma() {
            String result = service.normalizeSpecialization(clinicA, "Paediatrics, " + n("Neonatology"));

            assertThat(result).isEqualTo("Paediatrics, " + n("Neonatology"));
            assertThat(mineS(service.listAllSpecializations(clinicA)))
                    .extracting(MedicalSpecialization::getName)
                    .containsExactly("Paediatrics, " + n("Neonatology"));
        }

        @Test
        void create_clinic_specialization_returns_platform_row_when_it_exists() {
            MedicalSpecialization platform = service.createPlatformSpecialization(n("General Medicine"));

            MedicalSpecialization result = service.createClinicSpecialization(clinicA, n("general medicine"));

            assertThat(result.getId()).isEqualTo(platform.getId());
        }

        @Test
        void create_platform_specialization_is_idempotent() {
            MedicalSpecialization a = service.createPlatformSpecialization(n("ENT"));
            MedicalSpecialization b = service.createPlatformSpecialization(n("ent"));

            assertThat(b.getId()).isEqualTo(a.getId());
        }

        @Test
        void lists_union_platform_and_own_clinic_only() {
            int platformBefore = service.listPlatformSpecializations().size();
            service.createPlatformSpecialization(n("Orthopaedics"));
            service.createClinicSpecialization(clinicA, n("Sports Medicine"));
            service.createClinicSpecialization(clinicB, n("Dermatology"));

            assertThat(mineS(service.listActiveSpecializations(clinicA)))
                    .extracting(MedicalSpecialization::getName)
                    .containsExactly(n("Orthopaedics"), n("Sports Medicine"));
            assertThat(service.listActiveSpecializations(clinicA))
                    .allSatisfy(s -> assertThat(s.getClinicId()).isIn(null, clinicA));
            assertThat(service.listPlatformSpecializations()).hasSize(platformBefore + 1);
        }

        @Test
        void soft_delete_restore_and_rename_specialization() {
            MedicalSpecialization s = service.createClinicSpecialization(clinicA, n("Psych"));

            service.softDeleteSpecialization(s.getId());
            assertThat(mineS(service.listActiveSpecializations(clinicA))).isEmpty();
            assertThat(mineS(service.listAllSpecializations(clinicA))).hasSize(1);

            service.restoreSpecialization(s.getId());
            assertThat(mineS(service.listActiveSpecializations(clinicA)))
                    .extracting(MedicalSpecialization::getId).containsExactly(s.getId());

            assertThat(service.renameSpecialization(s.getId(), " " + n("Psychiatry") + " ").getName())
                    .isEqualTo(n("Psychiatry"));
        }

        @Test
        void rename_specialization_onto_existing_name_is_rejected() {
            service.createClinicSpecialization(clinicA, n("Dermatology"));
            MedicalSpecialization s = service.createClinicSpecialization(clinicA, n("Skin"));

            assertThatThrownBy(() -> service.renameSpecialization(s.getId(), n("DERMATOLOGY")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void get_specialization_or_throw_for_unknown_id_throws() {
            assertThatThrownBy(() -> service.getSpecializationOrThrow(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Specialization not found");
        }
    }
}
