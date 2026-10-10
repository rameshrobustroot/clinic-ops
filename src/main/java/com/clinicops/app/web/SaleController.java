package com.clinicops.app.web;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.PrescriptionItem;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.SaleItem;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.RxQuantityHeuristic;
import com.clinicops.app.service.SaleService;
import com.clinicops.app.service.VisitService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** Billing is OWNER / DOCTOR / PHARMACIST only — matches the bottom nav; receptionists don't bill. */
@Controller
@RequestMapping("/sales")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','DOCTOR','PHARMACIST')")
public class SaleController {

    private final SaleService saleService;
    private final VisitService visitService;
    private final MedicineRepository medicineRepository;
    private final PatientRepository patientRepository;
    private final com.clinicops.app.repository.ClinicRepository clinicRepository;
    private final com.clinicops.app.service.SaleReceiptPdfService receiptPdfService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public String list(@RequestParam(value = "page", defaultValue = "0") int pageNo, Model model) {
        Long clinicId = currentUser.clinicId();
        var page = saleService.salesPage(clinicId, pageNo);
        List<Sale> sales = page.getContent();

        Map<Long, String> patientNames = new HashMap<>();
        for (Sale s : sales) {
            if (s.getPatientId() != null && !patientNames.containsKey(s.getPatientId())) {
                patientRepository.findById(s.getPatientId())
                        .ifPresent(p -> patientNames.put(p.getId(), p.getName()));
            }
        }
        model.addAttribute("sales", sales);
        model.addAttribute("patientNames", patientNames);
        model.addAttribute("pageNo", page.getNumber());
        model.addAttribute("totalPages", page.getTotalPages());
        model.addAttribute("totalElements", page.getTotalElements());
        model.addAttribute("hasPrev", page.hasPrevious());
        model.addAttribute("hasNext", page.hasNext());
        return "sale/list";
    }

    @GetMapping("/new")
    public String newSale(@RequestParam(value = "patientId", required = false) String patientIdStr,
                          @RequestParam(value = "fromVisit", required = false) Long fromVisit,
                          Model model) {
        Long clinicId = currentUser.clinicId();
        if (fromVisit != null) {
            Visit visit = visitService.getOrThrow(fromVisit);
            List<PrescriptionItem> items = visitService.rxItemsFor(visit.getId());

            int customSkipped = 0;
            String[] mids = new String[items.size()];
            String[] qtys = new String[items.size()];
            int i = 0;
            for (PrescriptionItem item : items) {
                if (item.getMedicineId() == null) {
                    customSkipped++;
                    continue;
                }
                mids[i] = String.valueOf(item.getMedicineId());
                qtys[i] = String.valueOf(RxQuantityHeuristic.compute(item.getDosage(), item.getDuration()));
                i++;
            }
            // Trim trailing nulls (skipped customs)
            String[] tmids = java.util.Arrays.copyOf(mids, i);
            String[] tqtys = java.util.Arrays.copyOf(qtys, i);

            addFormModel(model, clinicId, String.valueOf(visit.getPatientId()), "CASH", null, null,
                    tmids, tqtys, null, null, null, null);
            model.addAttribute("fromVisit", visit);
            if (customSkipped > 0) {
                model.addAttribute("customSkipped", customSkipped);
            }
            return "sale/new";
        }
        addFormModel(model, clinicId, patientIdStr, "CASH", null, null,
                null, null, null, null, null, null);
        return "sale/new";
    }

    @PostMapping
    public String create(@RequestParam(value = "patientId", required = false) String patientIdStr,
                         @RequestParam(value = "visitId", required = false) Long visitId,
                         @RequestParam(value = "paymentMode", required = false) String paymentMode,
                         @RequestParam(value = "walkInName", required = false) String walkInName,
                         @RequestParam(value = "walkInPhone", required = false) String walkInPhone,
                         @RequestParam(value = "medicineId", required = false) String[] medicineIds,
                         @RequestParam(value = "quantity", required = false) String[] quantities,
                         @RequestParam(value = "lineDiscountType", required = false) String[] lineDiscountTypes,
                         @RequestParam(value = "lineDiscountValue", required = false) String[] lineDiscountValues,
                         @RequestParam(value = "billDiscountType", required = false) String billDiscountType,
                         @RequestParam(value = "billDiscountValue", required = false) String billDiscountValueStr,
                         Model model) {
        Long clinicId = currentUser.clinicId();
        Long patientId = parsePatientId(patientIdStr);

        List<SaleService.SaleLine> lines = SaleService.parseLines(
                medicineIds, quantities, lineDiscountTypes, lineDiscountValues);
        if (lines.isEmpty()) {
            addFormModel(model, clinicId, patientIdStr, paymentMode, walkInName, walkInPhone,
                    medicineIds, quantities, lineDiscountTypes, lineDiscountValues,
                    billDiscountType, billDiscountValueStr);
            model.addAttribute("error", "Add at least one medicine line.");
            return "sale/new";
        }
        java.math.BigDecimal billDiscountValue = parseDecimal(billDiscountValueStr);
        if (billDiscountValue == null || billDiscountValue.signum() <= 0) {
            // Drop the type if value is missing/zero — keeps DB clean.
            billDiscountType = null;
            billDiscountValue = null;
        }
        try {
            Sale sale = saleService.record(clinicId, currentUser.userId(),
                    patientId, visitId, paymentMode, walkInName, walkInPhone, lines,
                    billDiscountType, billDiscountValue);
            return "redirect:/sales/" + sale.getId();
        } catch (IllegalStateException | IllegalArgumentException e) {
            addFormModel(model, clinicId, patientIdStr, paymentMode, walkInName, walkInPhone,
                    medicineIds, quantities, lineDiscountTypes, lineDiscountValues,
                    billDiscountType, billDiscountValueStr);
            model.addAttribute("error", e.getMessage());
            return "sale/new";
        }
    }

