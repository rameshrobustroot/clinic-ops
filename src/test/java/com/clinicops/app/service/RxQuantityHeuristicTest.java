package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RxQuantityHeuristicTest {

    // ---- parseDosesPerDay --------------------------------------------------

    @Test
    void dosage_one_zero_one_means_two_doses() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("1-0-1")).isEqualTo(2);
    }

    @Test
    void dosage_one_one_one_means_three_doses() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("1-1-1")).isEqualTo(3);
    }

    @Test
    void dosage_two_one_zero_means_three_doses() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("2-1-0")).isEqualTo(3);
    }

    @Test
    void dosage_bd_means_two_doses() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("BD")).isEqualTo(2);
    }

    @Test
    void dosage_tds_means_three_doses() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("TDS")).isEqualTo(3);
    }

    @Test
    void dosage_qid_means_four_doses() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("QID")).isEqualTo(4);
    }

    @Test
    void dosage_sos_means_one_dose() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("SOS")).isEqualTo(1);
    }

    @Test
    void dosage_blank_returns_zero() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("")).isZero();
        assertThat(RxQuantityHeuristic.parseDosesPerDay(null)).isZero();
        assertThat(RxQuantityHeuristic.parseDosesPerDay("anything random")).isZero();
    }

    // ---- parseDays --------------------------------------------------------

    @Test
    void duration_five_days() {
        assertThat(RxQuantityHeuristic.parseDays("5 days")).isEqualTo(5);
        assertThat(RxQuantityHeuristic.parseDays("5d")).isEqualTo(5);
        assertThat(RxQuantityHeuristic.parseDays("5D")).isEqualTo(5);
    }

    @Test
    void duration_in_weeks_converted_to_days() {
        assertThat(RxQuantityHeuristic.parseDays("2 weeks")).isEqualTo(14);
        assertThat(RxQuantityHeuristic.parseDays("3w")).isEqualTo(21);
    }

    @Test
    void duration_unparseable_returns_zero() {
        assertThat(RxQuantityHeuristic.parseDays(null)).isZero();
        assertThat(RxQuantityHeuristic.parseDays("")).isZero();
        assertThat(RxQuantityHeuristic.parseDays("when needed")).isZero();
    }

    // Regression: plain integer duration (e.g. "5") was returning 0 → compute fell back to 1
    @Test
    void duration_plain_integer_treated_as_days() {
        assertThat(RxQuantityHeuristic.parseDays("5")).isEqualTo(5);
        assertThat(RxQuantityHeuristic.parseDays("3")).isEqualTo(3);
        assertThat(RxQuantityHeuristic.parseDays("14")).isEqualTo(14);
        assertThat(RxQuantityHeuristic.parseDays("0")).isZero();    // 0 is not a valid day count
        assertThat(RxQuantityHeuristic.parseDays("400")).isZero();  // > 365 is implausible
    }

    // ---- dosage 4-part M-N-E-N -------------------------------------------

    // Regression: 4-part M-N-E-N format (1-1-0-1) was not matched by the 3-part regex
    @Test
    void dosage_four_part_mnen_parsed_correctly() {
        assertThat(RxQuantityHeuristic.parseDosesPerDay("1-1-0-1")).isEqualTo(3);
        assertThat(RxQuantityHeuristic.parseDosesPerDay("1-0-0-1")).isEqualTo(2);
        assertThat(RxQuantityHeuristic.parseDosesPerDay("1-1-1-1")).isEqualTo(4);
        assertThat(RxQuantityHeuristic.parseDosesPerDay("0-0-0-1")).isEqualTo(1);
    }

    // ---- compute (full heuristic) -----------------------------------------

    @Test
    void compute_one_zero_one_for_five_days_gives_ten() {
        // The headline example from our brief to the doctor.
        assertThat(RxQuantityHeuristic.compute("1-0-1", "5 days")).isEqualTo(10);
    }

    @Test
    void compute_tds_for_three_days_gives_nine() {
        assertThat(RxQuantityHeuristic.compute("TDS", "3 days")).isEqualTo(9);
    }

    // Regression: "1-1-1" dosage + "5" plain-number duration was returning 1 (the bug)
    @Test
    void compute_plain_integer_duration_works_correctly() {
        assertThat(RxQuantityHeuristic.compute("1-1-1", "5")).isEqualTo(15);  // 3 doses × 5 days
        assertThat(RxQuantityHeuristic.compute("1-0-1", "3")).isEqualTo(6);   // 2 doses × 3 days
        assertThat(RxQuantityHeuristic.compute("1-0-0", "5")).isEqualTo(5);   // 1 dose  × 5 days
        assertThat(RxQuantityHeuristic.compute("0-0-1", "5")).isEqualTo(5);   // 1 dose  × 5 days
    }

    @Test
    void compute_four_part_dosage_with_plain_duration() {
        assertThat(RxQuantityHeuristic.compute("1-1-0-1", "5")).isEqualTo(15); // 3 × 5
        assertThat(RxQuantityHeuristic.compute("1-0-0-1", "3")).isEqualTo(6);  // 2 × 3
    }

    @Test
    void compute_returns_one_when_dosage_or_duration_unparseable() {
        // Conservative fallback — pharmacist must consciously adjust before dispensing.
        assertThat(RxQuantityHeuristic.compute("once a day", "until better")).isEqualTo(1);
        assertThat(RxQuantityHeuristic.compute(null, null)).isEqualTo(1);
    }
}
