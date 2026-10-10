package com.clinicops.app.util;

/**
 * Guards user-supplied "return to" targets against open redirects. Only same-origin, path-relative
 * URLs survive.
 *
 * <p>Browsers are lenient when resolving a {@code Location} header: they treat {@code \} like
 * {@code /} and silently strip tabs / newlines, so {@code /\evil.com} and {@code /<TAB>/evil.com}
 * both become the protocol-relative {@code //evil.com}. Rejecting backslashes and control
 * characters outright closes those tricks.
 */
public final class SafeRedirect {

    private SafeRedirect() {}

    /** Returns {@code value} if it is a safe relative path, else {@code null}. */
    public static String relativeOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.startsWith("/") || value.startsWith("//")) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7f) return null;
        }
        return value;
    }
}
