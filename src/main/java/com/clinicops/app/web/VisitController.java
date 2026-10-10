package com.clinicops.app.web;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.service.FileStorageService;
import com.clinicops.app.service.PatientService;
import com.clinicops.app.service.PrescriptionPdfService;
import com.clinicops.app.service.VisitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequestMapping("/visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final PatientService patientService;
    private final FileStorageService fileStorage;
    private final PrescriptionPdfService pdfService;
    private final ClinicRepository clinicRepository;
    private final AppUserRepository userRepository;
    private final MedicineRepository medicineRepository;
    private final com.clinicops.app.repository.SaleRepository saleRepository;
    private final com.clinicops.app.service.DoctorService doctorService;
    private final com.clinicops.app.repository.DoctorRepository doctorRepository;
    private final com.clinicops.app.service.MedicalMasterService masterService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','DOCTOR','RECEPTIONIST')")
    @GetMapping("/new")
    public String newForm(@RequestParam("patientId") Long patientId, Model model) {
        Patient patient = patientService.getOrThrow(patientId);
        model.addAttribute("patient", patient);
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("canEditClinical", hasAnyRole("ROLE_OWNER", "ROLE_DOCTOR"));
        addDoctorsModel(model, null);
        addRxFormModel(model);
        return "visit/form";
    }

    /** Populates the doctor dropdown. If the clinic has exactly one active doctor, pre-selects them. */
    private void addDoctorsModel(Model model, Long selectedDoctorId) {
        var doctors = doctorService.listActive(currentUser.clinicId());
        model.addAttribute("doctors", doctors);
        if (selectedDoctorId != null) {
            model.addAttribute("selectedDoctorId", selectedDoctorId);
        } else if (doctors.size() == 1) {
            model.addAttribute("selectedDoctorId", doctors.get(0).getId());
        }
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','DOCTOR','RECEPTIONIST')")
    @PostMapping
    public String create(@RequestParam("patientId") Long patientId,
                         @RequestParam(value = "doctorId", required = false) Long doctorId,
                         @RequestParam(value = "diagnosis", required = false) String diagnosis,
                         @RequestParam(value = "notes", required = false) String notes,
                         @RequestParam(value = "followUpDate", required = false) String followUpStr,
                         @RequestParam(value = "prescriptionText", required = false) String prescriptionText,
                         @RequestParam(value = "prescriptionFile", required = false) MultipartFile file,
                         @RequestParam(value = "rxMedicineId", required = false) String[] rxMedicineIds,
                         @RequestParam(value = "rxCustomName", required = false) String[] rxCustomNames,
                         @RequestParam(value = "rxDosage", required = false) String[] rxDosages,
                         @RequestParam(value = "rxFrequency", required = false) String[] rxFrequencies,
                         @RequestParam(value = "rxDuration", required = false) String[] rxDurations,
                         @RequestParam(value = "rxInstructions", required = false) String[] rxInstructions,
                         @RequestParam(value = "vitalBpSystolic", required = false) Integer vitalBpSystolic,
                         @RequestParam(value = "vitalBpDiastolic", required = false) Integer vitalBpDiastolic,
                         @RequestParam(value = "vitalPulse", required = false) Integer vitalPulse,
                         @RequestParam(value = "vitalTemperatureF", required = false) java.math.BigDecimal vitalTemperatureF,
                         @RequestParam(value = "vitalRespiratoryRate", required = false) Integer vitalRespiratoryRate,
                         @RequestParam(value = "vitalSpo2", required = false) Integer vitalSpo2,
                         @RequestParam(value = "vitalWeightKg", required = false) java.math.BigDecimal vitalWeightKg,
                         @RequestParam(value = "vitalHeightCm", required = false) java.math.BigDecimal vitalHeightCm,
                         @RequestParam(value = "vitalBloodGlucose", required = false) Integer vitalBloodGlucose,
                         @RequestParam(value = "vitalBloodGlucoseType", required = false) String vitalBloodGlucoseType,
                         @RequestParam(value = "investigationName", required = false) String[] investigationNames,
                         @RequestParam(value = "investigationInstructions", required = false) String[] investigationInstructions,
                         Model model)
            throws IOException {
        LocalDate followUp;
        try {
            followUp = parseOptionalDate(followUpStr);
        } catch (DateTimeParseException e) {
            return newFormWithError(model, patientId, doctorId, "Follow-up date is not a valid date.",
                    diagnosis, notes, prescriptionText);
        }
        if (followUp != null && followUp.isBefore(LocalDate.now())) {
            return newFormWithError(model, patientId, doctorId, "Follow-up date cannot be in the past.",
                    diagnosis, notes, prescriptionText);
        }
        // Doctor is required — the visit must be attributable to a specific practitioner.
        if (doctorId == null || !isValidDoctor(doctorId)) {
            return newFormWithError(model, patientId, doctorId, "Select the consulting doctor before saving.",
                    diagnosis, notes, prescriptionText);
        }
        List<VisitService.RxLine> rxLines = VisitService.parseRxLines(
                rxMedicineIds, rxCustomNames, rxDosages, rxFrequencies, rxDurations, rxInstructions);
        VisitService.Vitals vitals = new VisitService.Vitals(
                vitalBpSystolic, vitalBpDiastolic, vitalPulse, vitalTemperatureF,
                vitalRespiratoryRate, vitalSpo2, vitalWeightKg, vitalHeightCm,
                vitalBloodGlucose,
                (vitalBloodGlucoseType == null || vitalBloodGlucoseType.isBlank()) ? null : vitalBloodGlucoseType);
        List<VisitService.InvestigationLine> investigations = VisitService.parseInvestigationLines(
                investigationNames, investigationInstructions);

        // Don't create an empty visit. At least one piece of clinical OR vitals data must be present.
        if (!hasAnyContent(vitals, diagnosis, notes, followUp, prescriptionText, file, rxLines, investigations)) {
            Patient patient = patientService.getOrThrow(patientId);
            model.addAttribute("patient", patient);
            model.addAttribute("today", LocalDate.now().toString());
            addDoctorsModel(model, doctorId);
            addRxFormModel(model);
            model.addAttribute("error", "Enter at least one vital, diagnosis, prescription, investigation or note before saving.");
            return "visit/form";
        }

        Visit visit = visitService.create(patientId, doctorId, currentUser.clinicId(), currentUser.userId(),
                diagnosis, notes, followUp, prescriptionText, file, rxLines,
                vitals.isEmpty() ? null : vitals, investigations);
        return "redirect:/visits/" + visit.getId();
    }

    /** Re-renders the new-visit form with an error, keeping what the user typed. */
    private String newFormWithError(Model model, Long patientId, Long doctorId, String error,
                                    String diagnosis, String notes, String prescriptionText) {
        model.addAttribute("patient", patientService.getOrThrow(patientId));
        model.addAttribute("today", LocalDate.now().toString());
        addDoctorsModel(model, doctorId);
        addRxFormModel(model);
        model.addAttribute("error", error);
        model.addAttribute("diagnosisVal", diagnosis);
        model.addAttribute("notesVal", notes);
        model.addAttribute("prescriptionTextVal", prescriptionText);
        return "visit/form";
    }

    /** Blank → null; anything else must be ISO yyyy-MM-dd (what the date input sends). */
    private static LocalDate parseOptionalDate(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value.trim());
    }

    private boolean isValidDoctor(Long doctorId) {
        return doctorService.listActive(currentUser.clinicId()).stream()
                .anyMatch(d -> doctorId.equals(d.getId()));
    }

    private static boolean hasAnyContent(VisitService.Vitals vitals, String diagnosis, String notes,
                                         LocalDate followUp, String prescriptionText, MultipartFile file,
                                         List<VisitService.RxLine> rxLines,
                                         List<VisitService.InvestigationLine> investigations) {
        if (vitals != null && !vitals.isEmpty()) return true;
        if (diagnosis != null && !diagnosis.isBlank()) return true;
        if (notes != null && !notes.isBlank()) return true;
        if (followUp != null) return true;
        if (prescriptionText != null && !prescriptionText.isBlank()) return true;
        if (file != null && !file.isEmpty()) return true;
        if (rxLines != null && !rxLines.isEmpty()) return true;
        if (investigations != null && !investigations.isEmpty()) return true;
        return false;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','DOCTOR','RECEPTIONIST')")
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Visit visit = visitService.getOrThrow(id);
        Patient patient = patientService.getOrThrow(visit.getPatientId());
        model.addAttribute("patient", patient);
        model.addAttribute("visit", visit);
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("canEditClinical", hasAnyRole("ROLE_OWNER", "ROLE_DOCTOR"));
        addDoctorsModel(model, visit.getDoctorId());
        addRxFormModel(model);
        // Pre-populate Rx initial lines
        var rxItems = visitService.rxItemsFor(id);
        List<Map<String, String>> lines = new java.util.ArrayList<>();
        for (var r : rxItems) {
            lines.add(Map.of(
                    "medicineId", r.getMedicineId() == null ? "" : String.valueOf(r.getMedicineId()),
                    "customName", r.getCustomName() == null ? "" : r.getCustomName(),
                    "dosage", r.getDosage() == null ? "" : r.getDosage(),
                    "frequency", r.getFrequency() == null ? "" : r.getFrequency(),
                    "duration", r.getDuration() == null ? "" : r.getDuration(),
                    "instructions", r.getInstructions() == null ? "" : r.getInstructions()));
        }
        try { model.addAttribute("initialLinesJson", objectMapper.writeValueAsString(lines)); }
        catch (Exception e) { model.addAttribute("initialLinesJson", "[]"); }

        var inv = visitService.investigationsFor(id);
        List<Map<String, String>> invLines = new java.util.ArrayList<>();
        for (var i : inv) {
            invLines.add(Map.of(
                    "name", i.getName(),
                    "instructions", i.getInstructions() == null ? "" : i.getInstructions()));
        }
        try { model.addAttribute("initialInvestigationsJson", objectMapper.writeValueAsString(invLines)); }
        catch (Exception e) { model.addAttribute("initialInvestigationsJson", "[]"); }

        return "visit/form";
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER','DOCTOR','RECEPTIONIST')")
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam(value = "doctorId", required = false) Long doctorId,
                         @RequestParam(value = "diagnosis", required = false) String diagnosis,
                         @RequestParam(value = "notes", required = false) String notes,
                         @RequestParam(value = "followUpDate", required = false) String followUpStr,
                         @RequestParam(value = "prescriptionText", required = false) String prescriptionText,
                         @RequestParam(value = "prescriptionFile", required = false) MultipartFile file,
                         @RequestParam(value = "rxMedicineId", required = false) String[] rxMedicineIds,
                         @RequestParam(value = "rxCustomName", required = false) String[] rxCustomNames,
                         @RequestParam(value = "rxDosage", required = false) String[] rxDosages,
                         @RequestParam(value = "rxFrequency", required = false) String[] rxFrequencies,
                         @RequestParam(value = "rxDuration", required = false) String[] rxDurations,
                         @RequestParam(value = "rxInstructions", required = false) String[] rxInstructions,
                         @RequestParam(value = "vitalBpSystolic", required = false) Integer vitalBpSystolic,
                         @RequestParam(value = "vitalBpDiastolic", required = false) Integer vitalBpDiastolic,
                         @RequestParam(value = "vitalPulse", required = false) Integer vitalPulse,
                         @RequestParam(value = "vitalTemperatureF", required = false) java.math.BigDecimal vitalTemperatureF,
                         @RequestParam(value = "vitalRespiratoryRate", required = false) Integer vitalRespiratoryRate,
                         @RequestParam(value = "vitalSpo2", required = false) Integer vitalSpo2,
                         @RequestParam(value = "vitalWeightKg", required = false) java.math.BigDecimal vitalWeightKg,
                         @RequestParam(value = "vitalHeightCm", required = false) java.math.BigDecimal vitalHeightCm,
                         @RequestParam(value = "vitalBloodGlucose", required = false) Integer vitalBloodGlucose,
                         @RequestParam(value = "vitalBloodGlucoseType", required = false) String vitalBloodGlucoseType,
                         @RequestParam(value = "investigationName", required = false) String[] investigationNames,
                         @RequestParam(value = "investigationInstructions", required = false) String[] investigationInstructions,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes ra)
            throws IOException {
        LocalDate followUp;
        try {
            followUp = parseOptionalDate(followUpStr);
        } catch (DateTimeParseException e) {
            ra.addFlashAttribute("error", "Follow-up date is not a valid date.");
            return "redirect:/visits/" + id + "/edit";
        }
        VisitService.Vitals vitals = new VisitService.Vitals(
                vitalBpSystolic, vitalBpDiastolic, vitalPulse, vitalTemperatureF,
                vitalRespiratoryRate, vitalSpo2, vitalWeightKg, vitalHeightCm,
                vitalBloodGlucose,
                (vitalBloodGlucoseType == null || vitalBloodGlucoseType.isBlank()) ? null : vitalBloodGlucoseType);

        // Receptionists can only update vitals; clinical fields are preserved by re-using existing values.
        boolean canEditClinical = hasAnyRole("ROLE_OWNER", "ROLE_DOCTOR");
        if (!canEditClinical) {
            Visit existing = visitService.getOrThrow(id);
            diagnosis = existing.getDiagnosis();
            notes = existing.getNotes();
            followUp = existing.getFollowUpDate();
            prescriptionText = existing.getPrescriptionText();
            file = null; // don't accept new file uploads
            rxMedicineIds = null; rxCustomNames = null;
            rxDosages = null; rxFrequencies = null; rxDurations = null; rxInstructions = null;
            investigationNames = null; investigationInstructions = null;
        }

        List<VisitService.RxLine> rxLines = canEditClinical
                ? VisitService.parseRxLines(rxMedicineIds, rxCustomNames, rxDosages, rxFrequencies, rxDurations, rxInstructions)
                : null;  // null = service keeps existing rx items (see update())
        List<VisitService.InvestigationLine> investigations = canEditClinical
                ? VisitService.parseInvestigationLines(investigationNames, investigationInstructions)
                : null;

        // Doctor reassignment is allowed; fall back to existing if not supplied or invalid.
        Long resolvedDoctorId = (doctorId != null && isValidDoctor(doctorId))
                ? doctorId
                : visitService.getOrThrow(id).getDoctorId();

        visitService.update(id, currentUser.userId(), resolvedDoctorId, diagnosis, notes, followUp, prescriptionText, file,
                rxLines, vitals.isEmpty() ? null : vitals, investigations);
        return "redirect:/visits/" + id;
    }

    private boolean hasAnyRole(String... roles) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        for (var a : auth.getAuthorities()) {
            for (String r : roles) if (r.equals(a.getAuthority())) return true;
        }
        return false;
    }

    private void addRxFormModel(Model model) {
        List<Map<String, Object>> medList = new ArrayList<>();
        medicineRepository.findByClinicIdOrderByNameAsc(currentUser.clinicId()).forEach(m -> {
            String label = m.getName() + (m.getStrength() != null && !m.getStrength().isBlank() ? " · " + m.getStrength() : "");
            medList.add(Map.of("id", m.getId(), "label", label));
        });
        var invNames = masterService.listActiveInvestigations(currentUser.clinicId()).stream()
                .map(i -> i.getName()).toList();
        try {
            model.addAttribute("medicinesJson", objectMapper.writeValueAsString(medList));
            model.addAttribute("masterInvestigationsJson", objectMapper.writeValueAsString(invNames));
        } catch (Exception e) {
            model.addAttribute("medicinesJson", "[]");
            model.addAttribute("masterInvestigationsJson", "[]");
        }
        model.addAttribute("canEditClinical", hasAnyRole("ROLE_OWNER", "ROLE_DOCTOR"));
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Visit visit = visitService.getOrThrow(id);
        Patient patient = patientService.getOrThrow(visit.getPatientId());
        var rxItems = visitService.rxItemsFor(id);
        Map<Long, String> medNames = new java.util.HashMap<>();
        rxItems.forEach(it -> {
            if (it.getMedicineId() != null && !medNames.containsKey(it.getMedicineId())) {
                medicineRepository.findById(it.getMedicineId())
                        .ifPresent(m -> medNames.put(m.getId(), m.getName()));
            }
        });
        model.addAttribute("visit", visit);
        model.addAttribute("patient", patient);
        model.addAttribute("rxItems", rxItems);
        model.addAttribute("medNames", medNames);
        model.addAttribute("investigations", visitService.investigationsFor(id));
        if (visit.getDoctorId() != null) {
            doctorRepository.findById(visit.getDoctorId()).ifPresent(d -> model.addAttribute("doctor", d));
        }
        saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(id)
                .ifPresent(sale -> model.addAttribute("existingBill", sale));
        return "visit/detail";
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<UrlResource> file(@PathVariable Long id) throws IOException {
        Visit visit = visitService.getOrThrow(id);
        if (visit.getPrescriptionFile() == null) return ResponseEntity.notFound().build();
        Path path = fileStorage.resolve(visit.getPrescriptionFile());
        String contentType = Files.probeContentType(path);
        return ResponseEntity.ok()
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
                .body(new UrlResource(path.toUri()));
    }

    @GetMapping("/{id}/prescription.pdf")
    public ResponseEntity<byte[]> prescriptionPdf(@PathVariable Long id) {
        Visit visit = visitService.getOrThrow(id);
        Patient patient = patientService.getOrThrow(visit.getPatientId());
        Clinic clinic = clinicRepository.findById(currentUser.clinicId()).orElse(null);
        com.clinicops.app.domain.Doctor doctor = visit.getDoctorId() == null ? null
                : doctorRepository.findById(visit.getDoctorId()).orElse(null);

        var rxItems = visitService.rxItemsFor(visit.getId());
        // Pass full Medicine objects so the PDF can render generic name, strength, form.
        java.util.Map<Long, com.clinicops.app.domain.Medicine> medicines = new java.util.HashMap<>();
        rxItems.forEach(it -> {
            if (it.getMedicineId() != null && !medicines.containsKey(it.getMedicineId())) {
                medicineRepository.findById(it.getMedicineId())
                        .ifPresent(m -> medicines.put(m.getId(), m));
            }
        });

        var investigations = visitService.investigationsFor(visit.getId());
        byte[] pdf = pdfService.generate(clinic, patient, visit, doctor, rxItems, medicines, investigations);
        String filename = "prescription-" + patient.getName().replaceAll("\\s+", "_") + "-" + id + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdf);
    }
}
