package com.clinicops.app.web;

import com.clinicops.app.domain.Patient;
import com.clinicops.app.repository.VisitRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.PatientService;
import com.clinicops.app.service.SaleService;
import com.clinicops.app.web.dto.PatientForm;
import com.clinicops.app.util.SafeRedirect;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;
    private final VisitRepository visitRepository;
    private final SaleService saleService;
    private final com.clinicops.app.repository.SaleRepository saleRepository;
    private final com.clinicops.app.repository.PrescriptionItemRepository prescriptionItemRepository;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(@RequestParam(value = "q", required = false) String query,
                       @RequestParam(value = "page", defaultValue = "0") int pageNo,
                       Model model) {
        Page<Patient> page = patientService.search(currentUser.clinicId(), query, Math.max(0, pageNo));
        model.addAttribute("patients", page.getContent());
        model.addAttribute("q", query == null ? "" : query);
        model.addAttribute("pageNo", page.getNumber());
        model.addAttribute("totalPages", page.getTotalPages());
        model.addAttribute("totalElements", page.getTotalElements());
        model.addAttribute("hasPrev", page.hasPrevious());
        model.addAttribute("hasNext", page.hasNext());
        return "patient/list";
    }

    // HTMX search fragment
    @GetMapping("/search")
    public String searchFragment(@RequestParam(value = "q", required = false) String query, Model model) {
        Page<Patient> page = patientService.search(currentUser.clinicId(), query, 0);
        model.addAttribute("patients", page.getContent());
        return "patient/list :: results";
    }

    // JSON search for inline pickers (e.g. New Sale form)
    @GetMapping("/search.json")
    @ResponseBody
    public List<Map<String, Object>> searchJson(@RequestParam(value = "q", required = false) String query) {
        Page<Patient> page = patientService.search(currentUser.clinicId(), query, 0);
        return page.getContent().stream()
                .limit(10)
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(),
                        "mrn", p.getMrn(),
                        "name", p.getName(),
                        "phone", p.getPhone() == null ? "" : p.getPhone(),
                        "age", p.getAge() == null ? "" : p.getAge(),
                        "gender", p.getGender() == null ? "" : p.getGender()))
                .toList();
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(value = "returnTo", required = false) String returnTo, Model model) {
        model.addAttribute("form", new PatientForm());
        model.addAttribute("returnTo", returnTo);
        return "patient/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") PatientForm form, BindingResult br,
                         @RequestParam(value = "returnTo", required = false) String returnTo,
                         Model model,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        if (br.hasErrors()) {
            model.addAttribute("returnTo", returnTo);
            return "patient/form";
        }
        Patient saved = patientService.create(currentUser.clinicId(), form.toEntity());
        String target = safeReturnTo(returnTo);
        if (target != null) {
            String sep = target.contains("?") ? "&" : "?";
            return "redirect:" + target + sep + "patientId=" + saved.getId();
        }
        // After creating, take the user back to the searchable patient list with a confirmation.
        ra.addFlashAttribute("flash", "Registered " + saved.getName() + " (" + saved.getMrn() + ")");
        return "redirect:/patients";
    }

    /** Only honour relative (same-origin) URLs — guards against open-redirect attacks. */
    private static String safeReturnTo(String value) {
        return SafeRedirect.relativeOrNull(value);
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Patient patient = patientService.getOrThrow(id);
        var visits = visitRepository.findByPatientIdOrderByVisitDateDesc(id);
        model.addAttribute("patient", patient);
        model.addAttribute("visits", visits);
        model.addAttribute("sales", saleService.salesForPatient(id));

        java.util.Map<Long, String> visitBillNo = new java.util.HashMap<>();
        java.util.Map<Long, Boolean> visitHasRx = new java.util.HashMap<>();
        for (var v : visits) {
            saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(v.getId())
                    .ifPresent(s -> visitBillNo.put(v.getId(), s.getBillNo()));
            visitHasRx.put(v.getId(), !prescriptionItemRepository.findByVisitIdOrderByLineNoAsc(v.getId()).isEmpty());
        }
        model.addAttribute("visitBillNo", visitBillNo);
        model.addAttribute("visitHasRx", visitHasRx);
        return "patient/detail";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Patient patient = patientService.getOrThrow(id);
        model.addAttribute("form", PatientForm.from(patient));
        model.addAttribute("patientId", id);
        return "patient/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") PatientForm form, BindingResult br,
                         Model model,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        if (br.hasErrors()) {
            model.addAttribute("patientId", id);
            return "patient/form";
        }
        Patient updated = patientService.update(id, form.toEntity());
        // After an edit, the user wants to see the change in context — stay on detail.
        ra.addFlashAttribute("flash", "Patient updated.");
        return "redirect:/patients/" + id;
    }
}
