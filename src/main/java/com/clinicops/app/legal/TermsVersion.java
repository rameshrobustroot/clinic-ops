package com.clinicops.app.legal;

/**
 * Single source of truth for the published Terms / Privacy / Disclaimer version.
 *
 * Bump {@link #CURRENT} when the legal text materially changes. The new value
 * gets persisted on fresh clinic registrations; an audit comparison against
 * existing {@code clinic.terms_version} reveals who needs re-acceptance.
 *
 * Use ISO-8601 dates so versions sort chronologically.
 */
public final class TermsVersion {

    /** Increment when the legal text changes. */
    public static final String CURRENT = "2026-05-24";

    private TermsVersion() {}
}
