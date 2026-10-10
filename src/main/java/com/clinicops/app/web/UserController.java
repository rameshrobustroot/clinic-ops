package com.clinicops.app.web;

import com.clinicops.app.domain.Role;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CurrentUser currentUser;

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public String list(Model model) {
        model.addAttribute("users", userService.listForClinic(currentUser.clinicId()));
        return "user/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('OWNER')")
    public String newForm(Model model) {
        model.addAttribute("roles", UserService.ASSIGNABLE_ROLES);
        return "user/form";
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public String create(@RequestParam String username,
                         @RequestParam String fullName,
                         @RequestParam Role role,
                         @RequestParam(required = false) String phone,
                         @RequestParam String password,
                         Model model) {
        try {
            if (!UserService.ASSIGNABLE_ROLES.contains(role)) {
                throw new IllegalArgumentException("Role cannot be assigned: " + role);
            }
            userService.create(currentUser.clinicId(), username, fullName, role, phone, password);
            return "redirect:/users";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("roles", UserService.ASSIGNABLE_ROLES);
            return "user/form";
        }
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasRole('OWNER')")
    public String toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        userService.setEnabled(id, enabled);
        return "redirect:/users";
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('OWNER')")
    public String resetPassword(@PathVariable Long id, @RequestParam String newPassword,
                                org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            userService.resetPassword(currentUser.clinicId(), id, newPassword);
            ra.addFlashAttribute("flash", "Password reset.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "Couldn't reset: " + e.getMessage());
        }
        return "redirect:/users";
    }
}
