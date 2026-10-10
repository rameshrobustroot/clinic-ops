package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.integration.BaseIntegrationTest;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import java.time.Instant;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

class PatientServiceTest extends BaseIntegrationTest {

    @Autowired PatientService patientService;

    Long otherClinicId;

    @BeforeEach
    void setUpOtherClinic() {
        otherClinicId = clinicRepository.save(Clinic.builder()
                .code("pat-other-" + System.nanoTime()).name("Other").build()).getId();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Patient newPatient(String name, String phone) {
        return Patient.builder().name(name).phone(phone).build();
    }

    @Nested
    class Search {

        @Test
        void blank_or_null_query_lists_clinic_patients_newest_first() {
            Patient older = patientRepository.save(Patient.builder().clinicId(clinicId)
                    .name("Older").phone("9000000010").mrn("MRN-S-OLD")
                    .updatedAt(Instant.now().minus(2, ChronoUnit.DAYS)).build());
            Patient newer = patientRepository.save(Patient.builder().clinicId(clinicId)
                    .name("Newer").phone("9000000011").mrn("MRN-S-NEW")
                    .updatedAt(Instant.now().plus(1, ChronoUnit.HOURS)).build());
            patientRepository.save(Patient.builder().clinicId(otherClinicId)
                    .name("Foreign").phone("9000000012").mrn("MRN-S-FOR").build());

            for (String q : new String[]{null, "", "   "}) {
                Page<Patient> page = patientService.search(clinicId, q, 0);
                // base fixture patient + the two above; other clinic excluded
                assertThat(page.getTotalElements()).as("query=%s", q).isEqualTo(3);
                assertThat(page.getContent().get(0).getId()).isEqualTo(newer.getId());
                assertThat(page.getContent().get(2).getId()).isEqualTo(older.getId());
            }
        }

        @Test
        void pages_are_twenty_rows() {
            for (int i = 0; i < 24; i++) {
                patientRepository.save(Patient.builder().clinicId(clinicId)
                        .name("P" + i).phone("90000001" + String.format("%02d", i)).mrn("MRN-PG-" + i).build());
            }
            // 24 + 1 fixture patient = 25 → pages of 20 and 5
            assertThat(patientService.search(clinicId, null, 0).getContent()).hasSize(20);
            assertThat(patientService.search(clinicId, null, 1).getContent()).hasSize(5);
            assertThat(patientService.search(clinicId, null, 0).getTotalPages()).isEqualTo(2);
        }

        @Test
        void query_matches_name_case_insensitively_after_trimming() {
            patientRepository.save(Patient.builder().clinicId(clinicId)
                    .name("Ramesh Kumar").phone("9123456789").mrn("MRN-Q-1").build());

            assertThat(patientService.search(clinicId, "  ramesh  ", 0).getContent())
                    .extracting(Patient::getName).containsExactly("Ramesh Kumar");
        }

        @Test
        void query_matches_partial_phone_and_mrn() {
            patientRepository.save(Patient.builder().clinicId(clinicId)
                    .name("Asha").phone("9123456789").mrn("MRN-QX-77").build());

            assertThat(patientService.search(clinicId, "34567", 0).getContent())
                    .extracting(Patient::getName).containsExactly("Asha");
            assertThat(patientService.search(clinicId, "mrn-qx", 0).getContent())
                    .extracting(Patient::getName).containsExactly("Asha");
        }

        @Test
        void query_never_returns_other_clinic_patients() {
            patientRepository.save(Patient.builder().clinicId(otherClinicId)
                    .name("Zed Foreign").phone("9111111111").mrn("MRN-ZF").build());
            assertThat(patientService.search(clinicId, "Zed", 0).getContent()).isEmpty();
        }
    }

    @Nested
    class Create {

        @Test
        void assigns_mrn_when_missing() {
            Patient saved = patientService.create(clinicId, newPatient("New", "9000000020"));
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getMrn()).matches("MRN-" + Year.now().getValue() + "-\\d{5}");
        }

        @Test
        void assigns_mrn_when_blank() {
            Patient p = newPatient("New", "9000000021");
            p.setMrn("   ");
            assertThat(patientService.create(clinicId, p).getMrn()).startsWith("MRN-");
        }

        @Test
        void keeps_supplied_mrn() {
            Patient p = newPatient("Legacy", "9000000022");
            p.setMrn("OLD-REG-123");
            assertThat(patientService.create(clinicId, p).getMrn()).isEqualTo("OLD-REG-123");
        }

        @Test
        void forces_clinic_id_to_the_callers_clinic() {
            Patient p = newPatient("Smuggled", "9000000023");
            p.setClinicId(otherClinicId);
            assertThat(patientService.create(clinicId, p).getClinicId()).isEqualTo(clinicId);
        }

        @Test
        void consecutive_patients_get_distinct_increasing_mrns() {
            String a = patientService.create(clinicId, newPatient("A", "9000000024")).getMrn();
            String b = patientService.create(clinicId, newPatient("B", "9000000025")).getMrn();
            assertThat(Integer.parseInt(b.substring(b.lastIndexOf('-') + 1)))
                    .isEqualTo(Integer.parseInt(a.substring(a.lastIndexOf('-') + 1)) + 1);
        }
    }

