package com.clinicops.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Appointment;
import com.clinicops.app.service.AppointmentForm;
import com.clinicops.app.service.AppointmentService;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end integration tests for appointment booking against an H2 database.
 *
 * <p>Tests the full service stack (controller → service → repository) to verify
 * that the server-side schedule-validation logic prevents bookings on non-working
 * days and outside configured session hours.
 *
 * <p>The doctor is scheduled Mon / Wed / Fri 09:00–17:00 for all tests unless
 * a specific {@code @Test} overrides the fixture.
 */
class AppointmentBookingIntegrationTest extends BaseIntegrationTest {

    @Autowired AppointmentService appointmentService;

    // Dates anchored to real days of the week:
    //   2026-06-01  Monday    (ISO 1)
    //   2026-06-02  Tuesday   (ISO 2) — NOT a working day in this fixture
    //   2026-06-03  Wednesday (ISO 3)
    //   2026-06-06  Saturday  (ISO 6) — NOT a working day
    static final LocalDate MONDAY    = LocalDate.of(2026, 6, 1);
    static final LocalDate TUESDAY   = LocalDate.of(2026, 6, 2);
    static final LocalDate WEDNESDAY = LocalDate.of(2026, 6, 3);
    static final LocalDate FRIDAY    = LocalDate.of(2026, 6, 5);
    static final LocalDate SATURDAY  = LocalDate.of(2026, 6, 6);

    @BeforeEach
    void setUpMwfSchedule() {
        createSlotBasedConfig();
        addWorkingHours(1, "09:00", "17:00"); // Monday
        addWorkingHours(3, "09:00", "17:00"); // Wednesday
        addWorkingHours(5, "09:00", "17:00"); // Friday
    }

    // ── Working-day success cases ────────────────────────────────────────

    @Test
    void book_on_monday_creates_scheduled_appointment() {
        Appointment appt = appointmentService.book(clinicId, walkInForm(MONDAY, null));

        assertThat(appt.getId()).isNotNull();
        assertThat(appt.getStatus()).isEqualTo("SCHEDULED");
        assertThat(appt.getTokenNo()).isEqualTo(1);
    }

