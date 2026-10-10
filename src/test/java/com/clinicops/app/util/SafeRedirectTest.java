package com.clinicops.app.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SafeRedirectTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/patients", "/supplier-bills/new?x=1&y=2", "/a/b#frag", "/%5Cencoded-is-just-a-path"})
    void keeps_same_origin_relative_paths(String value) {
        assertThat(SafeRedirect.relativeOrNull(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "https://evil.example.com",          // absolute URL
            "evil.example.com",                  // not rooted
            "//evil.example.com",                // protocol-relative
            "/\\evil.example.com",               // browsers read \ as /
            "\\\\evil.example.com",
            "/\t/evil.example.com",              // browsers strip tab → //evil
            "/\n/evil.example.com",              // ...and newlines (also header injection)
            "/\r\nSet-Cookie: x=1",
            "/patients\u007f"})
    void rejects_anything_that_could_leave_the_site(String value) {
        assertThat(SafeRedirect.relativeOrNull(value)).isNull();
    }
}
