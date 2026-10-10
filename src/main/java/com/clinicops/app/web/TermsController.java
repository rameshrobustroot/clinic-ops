package com.clinicops.app.web;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.legal.TermsVersion;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.TermsService;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Public Terms page + the forced re-acceptance flow for clinics on an older version. */
@Controller
@RequiredArgsConstructor
public class TermsController {

    private final TermsService termsService;
    private final ClinicRepository clinicRepository;
    private final CurrentUser currentUser;

    @GetMapping("/terms")
    public String terms(Model model) {
        Optional<TermsDocument> current = termsService.getCurrent();
        // Fall back to the legacy seed metadata when the DB has nothing yet (e.g. tests).
        model.addAttribute("termsVersion",
                current.map(TermsDocument::getVersion).orElse(TermsVersion.CURRENT));
        model.addAttribute("termsBody",
                current.map(TermsDocument::getBody).orElse(""));
        model.addAttribute("termsEffectiveDate",
                current.map(TermsDocument::getEffectiveDate).orElse(null));
        return "legal/terms";
    }

    /**
     * Interstitial shown to a logged-in OWNER whose clinic accepted an older version
     * (or never accepted). The interceptor redirects them here before any other page.
     */
    @PreAuthorize("hasRole('OWNER')")
    @GetMapping("/terms/accept")
    public String acceptForm(Model model) {
        Optional<TermsDocument> current = termsService.getCurrent();
        Clinic clinic = clinicRepository.findById(currentUser.clinicId()).orElseThrow();
        model.addAttribute("clinic", clinic);
        model.addAttribute("termsBody", current.map(TermsDocument::getBody).orElse(""));
        model.addAttribute("termsVersion", current.map(TermsDocument::getVersion).orElse(""));
        model.addAttribute("termsEffectiveDate", current.map(TermsDocument::getEffectiveDate).orElse(null));
        return "legal/accept";
    }

    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/terms/accept")
    public String accept(@RequestParam(value = "acceptTerms", required = false) Boolean accepted,
                         RedirectAttributes ra) {
        if (!Boolean.TRUE.equals(accepted)) {
            ra.addFlashAttribute("error",
                    "You must tick the acknowledgement to continue using ClinicOps.");
            return "redirect:/terms/accept";
        }
        Optional<TermsDocument> current = termsService.getCurrent();
        if (current.isEmpty()) return "redirect:/";
        Clinic clinic = clinicRepository.findById(currentUser.clinicId()).orElseThrow();
        clinic.setTermsVersion(current.get().getVersion());
        clinic.setTermsAcceptedAt(Instant.now());
        clinicRepository.save(clinic);
        ra.addFlashAttribute("flash", "Terms accepted. Thank you.");
        return "redirect:/";
    }
}
