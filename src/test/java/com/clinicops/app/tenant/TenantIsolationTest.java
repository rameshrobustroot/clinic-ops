package com.clinicops.app.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Doctor;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defense-in-depth contract: a user logged into clinic A must NEVER see, read, or
 * modify clinic B's data — by any means.
 *
 * <p>Each test sets up two clinics with their own data, swaps the {@link TenantContext}
 * to clinic A, and asserts that clinic B's rows are invisible through every realistic
 * code path (findById, findAll, save with foreign clinic_id).
 *
 * <p>NB: We {@code em.flush(); em.clear();} after setup so that subsequent {@code findById}
 * calls actually hit the DB (and fire {@code @PostLoad} on the listener) rather than
 * returning cached references from the persistence context.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TenantIsolationTest {

    @Autowired ClinicRepository clinicRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired AppUserRepository userRepository;
    @PersistenceContext EntityManager em;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private record Pair<A, B>(A a, B b) {}

    private Pair<Patient, Patient> twoClinicsWithPatients() {
        TenantContext.clear();
        Clinic a = clinicRepository.save(Clinic.builder().code("clinic-a").name("Clinic A").build());
        Clinic b = clinicRepository.save(Clinic.builder().code("clinic-b").name("Clinic B").build());
        Patient pa = patientRepository.save(Patient.builder()
                .clinicId(a.getId()).name("Alice").phone("9876500001").mrn("MRN-A-1").build());
        Patient pb = patientRepository.save(Patient.builder()
                .clinicId(b.getId()).name("Bhushan").phone("9876500002").mrn("MRN-B-1").build());
        flushAndClear();
        return new Pair<>(pa, pb);
    }

    @Test
    void findById_blocks_cross_tenant_load_for_patient() {
        Pair<Patient, Patient> setup = twoClinicsWithPatients();
        Long aId = setup.a().getClinicId();
        Long bPatientId = setup.b().getId();

        TenantContext.set(aId);
        assertThatThrownBy(() -> patientRepository.findById(bPatientId).ifPresent(x -> {}))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    void findById_allows_own_tenant_load() {
        Pair<Patient, Patient> setup = twoClinicsWithPatients();
        TenantContext.set(setup.a().getClinicId());

        assertThat(patientRepository.findById(setup.a().getId()))
                .isPresent()
                .get().extracting(Patient::getName).isEqualTo("Alice");
    }

    @Test
    void save_blocks_cross_tenant_write_for_doctor() {
        TenantContext.clear();
        Clinic a = clinicRepository.save(Clinic.builder().code("doc-a").name("Doc A").build());
        Clinic b = clinicRepository.save(Clinic.builder().code("doc-b").name("Doc B").build());
        flushAndClear();

        TenantContext.set(a.getId());
        Doctor smuggled = Doctor.builder().clinicId(b.getId()).fullName("Smuggler").build();
        assertThatThrownBy(() -> {
            doctorRepository.save(smuggled);
            em.flush();
        }).isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    void update_blocks_changing_clinic_id_to_other_tenant() {
        TenantContext.clear();
        Clinic a = clinicRepository.save(Clinic.builder().code("upd-a").name("Upd A").build());
        Clinic b = clinicRepository.save(Clinic.builder().code("upd-b").name("Upd B").build());
        Medicine m = medicineRepository.save(Medicine.builder()
                .clinicId(a.getId()).name("Paracetamol").unit("TAB").build());
        flushAndClear();

        TenantContext.set(a.getId());
        Medicine loaded = medicineRepository.findById(m.getId()).orElseThrow();
        loaded.setClinicId(b.getId());
        assertThatThrownBy(() -> {
            medicineRepository.save(loaded);
            em.flush();
        }).isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    void platform_admin_sentinel_returns_zero_clinical_rows() {
        Pair<Patient, Patient> setup = twoClinicsWithPatients();
        TenantContext.set(TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL);
        assertThatThrownBy(() -> patientRepository.findById(setup.a().getId()).ifPresent(x -> {}))
                .isInstanceOf(CrossTenantAccessException.class);
        assertThatThrownBy(() -> patientRepository.findById(setup.b().getId()).ifPresent(x -> {}))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    void platform_admin_user_with_null_clinic_id_is_invisible_to_clinic_owners() {
        // Set up: a clinic with an owner, plus a PLATFORM_ADMIN sitting in no clinic.
        TenantContext.clear();
        Clinic a = clinicRepository.save(Clinic.builder().code("iso-a").name("Iso A").build());
        AppUser superadmin = userRepository.save(AppUser.builder()
                .clinicId(null)               // platform admin — no clinic
                .username("iso-superadmin")
                .passwordHash("x")
                .fullName("Platform Admin")
                .role(Role.PLATFORM_ADMIN)
                .enabled(true).build());
        flushAndClear();

        // Clinic A owner tries to load PLATFORM_ADMIN by id → must 404, not see them.
        TenantContext.set(a.getId());
        assertThatThrownBy(() -> userRepository.findById(superadmin.getId()).ifPresent(x -> {}))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    void platform_admin_can_load_their_own_user_record_via_sentinel() {
        TenantContext.clear();
        AppUser superadmin = userRepository.save(AppUser.builder()
                .clinicId(null)
                .username("iso-pa-self")
                .passwordHash("x")
                .fullName("Platform Admin Self")
                .role(Role.PLATFORM_ADMIN)
                .enabled(true).build());
        flushAndClear();

        TenantContext.set(TenantRequestFilter.PLATFORM_ADMIN_TENANT_SENTINEL);
        assertThat(userRepository.findById(superadmin.getId()))
                .isPresent()
                .get().extracting(AppUser::getUsername).isEqualTo("iso-pa-self");
    }

    @Test
    void no_tenant_context_skips_check_for_bootstrap_paths() {
        TenantContext.clear();
        Clinic a = clinicRepository.save(Clinic.builder().code("boot-a").name("Boot A").build());
        Patient p = patientRepository.save(Patient.builder()
                .clinicId(a.getId()).name("Bootstrap Patient").phone("9876500099").mrn("MRN-BOOT-1").build());
        flushAndClear();
        assertThat(patientRepository.findById(p.getId())).isPresent();
    }
}
