package com.clinicops.app.service;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.repository.TermsDocumentRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the lifecycle of the Terms of Service / Privacy / Disclaimer document.
 *
 * <p>Publishing a new version appends a row; old rows stay for audit. The "current"
 * version is the row with the most recent {@code publishedAt}. A clinic's
 * {@code termsVersion} field is compared against the current version to decide whether
 * the clinic owner needs to re-accept before continuing to use the app.
 */
@Service
@RequiredArgsConstructor
public class TermsService {

    private final TermsDocumentRepository repository;

    @Transactional(readOnly = true)
    public Optional<TermsDocument> getCurrent() {
        return repository.findFirstByOrderByPublishedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<TermsDocument> listAll() {
        return repository.findAllByOrderByPublishedAtDesc();
    }

    /** True when the clinic accepted an older version (or never accepted) than the current. */
    @Transactional(readOnly = true)
    public boolean requiresAcceptance(Clinic clinic) {
        if (clinic == null) return false;
        Optional<TermsDocument> current = getCurrent();
        if (current.isEmpty()) return false; // nothing published yet — don't gate
        String acceptedVersion = clinic.getTermsVersion();
        if (acceptedVersion == null || acceptedVersion.isBlank()) return true;
        return !current.get().getVersion().equals(acceptedVersion);
    }

    @Transactional
    public TermsDocument publish(String version, String body, LocalDate effectiveDate, Long publishedByUserId) {
        if (version == null || version.isBlank())   throw new IllegalArgumentException("Version is required");
        if (body == null || body.isBlank())         throw new IllegalArgumentException("Body is required");
        if (effectiveDate == null)                  throw new IllegalArgumentException("Effective date is required");
        if (repository.findByVersion(version.trim()).isPresent()) {
            throw new IllegalArgumentException("Version '" + version.trim() + "' is already published. Choose a new label.");
        }
        return repository.save(TermsDocument.builder()
                .version(version.trim())
                .body(body)
                .effectiveDate(effectiveDate)
                .publishedByUserId(publishedByUserId)
                .build());
    }

    /** Idempotently seed an initial document. Used by {@code TermsBootstrap}. */
    @Transactional
    public void seedIfEmpty(String version, String body, LocalDate effectiveDate) {
        if (repository.count() > 0) return;
        repository.save(TermsDocument.builder()
                .version(version)
                .body(body)
                .effectiveDate(effectiveDate)
                .build());
    }
}