    private static java.math.BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new java.math.BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long parsePatientId(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Populates everything sale/new needs: medicine list, preselected patient, preserved lines on error. */
    private void addFormModel(Model model, Long clinicId, String patientIdStr, String paymentMode,
                              String walkInName, String walkInPhone,
                              String[] medicineIds, String[] quantities,
                              String[] lineDiscountTypes, String[] lineDiscountValues,
                              String billDiscountType, String billDiscountValue) {
        List<Medicine> meds = medicineRepository.findByClinicIdOrderByNameAsc(clinicId);
        model.addAttribute("medicines", meds);

        Long pid = parsePatientId(patientIdStr);
        if (pid != null) {
            patientRepository.findById(pid).ifPresent(p -> model.addAttribute("patient", p));
        }

        model.addAttribute("paymentMode", paymentMode == null ? "CASH" : paymentMode);
        model.addAttribute("walkInName", walkInName == null ? "" : walkInName);
        model.addAttribute("walkInPhone", walkInPhone == null ? "" : walkInPhone);
        model.addAttribute("billDiscountType", billDiscountType == null ? "" : billDiscountType);
        model.addAttribute("billDiscountValue", billDiscountValue == null ? "" : billDiscountValue);

        try {
            // serialize medicines for client-side search
            List<Map<String, Object>> medList = new ArrayList<>();
            for (Medicine m : meds) {
                String label = m.getName() + (m.getStrength() != null && !m.getStrength().isBlank() ? " · " + m.getStrength() : "");
                medList.add(Map.of("id", m.getId(), "label", label));
            }
            model.addAttribute("medicinesJson", objectMapper.writeValueAsString(medList));

            // serialize submitted lines (preserved on validation error)
            List<Map<String, String>> lineList = new ArrayList<>();
            if (medicineIds != null && quantities != null) {
                int len = Math.min(medicineIds.length, quantities.length);
                for (int i = 0; i < len; i++) {
                    String dType  = (lineDiscountTypes != null && i < lineDiscountTypes.length
                            && lineDiscountTypes[i] != null) ? lineDiscountTypes[i] : "";
                    String dValue = (lineDiscountValues != null && i < lineDiscountValues.length
                            && lineDiscountValues[i] != null) ? lineDiscountValues[i] : "";
                    lineList.add(Map.of(
                            "medicineId", medicineIds[i] == null ? "" : medicineIds[i],
                            "quantity",   quantities[i]  == null ? "" : quantities[i],
                            "discountType",  dType,
                            "discountValue", dValue));
                }
            }
            model.addAttribute("initialLinesJson", objectMapper.writeValueAsString(lineList));
        } catch (JsonProcessingException e) {
            model.addAttribute("medicinesJson", "[]");
            model.addAttribute("initialLinesJson", "[]");
        }
    }

    @GetMapping("/{id}/receipt.pdf")
    public org.springframework.http.ResponseEntity<byte[]> receiptPdf(@PathVariable Long id) {
        Sale sale = saleService.getOrThrow(id);
        List<SaleItem> items = saleService.itemsFor(id);
        com.clinicops.app.domain.Clinic clinic = clinicRepository.findById(currentUser.clinicId()).orElse(null);
        Patient patient = sale.getPatientId() == null ? null
                : patientRepository.findById(sale.getPatientId()).orElse(null);

        Map<Long, String> medNames = new HashMap<>();
        for (SaleItem item : items) {
            if (!medNames.containsKey(item.getMedicineId())) {
                medicineRepository.findById(item.getMedicineId()).ifPresent(m -> {
                    String label = m.getName()
                            + (m.getStrength() != null && !m.getStrength().isBlank() ? " " + m.getStrength() : "");
                    medNames.put(m.getId(), label);
                });
            }
        }

        byte[] pdf = receiptPdfService.generate(clinic, sale, patient, items, medNames);
        String filename = "receipt-" + sale.getBillNo() + ".pdf";

        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Sale sale = saleService.getOrThrow(id);
        List<SaleItem> items = saleService.itemsFor(id);
        Map<Long, Medicine> medById = new HashMap<>();
        for (SaleItem item : items) {
            if (!medById.containsKey(item.getMedicineId())) {
                medicineRepository.findById(item.getMedicineId()).ifPresent(m -> medById.put(m.getId(), m));
            }
        }
        Patient patient = sale.getPatientId() == null ? null
                : patientRepository.findById(sale.getPatientId()).orElse(null);
        model.addAttribute("sale", sale);
        model.addAttribute("items", items);
        model.addAttribute("medById", medById);
        model.addAttribute("patient", patient);
        return "sale/detail";
    }
}
