package com.clinicops.app.web;

import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.ProcurementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/procurement")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
public class ProcurementController {

    private final ProcurementService procurementService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(@RequestParam(value = "view", required = false) String view, Model model) {
        boolean showAll = "all".equalsIgnoreCase(view);
        model.addAttribute("items", showAll
                ? procurementService.all(currentUser.clinicId())
                : procurementService.pending(currentUser.clinicId()));
        model.addAttribute("showAll", showAll);
        model.addAttribute("statuses", ProcurementItem.Status.values());
        return "procurement/list";
    }

    @PostMapping("/add")
    public String addManual(@RequestParam String medicineName,
                            @RequestParam(required = false) String notes) {
        if (medicineName != null && !medicineName.isBlank()) {
            procurementService.addManual(currentUser.clinicId(), currentUser.userId(),
                    medicineName.trim(), notes);
        }
        return "redirect:/procurement";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id, @RequestParam ProcurementItem.Status status) {
        procurementService.updateStatus(id, status);
        return "redirect:/procurement";
    }
}
