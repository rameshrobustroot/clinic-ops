package com.clinicops.app.util;

/** Centralised validation patterns. Update one place and every form gets the new rule. */
public final class ValidationPatterns {

    /** Indian mobile number: exactly 10 digits, starting with 6-9. */
    public static final String PHONE_INDIA = "^[6-9][0-9]{9}$";

    /** Same as PHONE_INDIA but also allows an empty/blank value (use for optional phone fields). */
    public static final String PHONE_INDIA_OPTIONAL = "^$|^[6-9][0-9]{9}$";

    /** GSTIN: 15 chars, e.g. 22AAAAA0000A1Z5. */
    public static final String GSTIN = "^$|^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9][A-Z][0-9A-Z]$";

    /** IFSC: 11 chars (4 alpha + '0' + 6 alphanumeric). */
    public static final String IFSC = "^$|^[A-Z]{4}0[A-Z0-9]{6}$";

    public static final String PHONE_HELP = "10-digit mobile starting with 6/7/8/9 (no +91 or spaces)";

    private ValidationPatterns() {}
}
