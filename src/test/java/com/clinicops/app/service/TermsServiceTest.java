package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.repository.TermsDocumentRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TermsServiceTest {

    @Autowired TermsService termsService;
    @Autowired TermsDocumentRepository repository;

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 5, 24);

    /** Saves a document with an explicit publishedAt so "current" ordering is deterministic. */
    private TermsDocument doc(String version, Instant publishedAt) {
        return repository.save(TermsDocument.builder()
                .version(version).body("<p>" + version + "</p>")
                .effectiveDate(EFFECTIVE).publishedAt(publishedAt).build());
    }

    private Clinic clinicAccepted(String version) {
        return Clinic.builder().code("t").name("T").termsVersion(version).build();
    }

    @Nested
    class Current {

        @Test
        void get_current_is_empty_when_nothing_published() {
            assertThat(termsService.getCurrent()).isEmpty();
        }

        @Test
        void get_current_returns_most_recently_published() {
            doc("v1", Instant.parse("2026-01-01T00:00:00Z"));
            doc("v3", Instant.parse("2026-03-01T00:00:00Z"));
            doc("v2", Instant.parse("2026-02-01T00:00:00Z"));

            assertThat(termsService.getCurrent()).get()
                    .extracting(TermsDocument::getVersion).isEqualTo("v3");
            assertThat(termsService.listAll()).extracting(TermsDocument::getVersion)
                    .containsExactly("v3", "v2", "v1");
        }
    }

    @Nested
    class RequiresAcceptance {

        @Test
        void null_clinic_never_requires_acceptance() {
            doc("v1", Instant.now());
            assertThat(termsService.requiresAcceptance(null)).isFalse();
        }

        @Test
        void nothing_published_does_not_gate() {
            assertThat(termsService.requiresAcceptance(clinicAccepted(null))).isFalse();
        }

        @Test
        void never_accepted_requires_acceptance() {
            doc("v1", Instant.now());
            assertThat(termsService.requiresAcceptance(clinicAccepted(null))).isTrue();
            assertThat(termsService.requiresAcceptance(clinicAccepted("   "))).isTrue();
        }

        @Test
        void accepted_current_version_does_not_require_acceptance() {
            doc("v1", Instant.parse("2026-01-01T00:00:00Z"));
            doc("v2", Instant.parse("2026-02-01T00:00:00Z"));
            assertThat(termsService.requiresAcceptance(clinicAccepted("v2"))).isFalse();
        }

        @Test
        void accepted_older_version_requires_acceptance() {
            doc("v1", Instant.parse("2026-01-01T00:00:00Z"));
            doc("v2", Instant.parse("2026-02-01T00:00:00Z"));
            assertThat(termsService.requiresAcceptance(clinicAccepted("v1"))).isTrue();
        }

        @Test
        void version_comparison_is_exact() {
            doc("V2", Instant.now());
            assertThat(termsService.requiresAcceptance(clinicAccepted("v2"))).isTrue();
        }
    }

    @Nested
    class Publish {

        @Test
        void publish_trims_version_and_sets_metadata() {
            TermsDocument d = termsService.publish("  2026-06-01 ", "<p>body</p>", EFFECTIVE, 7L);

            assertThat(d.getId()).isNotNull();
            assertThat(d.getVersion()).isEqualTo("2026-06-01");
            assertThat(d.getPublishedByUserId()).isEqualTo(7L);
            assertThat(d.getPublishedAt()).isNotNull();
            assertThat(termsService.getCurrent()).get()
                    .extracting(TermsDocument::getVersion).isEqualTo("2026-06-01");
        }

        @Test
        void publish_rejects_missing_fields() {
            assertThatThrownBy(() -> termsService.publish(" ", "b", EFFECTIVE, 1L))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Version");
            assertThatThrownBy(() -> termsService.publish(null, "b", EFFECTIVE, 1L))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Version");
            assertThatThrownBy(() -> termsService.publish("v", " ", EFFECTIVE, 1L))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Body");
            assertThatThrownBy(() -> termsService.publish("v", "b", null, 1L))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Effective date");
        }

        @Test
        void publish_rejects_duplicate_version_even_with_surrounding_whitespace() {
            termsService.publish("v1", "b", EFFECTIVE, 1L);

            assertThatThrownBy(() -> termsService.publish(" v1 ", "b2", EFFECTIVE, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already published");
            assertThat(repository.count()).isEqualTo(1);
        }

        @Test
        void publishing_new_version_makes_previous_acceptance_stale() {
            doc("v1", Instant.parse("2026-01-01T00:00:00Z"));
            Clinic c = clinicAccepted("v1");
            assertThat(termsService.requiresAcceptance(c)).isFalse();

            termsService.publish("v2", "b", EFFECTIVE, 1L);

            assertThat(termsService.requiresAcceptance(c)).isTrue();
        }
    }

    @Nested
    class Seed {

        @Test
        void seed_if_empty_inserts_once() {
            termsService.seedIfEmpty("seed-1", "<p>x</p>", EFFECTIVE);
            termsService.seedIfEmpty("seed-2", "<p>y</p>", EFFECTIVE);

            assertThat(repository.count()).isEqualTo(1);
            assertThat(termsService.getCurrent()).get()
                    .extracting(TermsDocument::getVersion).isEqualTo("seed-1");
        }

        @Test
        void seed_if_empty_is_noop_when_any_document_exists() {
            doc("existing", Instant.now());

            termsService.seedIfEmpty("seed", "<p>x</p>", EFFECTIVE);

            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findByVersion("seed")).isEmpty();
        }
    }
}
