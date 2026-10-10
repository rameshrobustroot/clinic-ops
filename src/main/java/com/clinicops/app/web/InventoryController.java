package com.clinicops.app.web;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.InventoryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(@RequestParam(value = "q", required = false) String query,
                       @RequestParam(value = "page", defaultValue = "0") int pageNo,
                       Model model) {
        var page = inventoryService.search(currentUser.clinicId(), query, Math.max(0, pageNo));
        List<Medicine> meds = page.getContent();
        model.addAttribute("q", query == null ? "" : query);
        model.addAttribute("pageNo", page.getNumber());
        model.addAttribute("totalPages", page.getTotalPages());
        model.addAttribute("totalElements", page.getTotalElements());
        model.addAttribute("hasPrev", page.hasPrevious());
        model.addAttribute("hasNext", page.hasNext());
        LocalDate today = LocalDate.now();
        Map<Long, Integer> totals = new HashMap<>();
        Map<Long, String> expiryLabel = new HashMap<>();
        Map<Long, String> expiryClass = new HashMap<>();

        for (Medicine m : meds) {
            totals.put(m.getId(), inventoryService.totalStock(m.getId()));
            LocalDate next = inventoryService.batchesFor(m.getId()).stream()
                    .filter(b -> b.getQuantity() != null && b.getQuantity() > 0)
                    .map(b -> b.getExpiryDate())
                    .findFirst()
                    .orElse(null);

            if (next == null) {
                expiryLabel.put(m.getId(), "no stock");
                expiryClass.put(m.getId(), "text-slate-400");
            } else {
                long days = ChronoUnit.DAYS.between(today, next);
                expiryLabel.put(m.getId(), "expires " + next.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM")));
                if (days < 0)        expiryClass.put(m.getId(), "text-red-600 font-semibold");
                else if (days <= 30) expiryClass.put(m.getId(), "text-red-500");
                else if (days <= 90) expiryClass.put(m.getId(), "text-amber-600");
                else                 expiryClass.put(m.getId(), "text-slate-400");
            }
        }
        model.addAttribute("medicines", meds);
        model.addAttribute("totals", totals);
        model.addAttribute("expiryLabel", expiryLabel);
        model.addAttribute("expiryClass", expiryClass);
        return "inventory/list";
    }

    @GetMapping("/search")
    public String searchFragment(@RequestParam(value = "q", required = false) String query, Model model) {
        List<Medicine> meds = inventoryService.search(currentUser.clinicId(), query, 0).getContent();
        LocalDate today = LocalDate.now();
        Map<Long, Integer> totals = new HashMap<>();
        Map<Long, String> expiryLabel = new HashMap<>();
        Map<Long, String> expiryClass = new HashMap<>();
        for (Medicine m : meds) {
            totals.put(m.getId(), inventoryService.totalStock(m.getId()));
            LocalDate next = inventoryService.batchesFor(m.getId()).stream()
                    .filter(b -> b.getQuantity() != null && b.getQuantity() > 0)
                    .map(b -> b.getExpiryDate()).findFirst().orElse(null);
            if (next == null) {
                expiryLabel.put(m.getId(), "no stock");
                expiryClass.put(m.getId(), "text-slate-400");
            } else {
                long days = ChronoUnit.DAYS.between(today, next);
                expiryLabel.put(m.getId(), "expires " + next.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM")));
                if (days < 0) expiryClass.put(m.getId(), "text-red-600 font-semibold");
                else if (days <= 30) expiryClass.put(m.getId(), "text-red-500");
                else if (days <= 90) expiryClass.put(m.getId(), "text-amber-600");
                else expiryClass.put(m.getId(), "text-slate-400");
            }
        }
        model.addAttribute("medicines", meds);
        model.addAttribute("totals", totals);
        model.addAttribute("expiryLabel", expiryLabel);
        model.addAttribute("expiryClass", expiryClass);
        return "inventory/list :: results";
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @GetMapping("/medicines/new")
    public String newMedicine(Model model) {
        model.addAttribute("medicine", new Medicine());
        return "inventory/medicine-form";
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @GetMapping("/medicines/{id}/edit")
    public String editMedicine(@PathVariable Long id, Model model,
                               org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            model.addAttribute("medicine", inventoryService.getOwnMedicineOrThrow(id));
            return "inventory/medicine-form";
        } catch (InventoryService.PlatformMedicineReadOnlyException e) {
            ra.addFlashAttribute("flashInfo", e.getMessage());
            return "redirect:/inventory/medicines/" + id;
        }
    }

    /**
     * Identity / ownership fields are never bound from the request: a client-supplied {@code id}
     * would turn "create" into "overwrite", and {@code clinicId} / {@code active} are server-owned.
     */
    @org.springframework.web.bind.annotation.InitBinder("medicine")
    void medicineBinder(org.springframework.web.bind.WebDataBinder binder) {
        binder.setDisallowedFields("id", "clinicId", "active");
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @PostMapping("/medicines")
    public String createMedicine(@jakarta.validation.Valid @ModelAttribute Medicine medicine,
                                  org.springframework.validation.BindingResult br,
                                  Model model,
                                  org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        if (br.hasErrors()) {
            model.addAttribute("medicine", medicine);
            model.addAttribute("error", br.getAllErrors().get(0).getDefaultMessage());
            return "inventory/medicine-form";
        }
        try {
            Medicine saved = inventoryService.createMedicine(currentUser.clinicId(), medicine);
            ra.addFlashAttribute("flash", "Medicine added: " + saved.getName());
            return "redirect:/inventory";
        } catch (com.clinicops.app.service.InventoryService.DuplicateMedicineException dup) {
            model.addAttribute("medicine", medicine);
            model.addAttribute("error", dup.getMessage());
            model.addAttribute("duplicateId", dup.getExistingId());
            return "inventory/medicine-form";
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @PostMapping("/medicines/{id}/delete")
    public String deleteMedicine(@PathVariable Long id,
                                  org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        try {
            inventoryService.softDeleteMedicine(id);
        } catch (InventoryService.PlatformMedicineReadOnlyException e) {
            ra.addFlashAttribute("flashInfo", e.getMessage());
            return "redirect:/inventory/medicines/" + id;
        }
        ra.addFlashAttribute("flash", "Medicine deleted.");
        return "redirect:/inventory";
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @PostMapping("/medicines/{id}")
    public String updateMedicine(@PathVariable Long id,
                                  @jakarta.validation.Valid @ModelAttribute Medicine medicine,
                                  org.springframework.validation.BindingResult br,
                                  Model model,
                                  org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        // id is not bound from the request (see medicineBinder); restore it so a re-rendered
        // form keeps posting to this update endpoint.
        medicine.setId(id);
        if (br.hasErrors()) {
            model.addAttribute("medicine", medicine);
            model.addAttribute("error", br.getAllErrors().get(0).getDefaultMessage());
            return "inventory/medicine-form";
        }
        try {
            inventoryService.updateMedicine(id, medicine);
        } catch (InventoryService.DuplicateMedicineException dup) {
            model.addAttribute("medicine", medicine);
            model.addAttribute("error", dup.getMessage());
            model.addAttribute("duplicateId", dup.getExistingId());
            return "inventory/medicine-form";
        } catch (InventoryService.PlatformMedicineReadOnlyException e) {
            ra.addFlashAttribute("flashInfo", e.getMessage());
            return "redirect:/inventory/medicines/" + id;
        }
        ra.addFlashAttribute("flash", "Medicine updated.");
        return "redirect:/inventory/medicines/" + id;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @PostMapping("/batches/{id}/adjust")
    public String adjustBatch(@PathVariable("id") Long batchId,
                              @RequestParam("medicineId") Long medicineId,
                              @RequestParam("newQuantity") Integer newQuantity,
                              @RequestParam(value = "reason", required = false) String reason) {
        inventoryService.adjustBatch(batchId, currentUser.userId(), newQuantity,
                reason == null || reason.isBlank() ? "manual adjustment" : reason);
        return "redirect:/inventory/medicines/" + medicineId;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @PostMapping("/write-off-expired")
    public String writeOffExpired() {
        inventoryService.writeOffExpired(currentUser.clinicId(), currentUser.userId());
        return "redirect:/alerts";
    }

    @GetMapping("/medicines/{id}")
    public String medicineDetail(@PathVariable Long id,
                                  @RequestParam(value = "newSupplierId", required = false) Long newSupplierId,
                                  Model model) {
        Medicine medicine = inventoryService.getMedicineOrThrow(id);
        List<StockBatch> batches = inventoryService.batchesFor(id);
        LocalDate today = LocalDate.now();

        Map<Long, String> batchClass = new HashMap<>();
        Map<Long, String> batchLabel = new HashMap<>();
        for (var b : batches) {
            long days = ChronoUnit.DAYS.between(today, b.getExpiryDate());
            if (days < 0) {
                batchClass.put(b.getId(), "chip-red");
                batchLabel.put(b.getId(), (-days) + "d expired");
            } else if (days <= 30) {
                batchClass.put(b.getId(), "chip-red");
                batchLabel.put(b.getId(), days + "d left");
            } else if (days <= 90) {
                batchClass.put(b.getId(), "chip-yellow");
                batchLabel.put(b.getId(), days + "d left");
            } else {
                batchClass.put(b.getId(), "chip-green");
                batchLabel.put(b.getId(), days + "d left");
            }
        }

        model.addAttribute("medicine", medicine);
        model.addAttribute("batches", batches);
        model.addAttribute("total", inventoryService.totalStock(id));
        model.addAttribute("suppliers", inventoryService.suppliers(currentUser.clinicId()));
        model.addAttribute("batchClass", batchClass);
        model.addAttribute("batchLabel", batchLabel);
        model.addAttribute("newSupplierId", newSupplierId);
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("tomorrow", LocalDate.now().plusDays(1).toString());
        return "inventory/medicine-detail";
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
    @PostMapping("/medicines/{id}/batches")
    public String addBatch(@PathVariable("id") Long medicineId,
                           @RequestParam String batchNumber,
                           @RequestParam String expiryDate,
                           @RequestParam Integer quantity,
                           @RequestParam(required = false) BigDecimal purchasePrice,
                           @RequestParam(required = false) BigDecimal salePrice,
                           @RequestParam(required = false) Long supplierId,
                           org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(expiryDate.trim());
        } catch (java.time.format.DateTimeParseException e) {
            ra.addFlashAttribute("error", "Batch expiry is not a valid date.");
            return "redirect:/inventory/medicines/" + medicineId;
        }
        if (!expiry.isAfter(LocalDate.now())) {
            ra.addFlashAttribute("error", "Batch expiry must be a future date.");
            return "redirect:/inventory/medicines/" + medicineId;
        }
        if (quantity == null || quantity <= 0) {
            ra.addFlashAttribute("error", "Quantity must be greater than zero.");
            return "redirect:/inventory/medicines/" + medicineId;
        }
        inventoryService.addBatch(
                currentUser.clinicId(),
                medicineId,
                currentUser.userId(),
                batchNumber,
                expiry,
                quantity,
                purchasePrice,
                salePrice,
                supplierId);
        return "redirect:/inventory/medicines/" + medicineId;
    }
}
