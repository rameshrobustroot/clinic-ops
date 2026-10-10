package com.clinicops.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the Spring context starts cleanly with the test profile.
 * Catches misconfiguration / missing beans before any other test runs.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Test
    void contextLoads() {
        // No assertions needed — passes if the context starts without exceptions.
    }
}