    @Nested
    class Update {

        @Test
        void copies_only_editable_fields() {
            Patient existing = patientRepository.findById(patientId).orElseThrow();
            String mrn = existing.getMrn();
            Instant createdAt = existing.getCreatedAt();

            Patient updates = Patient.builder()
                    .name("Renamed").phone("9888888888").age(42).gender("F")
                    .address("12 Main St").allergies("Penicillin").notes("VIP")
                    // these must be ignored:
                    .mrn("HACKED").clinicId(otherClinicId).createdAt(Instant.EPOCH)
                    .build();

            Patient saved = patientService.update(patientId, updates);

            assertThat(saved.getName()).isEqualTo("Renamed");
            assertThat(saved.getPhone()).isEqualTo("9888888888");
            assertThat(saved.getAge()).isEqualTo(42);
            assertThat(saved.getGender()).isEqualTo("F");
            assertThat(saved.getAddress()).isEqualTo("12 Main St");
            assertThat(saved.getAllergies()).isEqualTo("Penicillin");
            assertThat(saved.getNotes()).isEqualTo("VIP");
            assertThat(saved.getMrn()).isEqualTo(mrn);
            assertThat(saved.getClinicId()).isEqualTo(clinicId);
            assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
        }

        @Test
        void null_optional_fields_in_updates_clear_existing_values() {
            Patient p = patientRepository.findById(patientId).orElseThrow();
            p.setAllergies("Sulfa");
            patientRepository.save(p);

            Patient saved = patientService.update(patientId, newPatient("Same", "9876543210"));
            assertThat(saved.getAllergies()).isNull();
        }

        @Test
        void unknown_patient_is_rejected() {
            assertThatThrownBy(() -> patientService.update(-5L, newPatient("x", "9000000000")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Patient not found: -5");
        }
    }

    @Nested
    class GetOrThrow {

        @Test
        void returns_own_patient() {
            TenantContext.set(clinicId);
            assertThat(patientService.getOrThrow(patientId).getName()).isEqualTo("Integration Patient");
        }

        @Test
        void unknown_id_throws_not_found() {
            assertThatThrownBy(() -> patientService.getOrThrow(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Patient not found: -1");
        }

        @Test
        void other_clinic_patient_is_rejected() {
            TenantContext.set(otherClinicId);
            assertThatThrownBy(() -> patientService.getOrThrow(patientId))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void cross_tenant_update_is_rejected() {
            TenantContext.set(otherClinicId);
            assertThatThrownBy(() -> patientService.update(patientId, newPatient("x", "9000000000")))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }
}
