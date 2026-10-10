package com.clinicops.app.web;

import com.clinicops.app.domain.TermsDocument;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.service.NotFoundException;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin CRUD for the published Terms of Service / Privacy / Disclaimer document.
 * Gated by {@code ROLE_PLATFORM_ADMIN} — the software publisher's identity, distinct
 * from any clinic operator. Clinic owners can <em>accept</em> terms but cannot edit them.
 */
@Controller
@RequestMapping("/platform/terms")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class AdminTermsController {

    private final TermsService termsService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("versions", termsService.listAll());
        model.addAttribute("current", termsService.getCurrent().orElse(null));
        model.addAttribute("today", LocalDate.now().toString());
        return "admin/terms/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        TermsDocument latest = termsService.getCurrent().orElse(null);
        model.addAttribute("latestBody", latest != null ? latest.getBody() : "");
        model.addAttribute("today", LocalDate.now().toString());
        return "admin/terms/new";
    }

    @PostMapping
    public String publish(@RequestParam String version,
                          @RequestParam String body,
                          @RequestParam("effectiveDate") String effectiveDate,
                          RedirectAttributes ra) {
        try {
            LocalDate effective = LocalDate.parse(effectiveDate);
            termsService.publish(version, body, effective, currentUser.userId());
            ra.addFlashAttribute("flash",
                    "Published " + version + ". All clinic owners will be prompted to re-accept on their next request.");
            return "redirect:/platform/terms";
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            ra.addFlashAttribute("error", e.getMessage());
            ra.addFlashAttribute("formVersion", version);
            ra.addFlashAttribute("formBody", body);
            ra.addFlashAttribute("formEffectiveDate", effectiveDate);
            return "redirect:/platform/terms/new";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        TermsDocument doc = termsService.listAll().stream()
                .filter(d -> id.equals(d.getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Version not found: " + id));
        model.addAttribute("doc", doc);
        return "admin/terms/view";
    }
}
