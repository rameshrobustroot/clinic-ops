package com.clinicops.app.web;

import com.clinicops.app.domain.Supplier;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.NotFoundException;
import com.clinicops.app.util.SafeRedirect;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/suppliers")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
public class SupplierController {

    private final SupplierRepository supplierRepository;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("suppliers", supplierRepository.findByClinicIdOrderByNameAsc(currentUser.clinicId()));
        return "supplier/list";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(value = "returnTo", required = false) String returnTo, Model model) {
        model.addAttribute("supplier", new Supplier());
        model.addAttribute("returnTo", returnTo);
        return "supplier/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + id));
        com.clinicops.app.tenant.TenantGuard.check(supplier, Supplier::getClinicId);
        model.addAttribute("supplier", supplier);
        return "supplier/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("supplier") Supplier supplier, BindingResult br,
                         @RequestParam(value = "returnTo", required = false) String returnTo,
                         Model model,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        if (br.hasErrors()) {
            model.addAttribute("returnTo", returnTo);
            return "supplier/form";
        }
        supplier.setClinicId(currentUser.clinicId());
        Supplier saved = supplierRepository.save(supplier);
        String target = safeReturnTo(returnTo);
        if (target != null) {
            String sep = target.contains("?") ? "&" : "?";
            return "redirect:" + target + sep + "newSupplierId=" + saved.getId();
        }
        ra.addFlashAttribute("flash", "Supplier added: " + saved.getName());
        return "redirect:/suppliers";
    }

    /** Only honour relative (same-origin) URLs — guards against open-redirect attacks. */
    private static String safeReturnTo(String value) {
        return SafeRedirect.relativeOrNull(value);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("supplier") Supplier supplier, BindingResult br,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        if (br.hasErrors()) return "supplier/form";
        Supplier existing = supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + id));
        com.clinicops.app.tenant.TenantGuard.check(existing, Supplier::getClinicId);
        existing.setName(supplier.getName());
        existing.setPhone(supplier.getPhone());
        existing.setAddress(supplier.getAddress());
        existing.setBankAccountName(supplier.getBankAccountName());
        existing.setBankAccountNumber(supplier.getBankAccountNumber());
        existing.setBankIfsc(supplier.getBankIfsc());
        existing.setBankName(supplier.getBankName());
        existing.setUpiId(supplier.getUpiId());
        existing.setGstin(supplier.getGstin());
        existing.setNotes(supplier.getNotes());
        supplierRepository.save(existing);
        ra.addFlashAttribute("flash", "Supplier updated.");
        return "redirect:/suppliers";
    }
}
