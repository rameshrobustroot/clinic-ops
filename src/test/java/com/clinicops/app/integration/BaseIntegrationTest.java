package com.clinicops.app.integration;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Doctor;
import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.DoctorWorkingHours;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Role;
import com.clinicops.app.domain.Supplier;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.repository.DoctorScheduleConfigRepository;
import com.clinicops.app.repository.DoctorWorkingHoursRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abstract base for Spring Boot integration tests.
 *
 * <p>Loads the full application context against an H2 in-memory database (test profile).
 * Creates a fresh clinic and doctor before each test method. All mutations are rolled
 * back at the end of each test by {@code @Transactional}.
 *
 * <p>Subclasses may call the helper factory methods ({@link #createSlotBasedConfig},
 * {@link #addWorkingHours}, …) in their own {@code @BeforeEach} methods to build the
 * exact fixture they need. JUnit 5 runs the <em>superclass</em> {@code @BeforeEach}
 * before the subclass one, so the clinic and doctor are always available.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

    @Autowired protected ClinicRepository clinicRepository;
    @Autowired protected DoctorRepository doctorRepository;
    @Autowired protected DoctorScheduleConfigRepository scheduleConfigRepository;
    @Autowired protected DoctorWorkingHoursRepository workingHoursRepository;
    @Autowired protected PatientRepository patientRepository;
    @Autowired protected SupplierRepository supplierRepository;
    @Autowired protected AppUserRepository appUserRepository;

    protected Long clinicId;
    protected Long doctorId;
    protected Long patientId;
    /** A real app_user in the test clinic - created_by columns are FKs to app_user on PostgreSQL. */
    protected Long userId;

    @BeforeEach
    void baseSetUp() {
        Clinic clinic = clinicRepository.save(
                Clinic.builder()
                        .code("test-" + System.nanoTime())
                        .name("Test Clinic")
                        .build());
        clinicId = clinic.getId();

        Doctor doctor = doctorRepository.save(
                Doctor.builder()
                        .clinicId(clinicId)
                        .fullName("Dr. Integration Test")
                        .active(true)
                        .build());
        doctorId = doctor.getId();

        Patient patient = patientRepository.save(
                Patient.builder()
                        .clinicId(clinicId)
                        .name("Integration Patient")
                        .phone("9876543210")
                        .mrn("MRN-IT-1")
                        .build());
        patientId = patient.getId();

        userId = appUserRepository.save(AppUser.builder()
                .clinicId(clinicId)
                .username("it-user-" + System.nanoTime())
                .fullName("Integration User")
                .role(Role.OWNER)
                .passwordHash("n/a")
                .enabled(true)
                .build()).getId();
    }

    // ── Schedule helpers ─────────────────────────────────────────────────

    /** Creates a SLOT_BASED schedule config for the test doctor. */
    protected DoctorScheduleConfig createSlotBasedConfig() {
        return scheduleConfigRepository.save(
                DoctorScheduleConfig.builder()
                        .clinicId(clinicId)
                        .doctorId(doctorId)
                        .bookingMode("SLOT_BASED")
                        .slotDurationMinutes(15)
                        .maxPerSlot(1)
                        .build());
    }

    /** Creates a TOKEN_ONLY schedule config for the test doctor. */
    protected DoctorScheduleConfig createTokenOnlyConfig() {
        return scheduleConfigRepository.save(
                DoctorScheduleConfig.builder()
                        .clinicId(clinicId)
                        .doctorId(doctorId)
                        .bookingMode("TOKEN_ONLY")
                        .slotDurationMinutes(15)
                        .maxPerSlot(1)
                        .build());
    }

    /**
     * Adds a working-hours session for the test doctor.
     *
     * @param dayOfWeek ISO-8601 day (1=Mon, 7=Sun)
     */
    protected DoctorWorkingHours addWorkingHours(int dayOfWeek, String start, String end) {
        return workingHoursRepository.save(
                DoctorWorkingHours.builder()
                        .clinicId(clinicId)
                        .doctorId(doctorId)
                        .dayOfWeek(dayOfWeek)
                        .startTime(LocalTime.parse(start))
                        .endTime(LocalTime.parse(end))
                        .active(true)
                        .build());
    }

    /** Creates a supplier with zero credit balance. */
    protected Supplier createSupplier(String name) {
        return supplierRepository.save(
                Supplier.builder()
                        .clinicId(clinicId)
                        .name(name)
                        .creditBalance(BigDecimal.ZERO)
                        .build());
    }
}