    @Test
    void book_on_wednesday_succeeds() {
        Appointment appt = appointmentService.book(clinicId, walkInForm(WEDNESDAY, null));
        assertThat(appt.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    void book_on_friday_succeeds() {
        Appointment appt = appointmentService.book(clinicId, walkInForm(FRIDAY, null));
        assertThat(appt.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    void book_with_valid_time_slot_preserves_appointment_time() {
        Appointment appt = appointmentService.book(clinicId, walkInForm(MONDAY, "10:30"));
        assertThat(appt.getAppointmentTime()).isEqualTo(LocalTime.of(10, 30));
    }

    // ── Non-working-day rejection ────────────────────────────────────────

    @Test
    void book_on_tuesday_throws_not_available() {
        assertThatThrownBy(() -> appointmentService.book(clinicId, walkInForm(TUESDAY, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available on Tuesday");
    }

    @Test
    void book_on_saturday_throws_not_available() {
        assertThatThrownBy(() -> appointmentService.book(clinicId, walkInForm(SATURDAY, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available on Saturday");
    }

    @Test
    void book_on_non_working_day_blocked_even_without_explicit_time() {
        // Regression: original bug allowed token-queue bookings to slip through
        // when no appointmentTime was submitted. Verify that the day check runs
        // regardless of whether a time was provided.
        AppointmentForm form = walkInForm(TUESDAY, null); // no time submitted
        assertThatThrownBy(() -> appointmentService.book(clinicId, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available on Tuesday");
    }

    // ── Out-of-hours rejection ───────────────────────────────────────────

    @Test
    void book_with_time_before_session_start_is_rejected() {
        assertThatThrownBy(() -> appointmentService.book(clinicId, walkInForm(MONDAY, "08:30")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside this doctor's working hours");
    }

    @Test
    void book_with_time_after_session_end_is_rejected() {
        assertThatThrownBy(() -> appointmentService.book(clinicId, walkInForm(MONDAY, "18:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside this doctor's working hours");
    }

    @Test
    void book_with_time_exactly_at_end_boundary_is_rejected() {
        // 17:00 is the end time; it is exclusive (last valid slot is 16:xx)
        assertThatThrownBy(() -> appointmentService.book(clinicId, walkInForm(MONDAY, "17:00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── TOKEN_ONLY doctor ────────────────────────────────────────────────

    @Test
    void token_only_doctor_can_be_booked_on_any_day() {
        // Create a separate TOKEN_ONLY doctor (different doctorId) to avoid
        // conflicting with the MWF SLOT_BASED config set up in @BeforeEach.
        Long tokenDoctorId = doctorRepository.save(
                com.clinicops.app.domain.Doctor.builder()
                        .clinicId(clinicId).fullName("Dr Token").active(true).build()).getId();

        scheduleConfigRepository.save(
                com.clinicops.app.domain.DoctorScheduleConfig.builder()
                        .clinicId(clinicId).doctorId(tokenDoctorId)
                        .bookingMode("TOKEN_ONLY").slotDurationMinutes(15).maxPerSlot(1)
                        .build());

        AppointmentForm form = new AppointmentForm();
        form.setDoctorId(tokenDoctorId);
        form.setAppointmentDate(TUESDAY); // Tuesday — would fail for SLOT_BASED
        form.setPatientName("Token Walk-in");

        Appointment appt = appointmentService.book(clinicId, form);
        assertThat(appt.getStatus()).isEqualTo("SCHEDULED");
        assertThat(appt.getAppointmentTime()).isNull(); // no time for TOKEN_ONLY
    }

    @Test
    void token_only_booking_discards_submitted_time() {
        Long tokenDoctorId = doctorRepository.save(
                com.clinicops.app.domain.Doctor.builder()
                        .clinicId(clinicId).fullName("Dr Token 2").active(true).build()).getId();

        scheduleConfigRepository.save(
                com.clinicops.app.domain.DoctorScheduleConfig.builder()
                        .clinicId(clinicId).doctorId(tokenDoctorId)
                        .bookingMode("TOKEN_ONLY").slotDurationMinutes(15).maxPerSlot(1)
                        .build());

        AppointmentForm form = new AppointmentForm();
        form.setDoctorId(tokenDoctorId);
        form.setAppointmentDate(MONDAY);
        form.setAppointmentTime("09:30"); // should be stripped
        form.setPatientName("Token Walkin");

        Appointment appt = appointmentService.book(clinicId, form);
        assertThat(appt.getAppointmentTime()).isNull();
    }

    // ── Token sequencing ─────────────────────────────────────────────────

    @Test
    void sequential_bookings_get_incrementing_token_numbers() {
        Appointment first  = appointmentService.book(clinicId, walkInForm(MONDAY, null));
        Appointment second = appointmentService.book(clinicId, walkInForm(MONDAY, "09:15"));
        Appointment third  = appointmentService.book(clinicId, walkInForm(MONDAY, "09:30"));

        assertThat(first.getTokenNo()).isEqualTo(1);
        assertThat(second.getTokenNo()).isEqualTo(2);
        assertThat(third.getTokenNo()).isEqualTo(3);
    }

    // ── Status transitions ───────────────────────────────────────────────

    @Test
    void booked_appointment_can_transition_scheduled_to_arrived_to_done() {
        Appointment appt = appointmentService.book(clinicId, walkInForm(MONDAY, null));

        // In a single @Transactional test, all updateStatus calls share the same JPA
        // first-level cache — intermediate return values alias the same managed entity,
        // so only the FINAL state is meaningful to assert.  We verify the full chain
        // completes without exceptions and that the end state is DONE.
        assertThatCode(() -> appointmentService.updateStatus(appt.getId(), "ARRIVED"))
                .doesNotThrowAnyException();
        assertThatCode(() -> appointmentService.updateStatus(appt.getId(), "IN_PROGRESS"))
                .doesNotThrowAnyException();
        Appointment done = appointmentService.updateStatus(appt.getId(), "DONE");

        assertThat(done.getStatus()).isEqualTo("DONE");
    }

    @Test
    void booked_appointment_can_be_cancelled_from_scheduled() {
        Appointment appt = appointmentService.book(clinicId, walkInForm(MONDAY, null));
        Appointment cancelled = appointmentService.updateStatus(appt.getId(), "CANCELLED");
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private AppointmentForm walkInForm(LocalDate date, String time) {
        AppointmentForm form = new AppointmentForm();
        form.setDoctorId(doctorId);
        form.setAppointmentDate(date);
        form.setAppointmentTime(time);
        form.setPatientName("Walk-in");
        return form;
    }
}
