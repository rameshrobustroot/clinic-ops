package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.service.VisitService.InvestigationLine;
import com.clinicops.app.service.VisitService.RxLine;
import com.clinicops.app.service.VisitService.Vitals;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the static form-parsing helpers and value carriers on {@link VisitService}. */
class VisitServiceParsingTest {

    @Nested
    class ParseRxLines {

        @Test
        void returns_empty_when_both_medicine_ids_and_custom_names_are_null() {
            // Other columns alone are ignored — a row needs either an id or a typed-name column
            List<RxLine> out = VisitService.parseRxLines(null, null,
                    new String[]{"1-0-1"}, new String[]{"BD"}, new String[]{"5 days"}, new String[]{"x"});
            assertThat(out).isEmpty();
        }

        @Test
        void parses_aligned_arrays_into_lines() {
            List<RxLine> out = VisitService.parseRxLines(
                    new String[]{"12", ""},
                    new String[]{"", "Zincovit"},
                    new String[]{"1-0-1", "0-0-1"},
                    new String[]{"BD", "HS"},
                    new String[]{"5 days", "10 days"},
                    new String[]{"after food", ""});

            assertThat(out).containsExactly(
                    new RxLine(12L, null, "1-0-1", "BD", "5 days", "after food"),
                    new RxLine(null, "Zincovit", "0-0-1", "HS", "10 days", null));
        }

        @Test
        void uneven_arrays_use_the_longest_length_and_pad_missing_cells_with_null() {
            List<RxLine> out = VisitService.parseRxLines(
                    new String[]{"5"},
                    new String[]{null, "B", "C"},
                    new String[]{"d1", "d2"},
                    null, null, null);

            assertThat(out).containsExactly(
                    new RxLine(5L, null, "d1", null, null, null),
                    new RxLine(null, "B", "d2", null, null, null),
                    new RxLine(null, "C", null, null, null, null));
        }

        @Test
        void a_longer_secondary_column_still_extends_the_row_count() {
            // Length is the max over ALL six arrays, not just ids/names
            List<RxLine> out = VisitService.parseRxLines(
                    new String[]{"1"}, null, null, null, new String[]{"3d", "4d"}, null);
            assertThat(out).hasSize(2);
            assertThat(out.get(1)).isEqualTo(new RxLine(null, null, null, null, "4d", null));
        }

        @Test
        void non_numeric_medicine_id_is_ignored_rather_than_failing() {
            List<RxLine> out = VisitService.parseRxLines(
                    new String[]{"abc", "12.5", "  42  "}, null, null, null, null, null);

            assertThat(out).extracting(RxLine::getMedicineId).containsExactly(null, null, 42L);
        }

        @Test
        void trims_values_and_turns_whitespace_only_cells_into_null() {
            List<RxLine> out = VisitService.parseRxLines(
                    new String[]{"   "},
                    new String[]{"  Dolo 650  "},
                    new String[]{"  "},
                    new String[]{"\t"},
                    new String[]{" 3 days "},
                    new String[]{null});

            assertThat(out).containsExactly(new RxLine(null, "Dolo 650", null, null, "3 days", null));
        }

