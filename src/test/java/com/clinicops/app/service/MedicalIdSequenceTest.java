package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.integration.BaseIntegrationTest;
import com.clinicops.app.repository.VisitRepository;
import com.clinicops.app.tenant.TenantContext;
import java.time.Year;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * {@link MedicalIdSequence} is a global counter table (one row per PREFIX-YYYY key). Tests use
 * relative comparisons, or a throwaway encounter prefix when absolute numbers matter, because the
 * counter for well-known prefixes may already be seeded by other tests in the same JVM.
 */
class MedicalIdSequenceTest extends BaseIntegrationTest {

    @Autowired MedicalIdSequence sequence;
    @Autowired VisitRepository visitRepository;

    final int year = Year.now().getValue();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static int seq(String id) {
        return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
    }

    /** Unique per test so the counter row starts unseeded. */
    private static String freshPrefix() {
        return "T" + Long.toString(System.nanoTime(), 36).toUpperCase();
    }

    private void saveVisitWithEncounter(String encounterNo) {
        visitRepository.save(Visit.builder().clinicId(clinicId).patientId(patientId)
                .encounterType("OPD").encounterNo(encounterNo).build());
    }

    @Nested
    class Format {

        @Test
        void mrn_has_prefix_year_and_five_digit_zero_padded_counter() {
            assertThat(sequence.nextPatientMrn()).matches("MRN-" + year + "-\\d{5}");
        }

        @Test
        void bill_no_has_bill_prefix() {
            assertThat(sequence.nextBillNo()).matches("BILL-" + year + "-\\d{5}");
        }

        @Test
        void null_or_blank_encounter_type_defaults_to_opd() {
            assertThat(sequence.nextEncounterNo(null)).matches("OPD-" + year + "-\\d{5}");
            assertThat(sequence.nextEncounterNo("")).startsWith("OPD-");
            assertThat(sequence.nextEncounterNo("   ")).startsWith("OPD-");
        }

        @Test
        void uses_supplied_encounter_type_as_prefix() {
            assertThat(sequence.nextEncounterNo("IPD")).matches("IPD-" + year + "-\\d{5}");
        }
    }

    @Nested
    class Counting {

        @Test
        void every_call_consumes_a_number() {
            // Unlike the old COUNT(*)-based scheme, two callers that haven't saved yet can never
            // be handed the same number.
            String first = sequence.nextBillNo();
            String second = sequence.nextBillNo();
            assertThat(seq(second)).isEqualTo(seq(first) + 1);
        }

        @Test
        void a_fresh_key_starts_at_one() {
            String prefix = freshPrefix();
            assertThat(sequence.nextEncounterNo(prefix)).isEqualTo(prefix + "-" + year + "-00001");
            assertThat(sequence.nextEncounterNo(prefix)).isEqualTo(prefix + "-" + year + "-00002");
        }

        @Test
        void counters_are_independent_per_prefix() {
            String a = freshPrefix() + "A";
            String b = freshPrefix() + "B";
            sequence.nextEncounterNo(a);
            sequence.nextEncounterNo(a);
            assertThat(seq(sequence.nextEncounterNo(b))).isEqualTo(1);
        }

        @Test
        void hundred_consecutive_numbers_are_all_distinct() {
            Set<String> issued = new HashSet<>();
            for (int i = 0; i < 100; i++) issued.add(sequence.nextPatientMrn());
            assertThat(issued).hasSize(100);
        }
    }

    @Nested
    class SeedingFromExistingData {

        @Test
        void continues_after_highest_number_already_in_use() {
            // Rows that pre-date the counter (or were typed in manually) must never be re-issued.
            String prefix = freshPrefix();
            saveVisitWithEncounter(prefix + "-" + year + "-00007");
            saveVisitWithEncounter(prefix + "-" + year + "-00042");
            saveVisitWithEncounter(prefix + "-" + year + "-00003");

            assertThat(sequence.nextEncounterNo(prefix)).isEqualTo(prefix + "-" + year + "-00043");
        }

        @Test
        void ignores_previous_year_and_non_numeric_suffixes() {
            String prefix = freshPrefix();
            saveVisitWithEncounter(prefix + "-" + (year - 1) + "-00900");
            saveVisitWithEncounter(prefix + "-" + year + "-LEGACY");

            assertThat(sequence.nextEncounterNo(prefix)).isEqualTo(prefix + "-" + year + "-00001");
        }

        @Test
        void seeding_sees_rows_of_every_clinic() {
            Long clinicB = clinicRepository.save(Clinic.builder()
                    .code("seed-b-" + System.nanoTime()).name("B").build()).getId();
            String prefix = freshPrefix();
            visitRepository.save(Visit.builder().clinicId(clinicB).patientId(patientId)
                    .encounterType("OPD").encounterNo(prefix + "-" + year + "-00010").build());

            TenantContext.set(clinicId); // acting as clinic A; B's row must still count
            visitRepository.findByClinicIdAndFollowUpDate(clinicId, java.time.LocalDate.now());

            assertThat(sequence.nextEncounterNo(prefix)).isEqualTo(prefix + "-" + year + "-00011");
        }
    }

    @Nested
    class TenantFilter {

        @Test
        void mrn_is_globally_unique_even_when_tenant_filter_is_active() {
            String takenByA = sequence.nextPatientMrn();
            patientRepository.save(Patient.builder()
                    .clinicId(clinicId).name("A").phone("9000000000").mrn(takenByA).build());

            Long clinicB = clinicRepository.save(Clinic.builder()
                    .code("seq-b-" + System.nanoTime()).name("B").build()).getId();
            TenantContext.set(clinicB);
            // Any repository call activates the Hibernate tenantFilter on the session — as happens
            // in a real request before the controller creates a patient.
            patientRepository.findByClinicIdOrderByUpdatedAtDesc(clinicB, PageRequest.of(0, 1));

            assertThat(sequence.nextPatientMrn()).isNotEqualTo(takenByA);
        }
    }
}
