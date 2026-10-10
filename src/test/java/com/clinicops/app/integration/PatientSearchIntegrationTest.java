package com.clinicops.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.Patient;
import com.clinicops.app.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

/**
 * Integration tests for the server-side patient search covering all three fields:
 * name, phone, and MRN (patient ID).
 *
 * <p>Verifies that {@link PatientService#search} correctly matches partial strings
 * across all three columns, and that results are scoped to the current clinic.
 */
class PatientSearchIntegrationTest extends BaseIntegrationTest {

    @Autowired PatientService patientService;

    Patient priya;
    Patient arjun;

    @BeforeEach
    void setUpPatients() {
        priya = patientRepository.save(Patient.builder()
                .clinicId(clinicId)
                .name("Priya Sharma")
                .phone("9876500001")
                .mrn("P-2026-00042")
                .build());

        arjun = patientRepository.save(Patient.builder()
                .clinicId(clinicId)
                .name("Arjun Reddy")
                .phone("9811122233")
                .mrn("P-2026-00099")
                .build());
    }

    // ── Search by name ────────────────────────────────────────────────────────

    @Test
    void search_by_full_name_returns_matching_patient() {
        Page<Patient> results = patientService.search(clinicId, "Priya Sharma", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(priya.getId());
    }

    @Test
    void search_by_partial_name_returns_matching_patient() {
        Page<Patient> results = patientService.search(clinicId, "arjun", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(arjun.getId());
    }

    @Test
    void search_by_name_is_case_insensitive() {
        Page<Patient> results = patientService.search(clinicId, "PRIYA", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(priya.getId());
    }

    // ── Search by phone ───────────────────────────────────────────────────────

    @Test
    void search_by_full_phone_returns_matching_patient() {
        Page<Patient> results = patientService.search(clinicId, "9811122233", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(arjun.getId());
    }

    @Test
    void search_by_partial_phone_returns_matching_patient() {
        // "500001" is unique to priya's phone (9876500001); the base fixture uses 9876543210
        Page<Patient> results = patientService.search(clinicId, "500001", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(priya.getId());
    }

    // ── Search by MRN / patient ID ────────────────────────────────────────────

    @Test
    void search_by_full_mrn_returns_matching_patient() {
        Page<Patient> results = patientService.search(clinicId, "P-2026-00042", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(priya.getId());
    }

    @Test
    void search_by_partial_mrn_returns_matching_patient() {
        Page<Patient> results = patientService.search(clinicId, "00099", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(arjun.getId());
    }

    @Test
    void search_by_mrn_is_case_insensitive() {
        Page<Patient> results = patientService.search(clinicId, "p-2026-00042", 0);
        assertThat(results.getContent()).extracting(Patient::getId).containsExactly(priya.getId());
    }

    @Test
    void search_by_mrn_prefix_returns_all_matching_patients() {
        Page<Patient> results = patientService.search(clinicId, "P-2026", 0);
        assertThat(results.getContent()).extracting(Patient::getId)
                .containsExactlyInAnyOrder(priya.getId(), arjun.getId());
    }

    // ── No match ─────────────────────────────────────────────────────────────

    @Test
    void search_with_no_match_returns_empty_page() {
        Page<Patient> results = patientService.search(clinicId, "ZZZ-NOTFOUND", 0);
        assertThat(results.getContent()).isEmpty();
    }

    // ── Tenant isolation ──────────────────────────────────────────────────────

    @Test
    void search_does_not_return_patients_from_other_clinic() {
        // BaseIntegrationTest creates patientId in clinicId.
        // This test creates a different clinic's patient and verifies it is invisible.
        com.clinicops.app.domain.Clinic otherClinic = clinicRepository.save(
                com.clinicops.app.domain.Clinic.builder()
                        .code("other-" + System.nanoTime())
                        .name("Other Clinic")
                        .build());
        patientRepository.save(Patient.builder()
                .clinicId(otherClinic.getId())
                .name("Priya Sharma")   // same name — must not appear in clinic A's results
                .phone("9876500001")
                .mrn("P-9999-00001")
                .build());

        Page<Patient> results = patientService.search(clinicId, "Priya", 0);
        assertThat(results.getContent())
                .extracting(Patient::getClinicId)
                .containsOnly(clinicId);
    }
}