        @Test
        void keeps_fully_blank_rows_so_callers_can_filter_with_hasContent() {
            List<RxLine> out = VisitService.parseRxLines(
                    new String[]{""}, new String[]{""}, null, null, null, null);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).hasContent()).isFalse();
        }

        @Test
        void returned_list_is_mutable() {
            List<RxLine> out = VisitService.parseRxLines(null, null, null, null, null, null);
            out.add(new RxLine(1L, null, null, null, null, null));
            assertThat(out).hasSize(1);
        }
    }

    @Nested
    class ParseInvestigationLines {

        @Test
        void null_names_returns_empty_mutable_list() {
            List<InvestigationLine> out = VisitService.parseInvestigationLines(null, new String[]{"x"});
            assertThat(out).isEmpty();
            out.add(new InvestigationLine("CBC", null));
            assertThat(out).hasSize(1);
        }

        @Test
        void skips_null_and_blank_names_without_shifting_instruction_alignment() {
            List<InvestigationLine> out = VisitService.parseInvestigationLines(
                    new String[]{"CBC", "  ", null, "LFT"},
                    new String[]{"fasting", "b", "c", "morning"});

            // "LFT" is at index 3 and must still pick up instructions[3], not instructions[1]
            assertThat(out).containsExactly(
                    new InvestigationLine("CBC", "fasting"),
                    new InvestigationLine("LFT", "morning"));
        }

        @Test
        void trims_names_and_instructions() {
            List<InvestigationLine> out = VisitService.parseInvestigationLines(
                    new String[]{"  Lipid profile "}, new String[]{"  12h fasting "});
            assertThat(out).containsExactly(new InvestigationLine("Lipid profile", "12h fasting"));
        }

        @Test
        void missing_or_short_instructions_array_yields_null_instructions() {
            assertThat(VisitService.parseInvestigationLines(new String[]{"CBC"}, null))
                    .containsExactly(new InvestigationLine("CBC", null));
            assertThat(VisitService.parseInvestigationLines(new String[]{"CBC", "ESR"}, new String[]{"a"}))
                    .containsExactly(new InvestigationLine("CBC", "a"), new InvestigationLine("ESR", null));
        }

        @Test
        void whitespace_only_instruction_becomes_empty_string_not_null() {
            // Unlike parseRxLines, blank instructions are trimmed to "" here; the service's
            // persistInvestigations later normalises "" to null, so the stored value is still null.
            assertThat(VisitService.parseInvestigationLines(new String[]{"CBC"}, new String[]{"   "}))
                    .containsExactly(new InvestigationLine("CBC", ""));
        }
    }

    @Nested
    class RxLineHasContent {

        @Test
        void medicine_id_alone_counts_as_content() {
            assertThat(new RxLine(1L, null, null, null, null, null).hasContent()).isTrue();
        }

        @Test
        void custom_name_alone_counts_as_content() {
            assertThat(new RxLine(null, "Dolo", null, null, null, null).hasContent()).isTrue();
        }

        @Test
        void dosage_alone_counts_as_content() {
            assertThat(new RxLine(null, null, "1-0-1", null, null, null).hasContent()).isTrue();
        }

        @Test
        void duration_alone_counts_as_content() {
            assertThat(new RxLine(null, null, null, null, "5 days", null).hasContent()).isTrue();
        }

        @Test
        void frequency_or_instructions_alone_do_not_count_as_content() {
            assertThat(new RxLine(null, null, null, "BD", null, null).hasContent()).isFalse();
            assertThat(new RxLine(null, null, null, null, null, "after food").hasContent()).isFalse();
        }

        @Test
        void blank_strings_do_not_count_as_content() {
            assertThat(new RxLine(null, "  ", " ", null, "\t", null).hasContent()).isFalse();
        }
    }

    @Nested
    class VitalsIsEmpty {

        @Test
        void all_null_is_empty() {
            assertThat(Vitals.builder().build().isEmpty()).isTrue();
        }

        @Test
        void glucose_type_without_a_reading_is_still_empty() {
            assertThat(Vitals.builder().bloodGlucoseType("FASTING").build().isEmpty()).isTrue();
        }

        @Test
        void any_single_reading_makes_it_non_empty() {
            assertThat(Vitals.builder().bpSystolic(120).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().bpDiastolic(80).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().pulse(72).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().temperatureF(new BigDecimal("98.6")).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().respiratoryRate(16).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().spo2(98).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().weightKg(new BigDecimal("70")).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().heightCm(new BigDecimal("170")).build().isEmpty()).isFalse();
            assertThat(Vitals.builder().bloodGlucose(110).build().isEmpty()).isFalse();
        }
    }
}
