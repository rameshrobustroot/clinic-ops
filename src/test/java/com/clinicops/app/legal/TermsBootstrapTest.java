package com.clinicops.app.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.repository.TermsDocumentRepository;
import com.clinicops.app.service.TermsService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** {@link TermsBootstrap} is {@code @Profile("!test")}, so it is constructed by hand. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TermsBootstrapTest {

    @Autowired TermsService termsService;
    @Autowired TermsDocumentRepository repository;

    @Test
    void seeds_initial_document_from_classpath_resource() {
        new TermsBootstrap(termsService).run();

        TermsDocument doc = termsService.getCurrent().orElseThrow();
        assertThat(doc.getVersion()).isEqualTo("2026-05-24");
        assertThat(doc.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 5, 24));
        assertThat(doc.getBody()).isNotBlank();
    }

    @Test
    void initial_version_matches_TermsVersion_constant() {
        new TermsBootstrap(termsService).run();

        assertThat(termsService.getCurrent()).get()
                .extracting(TermsDocument::getVersion).isEqualTo(TermsVersion.CURRENT);
    }

    @Test
    void running_twice_does_not_duplicate() {
        TermsBootstrap bootstrap = new TermsBootstrap(termsService);

        bootstrap.run();
        bootstrap.run();

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void does_not_seed_when_a_document_was_already_published() {
        termsService.publish("2027-01-01", "<p>newer</p>", LocalDate.of(2027, 1, 1), 1L);

        new TermsBootstrap(termsService).run();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(termsService.getCurrent()).get()
                .extracting(TermsDocument::getVersion).isEqualTo("2027-01-01");
    }
}
