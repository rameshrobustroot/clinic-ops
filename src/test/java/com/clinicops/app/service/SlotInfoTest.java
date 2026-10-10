package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SlotInfo} record methods.
 * No Spring context needed — pure Java.
 */
class SlotInfoTest {

    @Test
    void label_returns_time_only_when_max_is_one() {
        SlotInfo slot = new SlotInfo(LocalTime.of(9, 30), 0, 1, true);
        // AM/PM casing is locale-dependent; use case-insensitive comparison
        assertThat(slot.label()).containsIgnoringCase("09:30");
        assertThat(slot.label()).doesNotContain("("); // no capacity fraction for single-slot
    }

    @Test
    void label_includes_booked_and_max_when_capacity_is_greater_than_one() {
        SlotInfo slot = new SlotInfo(LocalTime.of(14, 0), 2, 3, true);
        assertThat(slot.label()).containsIgnoringCase("02:00");
        assertThat(slot.label()).contains("(2/3)");
    }

    @Test
    void value_returns_24h_HH_mm_format() {
        SlotInfo morning = new SlotInfo(LocalTime.of(9, 5), 0, 1, true);
        assertThat(morning.value()).isEqualTo("09:05");

        SlotInfo afternoon = new SlotInfo(LocalTime.of(14, 30), 0, 1, true);
        assertThat(afternoon.value()).isEqualTo("14:30");
    }

    @Test
    void available_true_when_booked_is_less_than_max() {
        SlotInfo slot = new SlotInfo(LocalTime.of(10, 0), 1, 3, true);
        assertThat(slot.available()).isTrue();
    }

    @Test
    void available_false_when_slot_is_full() {
        SlotInfo slot = new SlotInfo(LocalTime.of(10, 0), 3, 3, false);
        assertThat(slot.available()).isFalse();
    }

    @Test
    void label_shows_zero_booked_on_empty_slot() {
        SlotInfo slot = new SlotInfo(LocalTime.of(8, 0), 0, 2, true);
        assertThat(slot.label()).containsIgnoringCase("08:00");
        assertThat(slot.label()).contains("(0/2)");
    }

    @Test
    void value_midnight_formats_correctly() {
        SlotInfo slot = new SlotInfo(LocalTime.MIDNIGHT, 0, 1, true);
        assertThat(slot.value()).isEqualTo("00:00");
    }
}
