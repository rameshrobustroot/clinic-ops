package com.clinicops.app.web;

import com.clinicops.app.service.MedicalMasterService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Platform-admin editor for the master lists. Operates only on platform-scoped rows
 * ({@code clinic_id IS NULL}). Clinic-specific rows are not visible here.
 */
@Controller
@RequestMapping("/platform")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class PlatformMasterController {

    private final MedicalMasterService masterService;

    // ===== Degrees ============================================

    @GetMapping("/degrees")
    public String degrees(Model model) {
        model.addAttribute("kind", "degrees");
        model.addAttribute("title", "Platform Degrees");
        model.addAttribute("items", masterService.listPlatformDegrees());
        return "platform/master-list/list";
    }

    @PostMapping("/degrees")
    public String createDegree(@RequestParam("name") String name, RedirectAttributes ra) {
        try { masterService.createPlatformDegree(name); ra.addFlashAttribute("flash", "Added: " + name); }
        catch (IllegalArgumentException e) { ra.addFlashAttribute("flash", "Couldn't add: " + e.getMessage()); }
        return "redirect:/platform/degrees";
    }

    @PostMapping("/degrees/{id}/rename")
    public String renameDegree(@PathVariable Long id, @RequestParam("name") String name,
                                RedirectAttributes ra) {
        ensurePlatform(masterService.getDegreeOrThrow(id).getClinicId());
        try { masterService.renameDegree(id, name); ra.addFlashAttribute("flash", "Renamed."); }
        catch (IllegalArgumentException e) { ra.addFlashAttribute("flash", "Couldn't rename: " + e.getMessage()); }
        return "redirect:/platform/degrees";
    }

    @PostMapping("/degrees/{id}/delete")
    public String softDeleteDegree(@PathVariable Long id, RedirectAttributes ra) {
        ensurePlatform(masterService.getDegreeOrThrow(id).getClinicId());
        masterService.softDeleteDegree(id);
        ra.addFlashAttribute("flash", "Hidden from every clinic's picker.");
        return "redirect:/platform/degrees";
    }

    @PostMapping("/degrees/{id}/restore")
    public String restoreDegree(@PathVariable Long id, RedirectAttributes ra) {
        ensurePlatform(masterService.getDegreeOrThrow(id).getClinicId());
        masterService.restoreDegree(id);
        ra.addFlashAttribute("flash", "Restored.");
        return "redirect:/platform/degrees";
    }

    // ===== Specializations ============================================

    @GetMapping("/specializations")
    public String specs(Model model) {
        model.addAttribute("kind", "specializations");
        model.addAttribute("title", "Platform Specializations");
        model.addAttribute("items", masterService.listPlatformSpecializations());
        return "platform/master-list/list";
    }

    @PostMapping("/specializations")
    public String createSpec(@RequestParam("name") String name, RedirectAttributes ra) {
        try { masterService.createPlatformSpecialization(name); ra.addFlashAttribute("flash", "Added: " + name); }
        catch (IllegalArgumentException e) { ra.addFlashAttribute("flash", "Couldn't add: " + e.getMessage()); }
        return "redirect:/platform/specializations";
    }

    @PostMapping("/specializations/{id}/rename")
    public String renameSpec(@PathVariable Long id, @RequestParam("name") String name,
                              RedirectAttributes ra) {
        ensurePlatform(masterService.getSpecializationOrThrow(id).getClinicId());
        try { masterService.renameSpecialization(id, name); ra.addFlashAttribute("flash", "Renamed."); }
        catch (IllegalArgumentException e) { ra.addFlashAttribute("flash", "Couldn't rename: " + e.getMessage()); }
        return "redirect:/platform/specializations";
    }

    @PostMapping("/specializations/{id}/delete")
    public String softDeleteSpec(@PathVariable Long id, RedirectAttributes ra) {
        ensurePlatform(masterService.getSpecializationOrThrow(id).getClinicId());
        masterService.softDeleteSpecialization(id);
        ra.addFlashAttribute("flash", "Hidden from every clinic's picker.");
        return "redirect:/platform/specializations";
    }

    @PostMapping("/specializations/{id}/restore")
    public String restoreSpec(@PathVariable Long id, RedirectAttributes ra) {
        ensurePlatform(masterService.getSpecializationOrThrow(id).getClinicId());
        masterService.restoreSpecialization(id);
        ra.addFlashAttribute("flash", "Restored.");
        return "redirect:/platform/specializations";
    }

    // ===== Investigations ============================================

    @GetMapping("/investigations")
    public String inv(Model model) {
        model.addAttribute("kind", "investigations");
        model.addAttribute("title", "Platform Investigations");
        model.addAttribute("items", masterService.listPlatformInvestigations());
        return "platform/master-list/list";
    }

    @PostMapping("/investigations")
    public String createInv(@RequestParam("name") String name, RedirectAttributes ra) {
        try { masterService.createPlatformInvestigation(name); ra.addFlashAttribute("flash", "Added: " + name); }
        catch (IllegalArgumentException e) { ra.addFlashAttribute("flash", "Couldn't add: " + e.getMessage()); }
        return "redirect:/platform/investigations";
    }

    @PostMapping("/investigations/{id}/rename")
    public String renameInv(@PathVariable Long id, @RequestParam("name") String name,
                             RedirectAttributes ra) {
        ensurePlatform(masterService.getInvestigationOrThrow(id).getClinicId());
        try { masterService.renameInvestigation(id, name); ra.addFlashAttribute("flash", "Renamed."); }
        catch (IllegalArgumentException e) { ra.addFlashAttribute("flash", "Couldn't rename: " + e.getMessage()); }
        return "redirect:/platform/investigations";
    }

    @PostMapping("/investigations/{id}/delete")
    public String softDeleteInv(@PathVariable Long id, RedirectAttributes ra) {
        ensurePlatform(masterService.getInvestigationOrThrow(id).getClinicId());
        masterService.softDeleteInvestigation(id);
        ra.addFlashAttribute("flash", "Hidden from every clinic's picker.");
        return "redirect:/platform/investigations";
    }

    @PostMapping("/investigations/{id}/restore")
    public String restoreInv(@PathVariable Long id, RedirectAttributes ra) {
        ensurePlatform(masterService.getInvestigationOrThrow(id).getClinicId());
        masterService.restoreInvestigation(id);
        ra.addFlashAttribute("flash", "Restored.");
        return "redirect:/platform/investigations";
    }

    /** Guard against editing a clinic-scoped row through a platform endpoint. */
    private static void ensurePlatform(Long rowClinicId) {
        if (rowClinicId != null) {
            throw new IllegalArgumentException(
                    "This row belongs to a specific clinic and can't be edited as a platform value.");
        }
    }
}
