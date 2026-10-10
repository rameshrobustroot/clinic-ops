package com.clinicops.app.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class FileStorageService {

    @Value("${clinic.uploads-dir}")
    private String uploadsDir;

    private Path root;

    @PostConstruct
    public void init() throws IOException {
        this.root = Paths.get(uploadsDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Uploads directory: {}", root);
    }

    /** Returns a relative path (yyyy-MM/uuid.ext) suitable for storing in DB. */
    public String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) return null;
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        if (original.contains("..")) throw new IOException("Invalid filename: " + original);
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot > 0) ext = original.substring(dot).toLowerCase();
        String month = LocalDate.now().toString().substring(0, 7); // yyyy-MM
        Path dir = root.resolve(month);
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + ext;
        Path target = dir.resolve(filename);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return month + "/" + filename;
    }

    public Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Path traversal blocked");
        return p;
    }
}
