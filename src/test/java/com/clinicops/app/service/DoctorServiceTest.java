package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Doctor;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.repository.MedicalDegreeRepository;
import com.clinicops.app.repository.MedicalSpecializationRepository;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DoctorServiceTest {

    @Autowired DoctorService doctorService;
    @Autowired DoctorRepository doctorRepository;
    @Autowired ClinicRepository clinicRepository;
    @Autowired MedicalDegreeRepository degreeRepository;
    @Autowired MedicalSpecializationRepository specRepository;
    @PersistenceContext EntityManager em;

    Long clinicId;
    Long otherClinicId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        clinicId = clinicRepository.save(Clinic.builder()
                .code("doc-svc-" + System.nanoTime()).name("Doc Svc").build()).getId();
        otherClinicId = clinicRepository.save(Clinic.builder()
                .code("doc-svc-o-" + System.nanoTime()).name("Doc Svc Other").build()).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Doctor newDoctor(String name) {
        return Doctor.builder().fullName(name).build();
    }

    @Nested
    class Create {

        @Test
        void create_forces_clinic_id_active_and_new_id() {
            Doctor incoming = newDoctor("Dr. Rao");
            incoming.setId(12345L);
            incoming.setClinicId(otherClinicId);
            incoming.setActive(false);

            Doctor saved = doctorService.create(clinicId, incoming);

            assertThat(saved.getId()).isNotNull().isNotEqualTo(12345L);
            assertThat(saved.getClinicId()).isEqualTo(clinicId);
            assertThat(saved.getActive()).isTrue();
        }

        @Test
        void create_normalizes_qualification_and_specialization_into_master_lists() {
            // Unique values: on PostgreSQL the migrations seed platform rows such as "MBBS", "MD"
            // and "Cardiology", which would be reused instead of creating clinic rows.
            String u = "-" + System.nanoTime();
            Doctor incoming = newDoctor("Dr. Iyer");
            incoming.setQualification(" MBBS" + u + " ,MD" + u + " ");
            incoming.setSpecialization("  Cardiology" + u + " ");

            Doctor saved = doctorService.create(clinicId, incoming);

            assertThat(saved.getQualification()).isEqualTo("MBBS" + u + ", MD" + u);
            assertThat(saved.getSpecialization()).isEqualTo("Cardiology" + u);
            assertThat(degreeRepository.findClinicByNameIgnoreCase(clinicId, "MBBS" + u)).isPresent();
            assertThat(degreeRepository.findClinicByNameIgnoreCase(clinicId, "md" + u)).isPresent();
            assertThat(specRepository.findClinicByNameIgnoreCase(clinicId, "cardiology" + u)).isPresent();
        }

        @Test
        void create_with_blank_qualification_stores_null() {
            Doctor incoming = newDoctor("Dr. Blank");
            incoming.setQualification("   ");

            Doctor saved = doctorService.create(clinicId, incoming);

            assertThat(saved.getQualification()).isNull();
            assertThat(saved.getSpecialization()).isNull();
        }
    }

    @Nested
    class Update {

        @Test
        void update_copies_editable_fields_and_normalizes() {
            Doctor d = doctorService.create(clinicId, newDoctor("Dr. Old"));

            Doctor changes = Doctor.builder()
                    .fullName("Dr. New")
                    .qualification("BDS, MDS")
                    .registrationNo("REG-1")
                    .specialization(" Orthodontics ")
                    .phone("9876543210")
                    .email("dr@example.com")
                    .build();
            Doctor updated = doctorService.update(d.getId(), changes);

            assertThat(updated.getFullName()).isEqualTo("Dr. New");
            assertThat(updated.getQualification()).isEqualTo("BDS, MDS");
            assertThat(updated.getRegistrationNo()).isEqualTo("REG-1");
            assertThat(updated.getSpecialization()).isEqualTo("Orthodontics");
            assertThat(updated.getPhone()).isEqualTo("9876543210");
            assertThat(updated.getEmail()).isEqualTo("dr@example.com");
        }

        @Test
        void update_does_not_change_clinic_or_active_flag() {
            Doctor d = doctorService.create(clinicId, newDoctor("Dr. Keep"));

            Doctor changes = newDoctor("Dr. Keep 2");
            changes.setClinicId(otherClinicId);
            changes.setActive(false);
            Doctor updated = doctorService.update(d.getId(), changes);

            assertThat(updated.getClinicId()).isEqualTo(clinicId);
            assertThat(updated.getActive()).isTrue();
        }

        @Test
        void update_unknown_doctor_throws() {
            assertThatThrownBy(() -> doctorService.update(-1L, newDoctor("x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Doctor not found");
        }
    }

    @Nested
    class SoftDeleteAndLists {

        @Test
        void soft_delete_keeps_row_but_hides_from_active_list() {
            Doctor a = doctorService.create(clinicId, newDoctor("Dr. A"));
            Doctor b = doctorService.create(clinicId, newDoctor("Dr. B"));

            doctorService.softDelete(a.getId());

            assertThat(doctorRepository.findById(a.getId())).isPresent();
            assertThat(doctorService.listActive(clinicId)).extracting(Doctor::getId)
                    .containsExactly(b.getId());
            assertThat(doctorService.listAll(clinicId)).extracting(Doctor::getId)
                    .containsExactly(a.getId(), b.getId());
        }

        @Test
        void restore_reactivates_doctor() {
            Doctor a = doctorService.create(clinicId, newDoctor("Dr. A"));
            doctorService.softDelete(a.getId());

            doctorService.restore(a.getId());

            assertThat(doctorService.listActive(clinicId)).extracting(Doctor::getId)
                    .containsExactly(a.getId());
        }

        @Test
        void lists_are_sorted_by_name_and_scoped_to_clinic() {
            doctorService.create(clinicId, newDoctor("Dr. Zed"));
            doctorService.create(clinicId, newDoctor("Dr. Amy"));
            doctorService.create(otherClinicId, newDoctor("Dr. Other"));

            assertThat(doctorService.listAll(clinicId)).extracting(Doctor::getFullName)
                    .containsExactly("Dr. Amy", "Dr. Zed");
        }
    }

    @Nested
    class TenantGuarding {

        @Test
        void get_or_throw_blocks_doctor_of_another_clinic() {
            Doctor foreign = doctorService.create(otherClinicId, newDoctor("Dr. Foreign"));
            em.flush();
            em.clear();

            TenantContext.set(clinicId);
            assertThatThrownBy(() -> doctorService.getOrThrow(foreign.getId()))
                    .isInstanceOf(CrossTenantAccessException.class);
        }

        @Test
        void get_or_throw_returns_own_clinic_doctor() {
            Doctor mine = doctorService.create(clinicId, newDoctor("Dr. Mine"));
            em.flush();
            em.clear();

            TenantContext.set(clinicId);
            assertThat(doctorService.getOrThrow(mine.getId()).getFullName()).isEqualTo("Dr. Mine");
        }
    }
}
