package com.clinicops.app.service;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import com.clinicops.app.repository.AppUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<AppUser> listForClinic(Long clinicId) {
        return userRepository.findByClinicIdOrderByFullNameAsc(clinicId);
    }

    /**
     * Users who can be assigned as the treating doctor on a visit. Owners qualify because
     * in single-doctor clinics the owner *is* the practitioner; receptionists / pharmacists
     * are filtered out.
     */
    @Transactional(readOnly = true)
    public List<AppUser> listDoctors(Long clinicId) {
        return userRepository.findByClinicIdOrderByFullNameAsc(clinicId).stream()
                .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
                .filter(u -> u.getRole() == Role.OWNER || u.getRole() == Role.DOCTOR)
                .toList();
    }

    /**
     * Roles a clinic owner may assign. PLATFORM_ADMIN is deliberately excluded — it is only ever
     * created by {@link com.clinicops.app.security.Bootstrap}; letting a clinic mint one would be a
     * privilege escalation into the platform console.
     */
    public static final List<Role> ASSIGNABLE_ROLES =
            List.of(Role.OWNER, Role.DOCTOR, Role.PHARMACIST, Role.RECEPTIONIST);

    private static final java.util.regex.Pattern PHONE_REGEX =
            java.util.regex.Pattern.compile(com.clinicops.app.util.ValidationPatterns.PHONE_INDIA_OPTIONAL);

    @Transactional
    public AppUser create(Long clinicId, String username, String fullName, Role role,
                          String phone, String plainPassword) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Role cannot be assigned: " + role);
        }
        String normalizedUsername = username.trim().toLowerCase();
        if (userRepository.findByClinicIdAndUsername(clinicId, normalizedUsername).isPresent()) {
            throw new IllegalArgumentException("Username already taken: " + normalizedUsername);
        }
        if (plainPassword == null || plainPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (phone != null && !phone.isBlank() && !PHONE_REGEX.matcher(phone).matches()) {
            throw new IllegalArgumentException("Phone must be a 10-digit mobile starting with 6/7/8/9");
        }
        AppUser user = AppUser.builder()
                .clinicId(clinicId)
                .username(normalizedUsername)
                .fullName(fullName.trim())
                .role(role)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(plainPassword))
                .enabled(true)
                .build();
        return userRepository.save(user);
    }

    public static final java.util.Set<String> VALID_THEMES =
            java.util.Set.of("violet", "sky", "indigo");

    @Transactional
    public AppUser updateOwnProfile(Long userId, String fullName, String phone) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        com.clinicops.app.tenant.TenantGuard.check(user, AppUser::getClinicId);
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        if (phone != null && !phone.isBlank() && !PHONE_REGEX.matcher(phone).matches()) {
            throw new IllegalArgumentException("Phone must be a 10-digit Indian mobile");
        }
        user.setFullName(fullName.trim());
        user.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        return userRepository.save(user);
    }

    @Transactional
    public void updateTheme(Long userId, String theme) {
        if (theme == null || !VALID_THEMES.contains(theme)) {
            throw new IllegalArgumentException("Unknown theme: " + theme);
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        com.clinicops.app.tenant.TenantGuard.check(user, AppUser::getClinicId);
        user.setTheme(theme);
        userRepository.save(user);
    }

    @Transactional
    public void setEnabled(Long userId, boolean enabled) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        com.clinicops.app.tenant.TenantGuard.check(user, AppUser::getClinicId);
        user.setEnabled(enabled);
        userRepository.save(user);
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("New password must be at least 6 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    /**
     * Admin password reset. Used by an OWNER to set a new password for any user in their clinic
     * (e.g. when that user forgot theirs). No current-password check — the caller authorises this.
     */
    @Transactional
    public void resetPassword(Long actingOwnerClinicId, Long targetUserId, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("New password must be at least 6 characters");
        }
        AppUser user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found: " + targetUserId));
        // Tenant safety: an owner can only reset passwords for users in their own clinic.
        if (!actingOwnerClinicId.equals(user.getClinicId())) {
            throw new IllegalArgumentException("Cannot reset a user from a different clinic");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
