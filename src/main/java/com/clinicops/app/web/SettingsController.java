package com.clinicops.app.web;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.ClinicProfileService;
import com.clinicops.app.service.FileStorageService;
import com.clinicops.app.service.UserService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ClinicRepository clinicRepository;
    private final AppUserRepository userRepository;
    private final UserService userService;
    private final ClinicProfileService clinicProfileService;
    private final FileStorageService fileStorage;
    private final CurrentUser currentUser;

    @GetMapping
    public String settings(Model model) {
        Clinic clinic = clinicRepository.findById(currentUser.clinicId()).orElse(null);
        model.addAttribute("clinic", clinic);
        model.addAttribute("users", userRepository.findByClinicIdOrderByFullNameAsc(currentUser.clinicId()));
        model.addAttribute("currentTheme", currentUser.get().getTheme());
        return "settings/view";
    }

    @PostMapping("/theme")
    public String updateTheme(@RequestParam("theme") String theme,
                              org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            userService.updateTheme(currentUser.userId(), theme);
            // Spring Security's principal is built at login and cached for the session.
            // Mutate the wrapped AppUser so the new theme shows up immediately on this redirect
            // without forcing a logout/login cycle.
            currentUser.get().getUser().setTheme(theme);
            ra.addFlashAttribute("themeSaved", true);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("themeError", e.getMessage());
        }
        return "redirect:/settings";
    }

    @GetMapping("/profile")
    public String profileForm(Model model) {
        com.clinicops.app.domain.AppUser me = userRepository.findById(currentUser.userId())
                .orElseThrow(() -> new IllegalStateException("Current user not found"));
        com.clinicops.app.tenant.TenantGuard.check(me, com.clinicops.app.domain.AppUser::getClinicId);
        model.addAttribute("me", me);
        return "settings/profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@RequestParam String fullName,
                                @RequestParam(required = false) String phone,
                                Model model,
                                org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            com.clinicops.app.domain.AppUser updated = userService.updateOwnProfile(
                    currentUser.userId(), fullName, phone);
            // Refresh principal so the new values show immediately
            com.clinicops.app.domain.AppUser principal = currentUser.get().getUser();
            principal.setFullName(updated.getFullName());
            principal.setPhone(updated.getPhone());
            ra.addFlashAttribute("success", "Profile updated.");
            return "redirect:/settings/profile";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("me", userRepository.findById(currentUser.userId()).orElseThrow());
            return "settings/profile";
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('OWNER')")
    @GetMapping("/clinic")
    public String clinicProfileForm(Model model) {
        Clinic clinic = clinicProfileService.getOrThrow(currentUser.clinicId());
        model.addAttribute("clinic", clinic);
        return "settings/clinic";
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('OWNER')")
    @PostMapping("/clinic")
    public String updateClinicProfile(@ModelAttribute("form") ClinicProfileService.ProfileForm form,
                                       @RequestParam(value = "logoFile", required = false) MultipartFile logoFile,
                                       org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            clinicProfileService.updateProfile(currentUser.clinicId(), form, logoFile);
            ra.addFlashAttribute("success", "Clinic profile updated.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/clinic";
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('OWNER')")
    @PostMapping("/clinic/logo/remove")
    public String removeLogo(org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        clinicProfileService.removeLogo(currentUser.clinicId());
        ra.addFlashAttribute("success", "Logo removed.");
        return "redirect:/settings/clinic";
    }

    @GetMapping("/clinic/logo")
    public ResponseEntity<UrlResource> clinicLogo() throws IOException {
        Clinic clinic = clinicProfileService.getOrThrow(currentUser.clinicId());
        if (clinic.getLogoPath() == null) return ResponseEntity.notFound().build();
        Path path = fileStorage.resolve(clinic.getLogoPath());
        String contentType = Files.probeContentType(path);
        return ResponseEntity.ok()
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(new UrlResource(path.toUri()));
    }

    @GetMapping("/password")
    public String passwordForm() {
        return "settings/password";
    }

    @PostMapping("/password")
    public String changePassword(@RequestParam("currentPassword") String current,
                                  @RequestParam("newPassword") String next,
                                  @RequestParam("confirmPassword") String confirm,
                                  Model model) {
        if (!next.equals(confirm)) {
            model.addAttribute("error", "New password and confirmation do not match");
            return "settings/password";
        }
        try {
            userService.changePassword(currentUser.userId(), current, next);
            model.addAttribute("success", "Password changed.");
            return "settings/password";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "settings/password";
        }
    }
}
