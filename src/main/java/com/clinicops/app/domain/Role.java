package com.clinicops.app.domain;

public enum Role {
    /**
     * Software publisher / SaaS operator. Maintains the Terms of Service, Privacy Policy
     * and Healthcare Disclaimer for all tenants. Not a clinical role — has no patient-data
     * access by intent (uses a stub clinic only because every AppUser is clinic-scoped).
     */
    PLATFORM_ADMIN,

    /** Clinic owner — full clinical + admin access within their own clinic. */
    OWNER,
    DOCTOR,
    PHARMACIST,
    RECEPTIONIST
}
