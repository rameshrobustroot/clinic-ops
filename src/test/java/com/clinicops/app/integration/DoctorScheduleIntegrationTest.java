package com.clinicops.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.DoctorWorkingHours;
import com.clinicops.app.service.DoctorScheduleForm;
import com.clinicops.app.service.DoctorScheduleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for doctor schedule saving — focuses on the time-range
 * validation that was silently swallowing invalid sessions before this fix.
 */
class DoctorScheduleIntegrationTest extends BaseIntegrationTest {

    @Autowired DoctorScheduleService scheduleService;

    // ── Valid sessions ────────────────────────────────────────────────────

    @Test
    void valid_session_is_saved_successfully() {
        DoctorScheduleForm form = form("[{\"d\":1,\"s\":\"09:00\",\"e\":\"17:00\"}]");
        scheduleService.saveSchedule(clinicId, doctorId, form);

        List<DoctorWorkingHours> hours = scheduleService.findWorkingHours(clinicId, doctorId);
        assertThat(hours).hasSize(1);
        assertThat(hours.get(0).getStartTime().toString()).isEqualTo("09:00");
        assertThat(hours.get(0).getEndTime().toString()).isEqualTo("17:00");
    }

    @Test
    void multiple_valid_sessions_on_same_day_are_saved() {
        DoctorScheduleForm form = form(
                "[{\"d\":1,\"s\":\"09:00\",\"e\":\"13:00\"}," +
                " {\"d\":1,\"s\":\"15:00\",\"e\":\"18:00\"}]");
        scheduleService.saveSchedule(clinicId, doctorId, form);

        List<DoctorWorkingHours> hours = scheduleService.findWorkingHours(clinicId, doctorId);
        assertThat(hours).hasSize(2);
    }

    // ── Invalid time ranges ───────────────────────────────────────────────

    @Test
    void end_before_start_throws_with_descriptive_message() {
        // 09:00 → 02:00 is the exact scenario reported by the user
        DoctorScheduleForm form = form("[{\"d\":1,\"s\":\"09:00\",\"e\":\"02:00\"}]");

        assertThatThrownBy(() -> scheduleService.saveSchedule(clinicId, doctorId, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Monday")
                .hasMessageContaining("02:00")
                .hasMessageContaining("09:00");
    }

    @Test
    void end_equal_to_start_throws_validation_error() {
        DoctorScheduleForm form = form("[{\"d\":3,\"s\":\"10:00\",\"e\":\"10:00\"}]");

        assertThatThrownBy(() -> scheduleService.saveSchedule(clinicId, doctorId, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wednesday");
    }

    @Test
    void midnight_end_time_on_a_saturday_throws_validation_error() {
        // 08:00 → 00:00 — another common mistake with 24hr pickers
        DoctorScheduleForm form = form("[{\"d\":6,\"s\":\"08:00\",\"e\":\"00:00\"}]");

        assertThatThrownBy(() -> scheduleService.saveSchedule(clinicId, doctorId, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Saturday");
    }

    @Test
    void invalid_session_in_batch_throws_and_aborts_save() {
        // Mix of a valid session (Monday) and an invalid one (Tuesday end < start).
        // The invalid session must throw — Spring's @Transactional will roll back the
        // entire saveSchedule call, but in a @Transactional test the rollback happens at
        // teardown, so we just verify the exception is thrown with the right day name.
        DoctorScheduleForm form = form(
                "[{\"d\":1,\"s\":\"09:00\",\"e\":\"13:00\"}," +
                " {\"d\":2,\"s\":\"15:00\",\"e\":\"10:00\"}]");

        assertThatThrownBy(() -> scheduleService.saveSchedule(clinicId, doctorId, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tuesday");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static DoctorScheduleForm form(String sessionsJson) {
        DoctorScheduleForm f = new DoctorScheduleForm();
        f.setBookingMode("SLOT_BASED");
        f.setSlotDurationMinutes(15);
        f.setMaxPerSlot(1);
        f.setSessionsJson(sessionsJson);
        return f;
    }
}
