package com.clinicops.app.legal;

import com.clinicops.app.service.TermsService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

/**
 * Inserts the initial terms_document row on first run by loading the legal text from a
 * classpath HTML resource. After this, all updates flow through the in-app publish form.
 */
@Component
@org.springframework.context.annotation.Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class TermsBootstrap implements CommandLineRunner {

    private static final String INITIAL_VERSION    = "2026-05-24";
    private static final LocalDate INITIAL_EFFECTIVE = LocalDate.parse(INITIAL_VERSION);
    private static final String INITIAL_RESOURCE  = "legal/terms-initial.html";

    private final TermsService termsService;

    @Override
    public void run(String... args) {
        try {
            ClassPathResource resource = new ClassPathResource(INITIAL_RESOURCE);
            String body = FileCopyUtils.copyToString(
                    new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
            termsService.seedIfEmpty(INITIAL_VERSION, body, INITIAL_EFFECTIVE);
        } catch (IOException e) {
            log.warn("Could not seed initial terms document from {}: {}", INITIAL_RESOURCE, e.getMessage());
        }
    }
}
