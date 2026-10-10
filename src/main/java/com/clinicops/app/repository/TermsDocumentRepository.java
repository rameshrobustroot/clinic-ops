package com.clinicops.app.repository;

import com.clinicops.app.domain.TermsDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsDocumentRepository extends JpaRepository<TermsDocument, Long> {
    Optional<TermsDocument> findFirstByOrderByPublishedAtDesc();
    List<TermsDocument> findAllByOrderByPublishedAtDesc();
    Optional<TermsDocument> findByVersion(String version);
}
