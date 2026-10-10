package com.clinicops.app.service;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Role;
import com.clinicops.app.legal.TermsVersion;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import java.time.Instant;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public clinic onboarding. Creates a new {@link Clinic} and its first OWNER {@link AppUser}
 * atomically.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final Pattern CLINIC_CODE  = Pattern.compile("^[a-z0-9_-]{3,32}$");
    private static final Pattern USERNAME     = Pattern.compile("^[a-z0-9._-]{3,32}$");
    private static final Pattern PHONE        = Pattern.compile("^$|^[6-9][0-9]{9}$");
    private static final Pattern SLUG_CLEAN   = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SLUG_TRIM    = Pattern.compile("^-+|-+$");
    private static final int MAX_SLUG_LEN     = 28; // leave room for "-NN" suffix

    private final ClinicRepository clinicRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TermsService termsService;

    @Transactional
    public Clinic register(RegistrationForm form) {
        if (form.getClinicName() == null || form.getClinicName().isBlank()) {
            throw new IllegalArgumentException("Clinic name is required.");
        }
        // Auto-generate a unique clinic code from the clinic name.
        String code = generateUniqueCode(form.getClinicName());

        String username = form.getUsername() == null ? "" : form.getUsername().trim().toLowerCase();
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3-32 lowercase letters/digits/._-");
        }
        if (form.getPassword() == null || form.getPassword().length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters.");
        }
        if (form.getOwnerFullName() == null || form.getOwnerFullName().isBlank()) {
            throw new IllegalArgumentException("Owner name is required.");
        }
        String phone = form.getOwnerPhone() == null ? "" : form.getOwnerPhone().trim();
        if (!PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException(
                    "Owner phone must be a 10-digit Indian mobile (or leave blank).");
        }
        if (!form.isAcceptTerms()) {
            throw new IllegalArgumentException(
                    "You must accept the Terms of Service, Privacy Policy and Healthcare Disclaimer to register.");
        }

        String currentVersion = termsService.getCurrent()
                .map(com.clinicops.app.domain.TermsDocument::getVersion)
                .orElse(TermsVersion.CURRENT);

        Clinic clinic = clinicRepository.save(Clinic.builder()
                .code(code)
                .name(form.getClinicName().trim())
                .phone(form.getClinicPhone() == null || form.getClinicPhone().isBlank()
                        ? null : form.getClinicPhone().trim())
                .address(form.getClinicAddress() == null || form.getClinicAddress().isBlank()
                        ? null : form.getClinicAddress().trim())
                .termsVersion(currentVersion)
                .termsAcceptedAt(Instant.now())
                .build());

        userRepository.save(AppUser.builder()
                .clinicId(clinic.getId())
                .username(username)
                .fullName(form.getOwnerFullName().trim())
                .role(Role.OWNER)
                .phone(phone.isBlank() ? null : phone)
                .passwordHash(passwordEncoder.encode(form.getPassword()))
                .enabled(true)
                .build());

        return clinic;
    }

    /**
     * Slugify the clinic name and find the first unused variant. Falls back to a random
     * suffix if 99 numeric attempts all collide (extremely unlikely).
     */
    String generateUniqueCode(String name) {
        String base = SLUG_CLEAN.matcher(name.toLowerCase()).replaceAll("-");
        base = SLUG_TRIM.matcher(base).replaceAll("");
        if (base.length() > MAX_SLUG_LEN) base = base.substring(0, MAX_SLUG_LEN);
        if (!CLINIC_CODE.matcher(base).matches()) {
            base = "clinic";
        }
        if (!clinicRepository.existsByCode(base)) {
            return base;
        }
        for (int i = 2; i <= 99; i++) {
            String candidate = base + "-" + i;
            if (!clinicRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        String suffix = Long.toString(System.nanoTime() % 100000L, 36);
        return base + "-" + suffix;
    }

    @lombok.Data
    public static class RegistrationForm {
        private String clinicCode;
        private String clinicName;
        private String clinicPhone;
        private String clinicAddress;
        private String ownerFullName;
        private String username;
        private String password;
        private String confirmPassword;
        private String ownerPhone;
        private boolean acceptTerms;
    }
}
