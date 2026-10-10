package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clinicops.app.domain.Appointment;
import com.clinicops.app.domain.DoctorScheduleConfig;
import com.clinicops.app.domain.DoctorWorkingHours;
import com.clinicops.app.repository.AppointmentRepository;
import com.clinicops.app.repository.DoctorScheduleConfigRepository;
import com.clinicops.app.repository.DoctorWorkingHoursRepository;
import com.clinicops.app.repository.PatientRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AppointmentServiceImpl} booking-validation logic.
 *
 * <p>All repository interactions are mocked so these run without a Spring context.
 * Focus: the server-side validation added to prevent bookings on non-working days
 * and outside configured session hours.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentServiceImplTest {

    @Mock AppointmentRepository appointmentRepo;
    @Mock PatientRepository patientRepo;
    @Mock DoctorScheduleConfigRepository configRepo;
    @Mock DoctorWorkingHoursRepository hoursRepo;

    @InjectMocks
    AppointmentServiceImpl service;

    static final Long CLINIC_ID = 1L;
    static final Long DOCTOR_ID = 10L;

    // 2026-06-01 is a Monday (ISO day 1)
    static final LocalDate MONDAY    = LocalDate.of(2026, 6, 1);
    static final LocalDate TUESDAY   = LocalDate.of(2026, 6, 2);
    static final LocalDate WEDNESDAY = LocalDate.of(2026, 6, 3);
    static final LocalDate SATURDAY  = LocalDate.of(2026, 5, 30);

    @BeforeEach
    void stubToken() {
        // Use lenient() so these stubs don't fail tests in StatusTransitions that don't call book()
        lenient().when(appointmentRepo.maxTokenForDoctorOnDate(anyLong(), anyLong(), any()))
                .thenReturn(Optional.empty());
        lenient().when(appointmentRepo.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment a = inv.getArgument(0);
            a.setId(99L);
            return a;
        });
    }

    // ── SLOT_BASED validation ────────────────────────────────────────────

    @Nested
    class SlotBased {

        @BeforeEach
        void stubConfig() {
            when(configRepo.findByClinicIdAndDoctorId(CLINIC_ID, DOCTOR_ID))
                    .thenReturn(Optional.of(slotBasedConfig()));
        }

        @Test
        void throws_when_doctor_has_no_sessions_on_selected_day() {
            // Monday is NOT configured
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.book(CLINIC_ID, formFor(MONDAY, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not available on Monday");
        }

        @Test
        void throws_when_different_non_working_day_is_submitted() {
            // Tuesday (ISO 2) — no hours configured
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 2))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.book(CLINIC_ID, formFor(TUESDAY, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not available on Tuesday");
        }

        @Test
        void succeeds_when_doctor_works_on_selected_day_no_time_submitted() {
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of(hours(1, "09:00", "17:00")));

            Appointment result = service.book(CLINIC_ID, formFor(MONDAY, null));

            assertThat(result).isNotNull();
            assertThat(result.getAppointmentTime()).isNull(); // no time = token queue
        }

        @Test
        void succeeds_when_submitted_time_is_within_session() {
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of(hours(1, "09:00", "17:00")));

            Appointment result = service.book(CLINIC_ID, formFor(MONDAY, "10:30"));

            assertThat(result.getAppointmentTime()).isEqualTo(LocalTime.of(10, 30));
        }

        @Test
        void accepts_time_exactly_at_session_start_boundary() {
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of(hours(1, "09:00", "13:00")));

            assertThatCode(() -> service.book(CLINIC_ID, formFor(MONDAY, "09:00")))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejects_time_exactly_at_session_end_boundary() {
            // End time is exclusive: "isBefore(endTime)" means endTime itself is rejected
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of(hours(1, "09:00", "13:00")));

            assertThatThrownBy(() -> service.book(CLINIC_ID, formFor(MONDAY, "13:00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside this doctor's working hours");
        }

        @Test
        void throws_when_submitted_time_is_after_all_sessions() {
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of(hours(1, "09:00", "13:00")));

            assertThatThrownBy(() -> service.book(CLINIC_ID, formFor(MONDAY, "18:00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside this doctor's working hours");
        }

        @Test
        void accepts_time_within_any_of_multiple_sessions() {
            // Morning 09–13 and evening 17–20
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of(
                            hours(1, "09:00", "13:00"),
                            hours(1, "17:00", "20:00")));

            // Evening slot should be valid
            assertThatCode(() -> service.book(CLINIC_ID, formFor(MONDAY, "18:00")))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejects_time_between_two_sessions_gap() {
            // 09-13 and 17-20 → 14:00 is in the gap
            when(hoursRepo.findByClinicIdAndDoctorIdAndDayOfWeekAndActiveTrue(CLINIC_ID, DOCTOR_ID, 1))
                    .thenReturn(List.of(
                            hours(1, "09:00", "13:00"),
                            hours(1, "17:00", "20:00")));

            assertThatThrownBy(() -> service.book(CLINIC_ID, formFor(MONDAY, "14:00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── TOKEN_ONLY validation ────────────────────────────────────────────

    @Nested
    class TokenOnly {

        @BeforeEach
        void stubConfig() {
            when(configRepo.findByClinicIdAndDoctorId(CLINIC_ID, DOCTOR_ID))
                    .thenReturn(Optional.of(tokenOnlyConfig()));
        }

        @Test
        void discards_submitted_appointment_time() {
            Appointment result = service.book(CLINIC_ID, formFor(MONDAY, "09:30"));
            assertThat(result.getAppointmentTime()).isNull();
        }

        @Test
        void succeeds_on_any_day_without_consulting_hours_repo() {
            Appointment result = service.book(CLINIC_ID, formFor(SATURDAY, null));

            assertThat(result).isNotNull();
            verifyNoInteractions(hoursRepo);
        }

        @Test
        void assigns_token_number_on_booking() {
            when(appointmentRepo.maxTokenForDoctorOnDate(CLINIC_ID, DOCTOR_ID, MONDAY))
                    .thenReturn(Optional.of(4)); // existing max = 4 → next = 5

            Appointment result = service.book(CLINIC_ID, formFor(MONDAY, null));
            assertThat(result.getTokenNo()).isEqualTo(5);
        }
    }

    // ── No config ────────────────────────────────────────────────────────

    @Nested
    class NoScheduleConfig {

        @BeforeEach
        void stubNoConfig() {
            when(configRepo.findByClinicIdAndDoctorId(CLINIC_ID, DOCTOR_ID))
                    .thenReturn(Optional.empty());
        }

        @Test
        void booking_always_succeeds_when_no_schedule_exists() {
            Appointment result = service.book(CLINIC_ID, formFor(TUESDAY, "10:00"));

            assertThat(result).isNotNull();
            verifyNoInteractions(hoursRepo);
        }
    }

    // ── No doctor specified ───────────────────────────────────────────────

    @Test
    void skips_schedule_validation_when_no_doctor_id() {
        AppointmentForm form = new AppointmentForm();
        form.setAppointmentDate(MONDAY);
        form.setPatientName("Walk-in");
        // doctorId is null

        when(appointmentRepo.findByClinicAndDate(anyLong(), any())).thenReturn(List.of());

        Appointment result = service.book(CLINIC_ID, form);

        assertThat(result).isNotNull();
        verifyNoInteractions(configRepo, hoursRepo);
    }

    // ── Status transitions ────────────────────────────────────────────────

    @Nested
    class StatusTransitions {

        @Test
        void scheduled_can_move_to_arrived() {
            stubAndSave("SCHEDULED");
            Appointment result = service.updateStatus(1L, "ARRIVED");
            assertThat(result.getStatus()).isEqualTo("ARRIVED");
        }

        @Test
        void scheduled_can_be_cancelled() {
            stubAndSave("SCHEDULED");
            Appointment result = service.updateStatus(1L, "CANCELLED");
            assertThat(result.getStatus()).isEqualTo("CANCELLED");
        }

        @Test
        void arrived_can_move_to_in_progress() {
            stubAndSave("ARRIVED");
            Appointment result = service.updateStatus(1L, "IN_PROGRESS");
            assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        void in_progress_can_move_to_done() {
            stubAndSave("IN_PROGRESS");
            Appointment result = service.updateStatus(1L, "DONE");
            assertThat(result.getStatus()).isEqualTo("DONE");
        }

        @Test
        void done_cannot_transition_further() {
            when(appointmentRepo.findById(1L))
                    .thenReturn(Optional.of(apptWithStatus("DONE")));

            assertThatThrownBy(() -> service.updateStatus(1L, "SCHEDULED"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot move appointment from DONE");
        }

        @Test
        void cancelled_cannot_transition_further() {
            when(appointmentRepo.findById(1L))
                    .thenReturn(Optional.of(apptWithStatus("CANCELLED")));

            assertThatThrownBy(() -> service.updateStatus(1L, "SCHEDULED"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot move appointment from CANCELLED");
        }

        @Test
        void scheduled_cannot_jump_directly_to_done() {
            when(appointmentRepo.findById(1L))
                    .thenReturn(Optional.of(apptWithStatus("SCHEDULED")));

            assertThatThrownBy(() -> service.updateStatus(1L, "DONE"))
                    .isInstanceOf(IllegalStateException.class);
        }

        private void stubAndSave(String fromStatus) {
            when(appointmentRepo.findById(1L))
                    .thenReturn(Optional.of(apptWithStatus(fromStatus)));
            when(appointmentRepo.save(any(Appointment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
        }
    }

    // ── Token assignment ──────────────────────────────────────────────────

    @Test
    void first_booking_of_day_gets_token_one() {
        when(configRepo.findByClinicIdAndDoctorId(CLINIC_ID, DOCTOR_ID))
                .thenReturn(Optional.empty()); // no config, skip validation
        when(appointmentRepo.maxTokenForDoctorOnDate(CLINIC_ID, DOCTOR_ID, MONDAY))
                .thenReturn(Optional.empty()); // no prior bookings

        Appointment result = service.book(CLINIC_ID, formFor(MONDAY, null));
        assertThat(result.getTokenNo()).isEqualTo(1);
    }

    @Test
    void subsequent_bookings_increment_token() {
        when(configRepo.findByClinicIdAndDoctorId(CLINIC_ID, DOCTOR_ID))
                .thenReturn(Optional.empty());
        when(appointmentRepo.maxTokenForDoctorOnDate(CLINIC_ID, DOCTOR_ID, MONDAY))
                .thenReturn(Optional.of(7)); // existing max

        Appointment result = service.book(CLINIC_ID, formFor(MONDAY, null));
        assertThat(result.getTokenNo()).isEqualTo(8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private AppointmentForm formFor(LocalDate date, String time) {
        AppointmentForm form = new AppointmentForm();
        form.setDoctorId(DOCTOR_ID);
        form.setAppointmentDate(date);
        form.setAppointmentTime(time);
        form.setPatientName("Test Patient");
        return form;
    }

    private DoctorScheduleConfig slotBasedConfig() {
        return DoctorScheduleConfig.builder()
                .id(1L).clinicId(CLINIC_ID).doctorId(DOCTOR_ID)
                .bookingMode("SLOT_BASED").slotDurationMinutes(15).maxPerSlot(1)
                .build();
    }

    private DoctorScheduleConfig tokenOnlyConfig() {
        return DoctorScheduleConfig.builder()
                .id(2L).clinicId(CLINIC_ID).doctorId(DOCTOR_ID)
                .bookingMode("TOKEN_ONLY").slotDurationMinutes(15).maxPerSlot(1)
                .build();
    }

    private DoctorWorkingHours hours(int dayOfWeek, String start, String end) {
        return DoctorWorkingHours.builder()
                .clinicId(CLINIC_ID).doctorId(DOCTOR_ID)
                .dayOfWeek(dayOfWeek)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .active(true)
                .build();
    }

    private Appointment apptWithStatus(String status) {
        return Appointment.builder()
                .id(1L).clinicId(CLINIC_ID).status(status)
                .appointmentDate(LocalDate.now())
                .build();
    }
}
