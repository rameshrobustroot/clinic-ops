package com.clinicops.app.web;

import com.clinicops.app.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    @GetMapping("/register")
    public String form(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new RegistrationService.RegistrationForm());
        }
        return "auth/register";
    }

    @PostMapping("/register")
    public String submit(@ModelAttribute("form") RegistrationService.RegistrationForm form,
                         Model model) {
        if (form.getPassword() == null
                || !form.getPassword().equals(form.getConfirmPassword())) {
            model.addAttribute("error", "Password and confirmation do not match.");
            return "auth/register";
        }
        try {
            var clinic = registrationService.register(form);
            return "redirect:/login?registered&code=" + clinic.getCode();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "auth/register";
        }
    }
}
