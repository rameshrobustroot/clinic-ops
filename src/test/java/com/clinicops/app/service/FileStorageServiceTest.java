package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

/** Plain unit tests (no Spring) — the uploads root is pointed at a JUnit temp dir. */
class FileStorageServiceTest {

    @TempDir Path tempDir;

    FileStorageService service;
    Path root;
    String month;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("uploads").resolve("nested");
        service = new FileStorageService();
        ReflectionTestUtils.setField(service, "uploadsDir", root.toString());
        service.init();
        month = LocalDate.now().toString().substring(0, 7);
    }

    @Test
    void init_creates_the_uploads_directory_including_parents() {
        assertThat(root).isDirectory();
    }

    @Nested
    class Store {

        @Test
        void null_file_returns_null() throws IOException {
            assertThat(service.store(null)).isNull();
        }

        @Test
        void empty_file_returns_null_and_writes_nothing() throws IOException {
            MockMultipartFile empty = new MockMultipartFile("f", "rx.pdf", "application/pdf", new byte[0]);
            assertThat(service.store(empty)).isNull();
            assertThat(root.resolve(month)).doesNotExist();
        }

        @Test
        void stores_content_under_month_folder_with_uuid_name_and_lowercased_extension() throws IOException {
            MockMultipartFile f = new MockMultipartFile("f", "Scan.PDF", "application/pdf",
                    "hello".getBytes(StandardCharsets.UTF_8));

            String rel = service.store(f);

            assertThat(rel).matches(month + "/[0-9a-f\\-]{36}\\.pdf");
            assertThat(Files.readString(root.resolve(rel))).isEqualTo("hello");
        }

        @Test
        void filename_without_extension_gets_no_extension() throws IOException {
            String rel = service.store(new MockMultipartFile("f", "README", null, new byte[]{1}));
            assertThat(rel).matches(month + "/[0-9a-f\\-]{36}");
        }

        @Test
        void dotfile_is_not_treated_as_extension() throws IOException {
            // dot at index 0 -> the "dot > 0" guard means no extension is kept
            String rel = service.store(new MockMultipartFile("f", ".hidden", null, new byte[]{1}));
            assertThat(rel).matches(month + "/[0-9a-f\\-]{36}");
        }

        @Test
        void only_the_last_extension_is_kept() throws IOException {
            String rel = service.store(new MockMultipartFile("f", "report.final.JPG", null, new byte[]{1}));
            assertThat(rel).endsWith(".jpg").doesNotContain("final");
        }

        @Test
        void two_uploads_with_same_name_do_not_collide() throws IOException {
            String a = service.store(new MockMultipartFile("f", "x.png", null, new byte[]{1}));
            String b = service.store(new MockMultipartFile("f", "x.png", null, new byte[]{2}));
            assertThat(a).isNotEqualTo(b);
            assertThat(Files.readAllBytes(root.resolve(a))).containsExactly(1);
            assertThat(Files.readAllBytes(root.resolve(b))).containsExactly(2);
        }

        @Test
        void path_traversal_in_original_filename_is_rejected() {
            MockMultipartFile evil = new MockMultipartFile("f", "../evil.txt", null, new byte[]{1});
            assertThatThrownBy(() -> service.store(evil))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid filename");
        }
    }

    @Nested
    class Resolve {

        @Test
        void resolves_relative_path_inside_root() {
            Path p = service.resolve("2026-01/abc.pdf");
            assertThat(p).isEqualTo(root.toAbsolutePath().normalize().resolve("2026-01/abc.pdf"));
        }

        @Test
        void blocks_paths_escaping_the_root() {
            assertThatThrownBy(() -> service.resolve("../../etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Path traversal");
        }

        @Test
        void round_trips_a_stored_file() throws IOException {
            String rel = service.store(new MockMultipartFile("f", "a.txt", null,
                    "data".getBytes(StandardCharsets.UTF_8)));
            assertThat(Files.readString(service.resolve(rel))).isEqualTo("data");
        }
    }
}
