package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "clinic")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Clinic {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable unique identifier used in login (e.g. "happy-clinic"). */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String phone;
    private String address;

    /** Relative path of the uploaded logo under the uploads dir. Rendered on prescriptions/bills. */
    @Column(name = "logo_path")
    private String logoPath;

    private String tagline;
    private String email;
    private String website;

    @Column(name = "license_no")
    private String licenseNo;

    private String gstin;

    /** Free-text footer on prescriptions and bills (e.g. "Emergency: call XXX"). */
    @Column(name = "footer_note")
    private String footerNote;

    /** Version string of the Terms of Service the owner accepted at registration. */
    @Column(name = "terms_version", length = 32)
    private String termsVersion;

    /** Timestamp of that acceptance — audit trail for the clinic owner. */
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
