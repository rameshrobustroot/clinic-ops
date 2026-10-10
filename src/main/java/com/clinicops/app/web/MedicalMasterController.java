package com.clinicops.app.web;

import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.MedicalMasterService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Clinic-OWNER view of the master lists. Shows the merged catalogue (platform rows +
 * this clinic's own additions) but only allows mutation on clinic-scoped rows. Attempts
 * to rename/delete a platform row are rejected with an explanatory flash message.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class MedicalMasterController {

    private final MedicalMasterService masterService;
    private final CurrentUser currentUser;

    // ===== Degrees =====================================================

    @GetMapping("/degrees")
    public String listDegrees(Model model) {
        model.addAttribute("kind", "degrees");
        model.addAttribute("title", "Medical Degrees");
        model.addAttribute("items", masterService.listAllDegrees(currentUser.clinicId()));
        return "master-list/list";
    }

    @PostMapping("/degrees")
    public String createDegree(@RequestParam("name") String name, RedirectAttributes ra) {
        try {
            var result = masterService.createClinicDegree(currentUser.clinicId(), name);
            if (result.getClinicId() == null) {
                ra.addFlashAttribute("flashInfo", "'" + result.getName() + "' is already in the platform list — it's available to all clinics.");
            } else {
                ra.addFlashAttribute("flash", "Added: " + result.getName());
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "Couldn't add: " + e.getMessage());
        }
        return "redirect:/degrees";
    }

    @PostMapping("/degrees/{id}/rename")
    public String renameDegree(@PathVariable Long id, @RequestParam("name") String name,
                                RedirectAttributes ra) {
        if (isPlatformRow(masterService.getDegreeOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values are managed by the software publisher and can't be edited here.");
            return "redirect:/degrees";
        }
        try {
            masterService.renameDegree(id, name);
            ra.addFlashAttribute("flash", "Renamed.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "Couldn't rename: " + e.getMessage());
        }
        return "redirect:/degrees";
    }

    @PostMapping("/degrees/{id}/delete")
    public String softDeleteDegree(@PathVariable Long id, RedirectAttributes ra) {
        if (isPlatformRow(masterService.getDegreeOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values can't be hidden by individual clinics.");
            return "redirect:/degrees";
        }
        masterService.softDeleteDegree(id);
        ra.addFlashAttribute("flash", "Hidden from picker.");
        return "redirect:/degrees";
    }

    @PostMapping("/degrees/{id}/restore")
    public String restoreDegree(@PathVariable Long id, RedirectAttributes ra) {
        if (isPlatformRow(masterService.getDegreeOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values are managed by the software publisher.");
            return "redirect:/degrees";
        }
        masterService.restoreDegree(id);
        ra.addFlashAttribute("flash", "Restored.");
        return "redirect:/degrees";
    }

    // ===== Specializations ============================================

    @GetMapping("/specializations")
    public String listSpecs(Model model) {
        model.addAttribute("kind", "specializations");
        model.addAttribute("title", "Specializations");
        model.addAttribute("items", masterService.listAllSpecializations(currentUser.clinicId()));
        return "master-list/list";
    }

    @PostMapping("/specializations")
    public String createSpec(@RequestParam("name") String name, RedirectAttributes ra) {
        try {
            var result = masterService.createClinicSpecialization(currentUser.clinicId(), name);
            if (result.getClinicId() == null) {
                ra.addFlashAttribute("flashInfo", "'" + result.getName() + "' is already in the platform list — it's available to all clinics.");
            } else {
                ra.addFlashAttribute("flash", "Added: " + result.getName());
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "Couldn't add: " + e.getMessage());
        }
        return "redirect:/specializations";
    }

    @PostMapping("/specializations/{id}/rename")
    public String renameSpec(@PathVariable Long id, @RequestParam("name") String name,
                              RedirectAttributes ra) {
        if (isPlatformRow(masterService.getSpecializationOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values are managed by the software publisher and can't be edited here.");
            return "redirect:/specializations";
        }
        try {
            masterService.renameSpecialization(id, name);
            ra.addFlashAttribute("flash", "Renamed.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "Couldn't rename: " + e.getMessage());
        }
        return "redirect:/specializations";
    }

    @PostMapping("/specializations/{id}/delete")
    public String softDeleteSpec(@PathVariable Long id, RedirectAttributes ra) {
        if (isPlatformRow(masterService.getSpecializationOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values can't be hidden by individual clinics.");
            return "redirect:/specializations";
        }
        masterService.softDeleteSpecialization(id);
        ra.addFlashAttribute("flash", "Hidden from picker.");
        return "redirect:/specializations";
    }

    @PostMapping("/specializations/{id}/restore")
    public String restoreSpec(@PathVariable Long id, RedirectAttributes ra) {
        if (isPlatformRow(masterService.getSpecializationOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values are managed by the software publisher.");
            return "redirect:/specializations";
        }
        masterService.restoreSpecialization(id);
        ra.addFlashAttribute("flash", "Restored.");
        return "redirect:/specializations";
    }

    // ===== Investigations ============================================

    @GetMapping("/investigations")
    public String listInv(Model model) {
        model.addAttribute("kind", "investigations");
        model.addAttribute("title", "Investigations");
        model.addAttribute("items", masterService.listAllInvestigations(currentUser.clinicId()));
        return "master-list/list";
    }

    @PostMapping("/investigations")
    public String createInv(@RequestParam("name") String name, RedirectAttributes ra) {
        try {
            var result = masterService.createClinicInvestigation(currentUser.clinicId(), name);
            if (result.getClinicId() == null) {
                ra.addFlashAttribute("flashInfo", "'" + result.getName() + "' is already in the platform list — it's available to all clinics.");
            } else {
                ra.addFlashAttribute("flash", "Added: " + result.getName());
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "Couldn't add: " + e.getMessage());
        }
        return "redirect:/investigations";
    }

    @PostMapping("/investigations/{id}/rename")
    public String renameInv(@PathVariable Long id, @RequestParam("name") String name,
                             RedirectAttributes ra) {
        if (isPlatformRow(masterService.getInvestigationOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values are managed by the software publisher and can't be edited here.");
            return "redirect:/investigations";
        }
        try {
            masterService.renameInvestigation(id, name);
            ra.addFlashAttribute("flash", "Renamed.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "Couldn't rename: " + e.getMessage());
        }
        return "redirect:/investigations";
    }

    @PostMapping("/investigations/{id}/delete")
    public String softDeleteInv(@PathVariable Long id, RedirectAttributes ra) {
        if (isPlatformRow(masterService.getInvestigationOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values can't be hidden by individual clinics.");
            return "redirect:/investigations";
        }
        masterService.softDeleteInvestigation(id);
        ra.addFlashAttribute("flash", "Hidden from picker.");
        return "redirect:/investigations";
    }

    @PostMapping("/investigations/{id}/restore")
    public String restoreInv(@PathVariable Long id, RedirectAttributes ra) {
        if (isPlatformRow(masterService.getInvestigationOrThrow(id).getClinicId())) {
            ra.addFlashAttribute("flash", "Platform values are managed by the software publisher.");
            return "redirect:/investigations";
        }
        masterService.restoreInvestigation(id);
        ra.addFlashAttribute("flash", "Restored.");
        return "redirect:/investigations";
    }

    private static boolean isPlatformRow(Long rowClinicId) {
        return rowClinicId == null;
    }
}
