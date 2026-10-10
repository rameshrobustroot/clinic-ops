package com.clinicops.app.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ValidationPatternsTest {

    private static final Pattern PHONE = Pattern.compile(ValidationPatterns.PHONE_INDIA);
    private static final Pattern PHONE_OPT = Pattern.compile(ValidationPatterns.PHONE_INDIA_OPTIONAL);
    private static final Pattern GSTIN = Pattern.compile(ValidationPatterns.GSTIN);
    private static final Pattern IFSC = Pattern.compile(ValidationPatterns.IFSC);

    // ---- Phone (India, 10 digits, starts 6-9) -----------------------------

    @Test
    void valid_indian_mobile_numbers() {
        for (String n : new String[]{"9876543210", "8000012345", "7000000000", "6999999999"}) {
            assertThat(PHONE.matcher(n).matches()).as(n).isTrue();
        }
    }

    @Test
    void rejects_numbers_starting_with_one_to_five() {
        for (String n : new String[]{"1234567890", "5000012345", "0000000000"}) {
            assertThat(PHONE.matcher(n).matches()).as(n).isFalse();
        }
    }

    @Test
    void rejects_wrong_length_or_country_code() {
        // We deliberately do NOT accept +91 in the strict pattern — UI strips that.
        assertThat(PHONE.matcher("+919876543210").matches()).isFalse();
        assertThat(PHONE.matcher("98765432109").matches()).isFalse(); // 11 digits
        assertThat(PHONE.matcher("987654321").matches()).isFalse();   // 9 digits
        assertThat(PHONE.matcher("9876 54 3210").matches()).isFalse(); // spaces
    }

    @Test
    void phone_optional_allows_empty_string() {
        assertThat(PHONE_OPT.matcher("").matches()).isTrue();
        assertThat(PHONE_OPT.matcher("9876543210").matches()).isTrue();
        assertThat(PHONE_OPT.matcher("123").matches()).isFalse();
    }

    // ---- GSTIN -----------------------------------------------------------

    @Test
    void valid_gstin_is_accepted() {
        assertThat(GSTIN.matcher("22AAAAA0000A1Z5").matches()).isTrue();
    }

    @Test
    void invalid_gstin_rejected() {
        assertThat(GSTIN.matcher("22aaaaa0000a1z5").matches()).isFalse(); // lowercase
        assertThat(GSTIN.matcher("22AAAAA0000A1Z").matches()).isFalse();  // 14 chars
        assertThat(GSTIN.matcher("garbage").matches()).isFalse();
    }

    @Test
    void empty_gstin_is_allowed() {
        // GSTIN is optional everywhere it's used
        assertThat(GSTIN.matcher("").matches()).isTrue();
    }

    // ---- IFSC ------------------------------------------------------------

    @Test
    void valid_ifsc_is_accepted() {
        assertThat(IFSC.matcher("HDFC0001234").matches()).isTrue();
        assertThat(IFSC.matcher("SBIN0CCBANK").matches()).isTrue();
    }

    @Test
    void invalid_ifsc_rejected() {
        assertThat(IFSC.matcher("HDFC1001234").matches()).isFalse();  // 5th char must be 0
        assertThat(IFSC.matcher("hdfc0001234").matches()).isFalse();  // lowercase
        assertThat(IFSC.matcher("HDFC00012345").matches()).isFalse(); // 12 chars
    }

    @Test
    void phone_help_text_matches_what_the_pattern_accepts() {
        // The help text must not promise formats the regex rejects (it once claimed "+91" was OK).
        Pattern phone = Pattern.compile(ValidationPatterns.PHONE_INDIA);
        assertThat(phone.matcher("+919876543210").matches()).isFalse();
        assertThat(phone.matcher("98765 43210").matches()).isFalse();
        assertThat(ValidationPatterns.PHONE_HELP).contains("no +91");
        assertThat(ValidationPatterns.PHONE_HELP).doesNotContain("optionally with +91");
    }
}
