package com.clinicops.app.web;

import com.clinicops.app.service.MedicalMasterService;
import com.clinicops.app.service.TermsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** PLATFORM_ADMIN landing page — entry point to all platform-management screens. */
@Controller
@RequestMapping("/platform")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class PlatformController {

    private final TermsService termsService;
    private final MedicalMasterService masterService;

    @GetMapping
    public String home(Model model) {
        model.addAttribute("currentTerms", termsService.getCurrent().orElse(null));
        model.addAttribute("degreeCount",         masterService.listPlatformDegrees().size());
        model.addAttribute("specializationCount", masterService.listPlatformSpecializations().size());
        model.addAttribute("investigationCount",  masterService.listPlatformInvestigations().size());
        return "platform/home";
    }
}
