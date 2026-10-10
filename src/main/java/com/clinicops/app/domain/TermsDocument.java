package com.clinicops.app.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;

/**
 * One published version of the Terms / Privacy / Healthcare Disclaimer. New versions are
 * appended (never edited in place) so the audit trail of what each clinic accepted survives.
 * The "current" document is the one with the most recent {@code publishedAt}.
 */
@Entity
@Table(name = "terms_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TermsDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 32)
    private String version;

    /** HTML body. Trusted because only the platform owner can publish.
     *  Use {@code text} so PostgreSQL stores inline rather than as a Large Object. */
    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "published_by_user_id")
    private Long publishedByUserId;

    @PrePersist
    public void onCreate() {
        if (publishedAt == null) publishedAt = Instant.now();
    }
}
