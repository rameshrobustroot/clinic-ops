package com.clinicops.app.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the RFC-4180 CSV writer used by the export endpoints. */
class CsvWriterTest {

    @Nested
    class Escape {

        @Test
        void null_becomes_empty_string() {
            assertThat(CsvWriter.escape(null)).isEmpty();
        }

        @Test
        void plain_text_is_left_unquoted() {
            assertThat(CsvWriter.escape("Crocin 500mg")).isEqualTo("Crocin 500mg");
        }

        @Test
        void surrounding_whitespace_alone_does_not_trigger_quoting() {
            assertThat(CsvWriter.escape("  padded  ")).isEqualTo("  padded  ");
        }

        @Test
        void non_string_values_use_toString() {
            assertThat(CsvWriter.escape(42)).isEqualTo("42");
            assertThat(CsvWriter.escape(new BigDecimal("12.50"))).isEqualTo("12.50");
        }

        @Test
        void value_with_comma_is_quoted() {
            assertThat(CsvWriter.escape("Chennai, TN")).isEqualTo("\"Chennai, TN\"");
        }

        @Test
        void embedded_quotes_are_doubled_and_value_is_quoted() {
            assertThat(CsvWriter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        }

        @Test
        void lone_quote_becomes_four_quotes() {
            // opening quote + doubled quote + closing quote
            assertThat(CsvWriter.escape("\"")).isEqualTo("\"\"\"\"");
        }

        @Test
        void line_feed_is_quoted() {
            assertThat(CsvWriter.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        }

        @Test
        void carriage_return_is_quoted() {
            assertThat(CsvWriter.escape("line1\rline2")).isEqualTo("\"line1\rline2\"");
        }

        @Test
        void empty_string_stays_empty() {
            assertThat(CsvWriter.escape("")).isEmpty();
        }
    }

    @Nested
    class WriteRow {

        @Test
        void joins_cells_with_commas_and_terminates_with_crlf() throws IOException {
            StringWriter out = new StringWriter();
            CsvWriter.writeRow(out, List.of("a", "b", "c"));
            assertThat(out.toString()).isEqualTo("a,b,c\r\n");
        }

        @Test
        void escapes_each_cell_and_renders_null_as_empty() throws IOException {
            StringWriter out = new StringWriter();
            CsvWriter.writeRow(out, Arrays.asList("a", null, "b,c", 7));
            assertThat(out.toString()).isEqualTo("a,,\"b,c\",7\r\n");
        }

        @Test
        void empty_row_is_just_crlf() throws IOException {
            StringWriter out = new StringWriter();
            CsvWriter.writeRow(out, List.of());
            assertThat(out.toString()).isEqualTo("\r\n");
        }

        @Test
        void consecutive_rows_are_each_crlf_terminated() throws IOException {
            StringWriter out = new StringWriter();
            CsvWriter.writeRow(out, List.of("h1", "h2"));
            CsvWriter.writeRow(out, List.of("v1", "multi\nline"));
            assertThat(out.toString()).isEqualTo("h1,h2\r\nv1,\"multi\nline\"\r\n");
        }
    }
}
