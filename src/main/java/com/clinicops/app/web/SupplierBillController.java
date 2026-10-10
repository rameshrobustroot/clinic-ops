package com.clinicops.app.web;

import com.clinicops.app.domain.Supplier;
import com.clinicops.app.domain.SupplierBill;
import com.clinicops.app.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.BillPaymentForm;
import com.clinicops.app.service.SupplierBillForm;
import com.clinicops.app.service.SupplierBillService;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.core.io.PathResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/supplier-bills")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('OWNER','PHARMACIST')")
public class SupplierBillController {

    private final SupplierBillService supplierBillService;
    private final SupplierRepository supplierRepository;
    private final CurrentUser currentUser;

    /**
     * Force locale-independent BigDecimal parsing for all form fields in this controller.
     *
     * <p>Spring's FormattingConversionService can apply locale-aware number formatting
     * (e.g. Tamil locale) that causes systematic precision errors when parsing plain
     * decimal strings like "10000". {@link CustomNumberEditor} with no NumberFormat
     * argument uses {@code new BigDecimal(text.trim())} — completely locale-independent.
     */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(BigDecimal.class, new CustomNumberEditor(BigDecimal.class, true));
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) Long supplierId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String status) {
        Long clinicId = currentUser.clinicId();

        List<Supplier> suppliers = supplierRepository.findByClinicIdOrderByNameAsc(clinicId);
        Map<Long, String> supplierNames = suppliers.stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));

        List<SupplierBill> bills = supplierBillService.listFiltered(clinicId, supplierId, from, to, status);

        // Aggregate totals from the filtered set so the summary cards reflect what is visible
        BigDecimal outstandingTotal = bills.stream()
                .filter(b -> !"PAID".equals(b.getStatus()))
                .map(SupplierBill::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdueCount = bills.stream().filter(SupplierBill::isOverdue).count();

        boolean filtersActive = supplierId != null || from != null || to != null
                || (status != null && !status.isBlank());

        model.addAttribute("bills", bills);
        model.addAttribute("suppliers", suppliers);
        model.addAttribute("supplierNames", supplierNames);
        model.addAttribute("outstandingTotal", outstandingTotal);
        model.addAttribute("overdueCount", overdueCount);
        model.addAttribute("filtersActive", filtersActive);
        // Current filter values — used to re-populate the filter form
        model.addAttribute("filterSupplierId", supplierId);
        model.addAttribute("filterFrom", from);
        model.addAttribute("filterTo", to);
        model.addAttribute("filterStatus", status);
        // When a single supplier is selected, expose its object for the "Add Bill for X" button
        if (supplierId != null) {
            suppliers.stream().filter(s -> s.getId().equals(supplierId)).findFirst()
                    .ifPresent(s -> model.addAttribute("selectedSupplier", s));
        }
        return "supplier-bill/list";
    }

    @GetMapping("/new")
    public String newForm(Model model,
                          @RequestParam(required = false) Long supplierId,
                          @RequestParam(required = false) Long newSupplierId) {
        Long clinicId = currentUser.clinicId();
        SupplierBillForm form = new SupplierBillForm();
        // newSupplierId is set when returning from the "create supplier" flow;
        // it takes priority over the pre-selected supplierId from a scoped link.
        Long resolvedId = newSupplierId != null ? newSupplierId : supplierId;
        if (resolvedId != null) {
            form.setSupplierId(resolvedId);
        }
        model.addAttribute("form", form);
        model.addAttribute("suppliers", supplierRepository.findByClinicIdOrderByNameAsc(clinicId));
        // Pass back so the back-link can return to the filtered list
        model.addAttribute("backSupplierId", supplierId);
        // Confirmation banner — show the newly created supplier's name
        if (newSupplierId != null) {
            supplierRepository.findById(newSupplierId)
                    .ifPresent(s -> model.addAttribute("newSupplierName", s.getName()));
        }
        return "supplier-bill/form";
    }

    @PostMapping
    public String create(@ModelAttribute("form") SupplierBillForm form,
                         @RequestParam(value = "attachment", required = false) MultipartFile attachment,
                         RedirectAttributes ra) {
        Long clinicId = currentUser.clinicId();
        log.debug("Bill form bound: totalAmount={} billNumber={}", form.getTotalAmount(), form.getBillNumber());
        SupplierBill bill = supplierBillService.create(clinicId, form, attachment);
        ra.addFlashAttribute("flash", "Bill recorded successfully.");
        return "redirect:/supplier-bills/" + bill.getId();
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Long clinicId = currentUser.clinicId();
        SupplierBill bill = supplierBillService.findById(clinicId, id);
        model.addAttribute("bill", bill);
        // Resolve supplier name and credit balance
        if (bill.getSupplierId() != null) {
            supplierRepository.findById(bill.getSupplierId()).ifPresent(s -> {
                model.addAttribute("supplierName", s.getName());
                model.addAttribute("supplierCredit", s.getCreditBalance());
            });
        }
        model.addAttribute("payments", supplierBillService.listPayments(id));
        model.addAttribute("paymentForm", new BillPaymentForm());
        return "supplier-bill/view";
    }

    @PostMapping("/{id}/payments")
    public String addPayment(@PathVariable Long id,
                             @ModelAttribute("paymentForm") BillPaymentForm form,
                             RedirectAttributes ra) {
        Long clinicId = currentUser.clinicId();
        supplierBillService.addPayment(clinicId, id, form);
        ra.addFlashAttribute("flash", "Payment recorded.");
        return "redirect:/supplier-bills/" + id;
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<PathResource> attachment(@PathVariable Long id) throws IOException {
        Long clinicId = currentUser.clinicId();
        Path file = supplierBillService.getAttachment(clinicId, id);
        if (file == null || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = URLConnection.guessContentTypeFromName(file.getFileName().toString());
        if (contentType == null) contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        boolean isPdf = contentType.equals("application/pdf");
        String disposition = isPdf ? "inline" : "attachment";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new PathResource(file));
    }
}
