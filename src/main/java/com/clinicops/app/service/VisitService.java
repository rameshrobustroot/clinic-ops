package com.clinicops.app.service;

import com.clinicops.app.domain.InvestigationItem;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantGuard;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.PrescriptionItem;
import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.repository.PrescriptionItemRepository;
import com.clinicops.app.repository.ProcurementItemRepository;
import com.clinicops.app.repository.VisitRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class VisitService {

    private final VisitRepository visitRepository;
    private final FileStorageService fileStorage;
    private final MedicalIdSequence medicalIdSequence;
    private final PrescriptionItemRepository prescriptionItemRepository;
    private final ProcurementItemRepository procurementItemRepository;
    private final MedicineRepository medicineRepository;
    private final com.clinicops.app.repository.InvestigationItemRepository investigationItemRepository;
    private final MedicalMasterService masterService;
    private final PatientRepository patientRepository;

    @Transactional
    public Visit create(Long patientId, Long doctorId, Long clinicId, Long userId, String diagnosis, String notes,
                        LocalDate followUpDate, String prescriptionText, MultipartFile prescriptionFile,
                        List<RxLine> rxLines, Vitals vitals, List<InvestigationLine> investigations)
            throws IOException {
        requirePatientInClinic(patientId, clinicId);
        Visit visit = Visit.builder()
                .clinicId(clinicId)
                .patientId(patientId)
                .doctorId(doctorId)
                .encounterType("OPD")
                .encounterNo(medicalIdSequence.nextEncounterNo("OPD"))
                .diagnosis(diagnosis)
                .notes(notes)
                .followUpDate(followUpDate)
                .prescriptionFile(fileStorage.store(prescriptionFile))
                .build();

        if (vitals != null) {
            visit.setVitalBpSystolic(vitals.bpSystolic);
            visit.setVitalBpDiastolic(vitals.bpDiastolic);
            visit.setVitalPulse(vitals.pulse);
            visit.setVitalTemperatureF(vitals.temperatureF);
            visit.setVitalRespiratoryRate(vitals.respiratoryRate);
            visit.setVitalSpo2(vitals.spo2);
            visit.setVitalWeightKg(vitals.weightKg);
            visit.setVitalHeightCm(vitals.heightCm);
            visit.setVitalBloodGlucose(vitals.bloodGlucose);
            visit.setVitalBloodGlucoseType(vitals.bloodGlucoseType);
        }

        // If structured Rx lines provided, derive text from them; else use the textarea content as-is
        String derivedText = formatRxLines(rxLines);
        visit.setPrescriptionText(derivedText != null ? derivedText
                : (prescriptionText != null && !prescriptionText.isBlank() ? prescriptionText : null));

        visit = visitRepository.save(visit);

        if (rxLines != null) {
            // created_by is an app_user FK: the acting user, never the doctor (a doctor-table id).
            persistRxLinesAndProcurement(visit, clinicId, userId, rxLines);
        }
        persistInvestigations(visit, investigations);
        return visit;
    }

    /**
     * Update an existing visit. Re-writes the Rx and Investigation lines (deletes old, inserts new)
     * to keep the diff handling simple. Procurement entries previously raised from custom Rx items
     * are NOT retracted — they remain in the procurement queue.
     */
    @Transactional
    public Visit update(Long visitId, Long userId, Long doctorId, String diagnosis, String notes,
                        LocalDate followUpDate, String prescriptionText, MultipartFile prescriptionFile,
                        List<RxLine> rxLines, Vitals vitals, List<InvestigationLine> investigations)
            throws IOException {
        Visit visit = getOrThrow(visitId);

        if (doctorId != null) visit.setDoctorId(doctorId);
        visit.setDiagnosis(diagnosis);
        visit.setNotes(notes);
        visit.setFollowUpDate(followUpDate);

        if (vitals != null) {
            visit.setVitalBpSystolic(vitals.bpSystolic);
            visit.setVitalBpDiastolic(vitals.bpDiastolic);
            visit.setVitalPulse(vitals.pulse);
            visit.setVitalTemperatureF(vitals.temperatureF);
            visit.setVitalRespiratoryRate(vitals.respiratoryRate);
            visit.setVitalSpo2(vitals.spo2);
            visit.setVitalWeightKg(vitals.weightKg);
            visit.setVitalHeightCm(vitals.heightCm);
            visit.setVitalBloodGlucose(vitals.bloodGlucose);
            visit.setVitalBloodGlucoseType(vitals.bloodGlucoseType);
        }

        // Replace file only if new one uploaded
        if (prescriptionFile != null && !prescriptionFile.isEmpty()) {
            visit.setPrescriptionFile(fileStorage.store(prescriptionFile));
        }

        // Rewrite Rx items ONLY when caller supplied them. Superseded rows are marked inactive
        // (soft-delete) so the historical prescription remains in the DB for audit.
        if (rxLines != null) {
            prescriptionItemRepository.findByVisitIdAndActiveTrueOrderByLineNoAsc(visitId).forEach(old -> {
                old.setActive(false);
                prescriptionItemRepository.save(old);
            });
            String derivedText = formatRxLines(rxLines);
            visit.setPrescriptionText(derivedText != null ? derivedText
                    : (prescriptionText != null && !prescriptionText.isBlank() ? prescriptionText : null));
            persistRxLinesAndProcurement(visit, visit.getClinicId(), userId, rxLines);
        }

        // Rewrite Investigations ONLY when caller supplied them. Same soft-delete pattern.
        if (investigations != null) {
            investigationItemRepository.findByVisitIdAndActiveTrueOrderByLineNoAsc(visitId).forEach(old -> {
                old.setActive(false);
                investigationItemRepository.save(old);
            });
            persistInvestigations(visit, investigations);
        }

        return visitRepository.save(visit);
    }

    /**
     * A visit must belong to a patient of the same clinic. Compared against the explicit
     * {@code clinicId} (not just {@code TenantContext}) so the guard holds for every caller.
     */
    private void requirePatientInClinic(Long patientId, Long clinicId) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));
        if (!clinicId.equals(patient.getClinicId())) {
            throw new CrossTenantAccessException();
        }
    }

    private void persistInvestigations(Visit visit, List<InvestigationLine> lines) {
        if (lines == null) return;
        int lineNo = 1;
        for (InvestigationLine line : lines) {
            if (line.getName() == null || line.getName().isBlank()) continue;
            String normalized = masterService.normalizeInvestigation(visit.getClinicId(), line.getName());
            investigationItemRepository.save(InvestigationItem.builder()
                    .clinicId(visit.getClinicId())
                    .visitId(visit.getId())
                    .lineNo(lineNo++)
                    .name(normalized)
                    .instructions(line.getInstructions() == null || line.getInstructions().isBlank()
                            ? null : line.getInstructions().trim())
                    .status(InvestigationItem.Status.PENDING)
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public List<InvestigationItem> investigationsFor(Long visitId) {
        return investigationItemRepository.findByVisitIdOrderByLineNoAsc(visitId);
    }

    private void persistRxLinesAndProcurement(Visit visit, Long clinicId, Long userId, List<RxLine> lines) {
        int lineNo = 1;
        for (RxLine line : lines) {
            if (!line.hasContent()) continue;

            Medicine medicine = line.getMedicineId() == null ? null
                    : TenantGuard.check(
                            medicineRepository.findById(line.getMedicineId()).orElse(null),
                            Medicine::getClinicId);
            String displayName = medicine != null ? medicine.getName() : line.getCustomName();
            if (displayName == null || displayName.isBlank()) continue;

            prescriptionItemRepository.save(PrescriptionItem.builder()
                    .clinicId(clinicId)
                    .visitId(visit.getId())
                    .lineNo(lineNo++)
                    .medicineId(medicine != null ? medicine.getId() : null)
                    .customName(medicine == null ? line.getCustomName() : null)
                    .dosage(line.getDosage())
                    .frequency(line.getFrequency())
                    .duration(line.getDuration())
                    .instructions(line.getInstructions())
                    .build());

            // Custom (not-in-inventory) medicine → add to procurement queue
            if (medicine == null) {
                procurementItemRepository.save(ProcurementItem.builder()
                        .clinicId(clinicId)
                        .medicineName(displayName)
                        .source(ProcurementItem.Source.PRESCRIPTION)
                        .sourceVisitId(visit.getId())
                        .status(ProcurementItem.Status.PENDING)
                        .notes("Prescribed in visit " + visit.getEncounterNo() + ". Not in inventory.")
                        .createdBy(userId)
                        .build());
            }
        }
    }

    /** Render structured Rx lines as the legacy prescription_text format. */
    private String formatRxLines(List<RxLine> lines) {
        if (lines == null || lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (RxLine line : lines) {
            if (!line.hasContent()) continue;
            any = true;
            String name = line.getMedicineId() != null
                    ? medicineRepository.findById(line.getMedicineId())
                            .map(m -> TenantGuard.check(m, Medicine::getClinicId))
                            .map(Medicine::getName)
                            .orElse(line.getCustomName())
                    : line.getCustomName();
            if (sb.length() > 0) sb.append('\n');
            sb.append(name == null ? "—" : name);
            if (line.getDosage() != null && !line.getDosage().isBlank()) sb.append(" · ").append(line.getDosage());
            if (line.getFrequency() != null && !line.getFrequency().isBlank()) sb.append(" · ").append(line.getFrequency());
            if (line.getDuration() != null && !line.getDuration().isBlank()) sb.append(" · ").append(line.getDuration());
            if (line.getInstructions() != null && !line.getInstructions().isBlank()) sb.append(" · ").append(line.getInstructions());
        }
        return any ? sb.toString() : null;
    }

    @Transactional(readOnly = true)
    public Visit getOrThrow(Long id) {
        Visit v = visitRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Visit not found: " + id));
        return com.clinicops.app.tenant.TenantGuard.check(v, Visit::getClinicId);
    }

    @Transactional(readOnly = true)
    public List<PrescriptionItem> rxItemsFor(Long visitId) {
        return prescriptionItemRepository.findByVisitIdOrderByLineNoAsc(visitId);
    }

    @Transactional(readOnly = true)
    public List<Visit> followUpsFor(Long clinicId, LocalDate date) {
        return visitRepository.findByClinicIdAndFollowUpDate(clinicId, date);
    }

    @Transactional(readOnly = true)
    public List<Visit> followUpsBetween(Long clinicId, LocalDate from, LocalDate to) {
        return visitRepository.findByClinicIdAndFollowUpDateBetweenOrderByFollowUpDateAsc(
                clinicId, from, to);
    }

    /** Form-side carrier for an investigation row. */
    @lombok.Value
    public static class InvestigationLine {
        String name;
        String instructions;
    }

    public static List<InvestigationLine> parseInvestigationLines(String[] names, String[] instructions) {
        if (names == null) return new ArrayList<>();
        List<InvestigationLine> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String name = names[i] == null ? null : names[i].trim();
            if (name == null || name.isEmpty()) continue;
            String inst = instructions != null && i < instructions.length ? instructions[i] : null;
            out.add(new InvestigationLine(name, inst == null ? null : inst.trim()));
        }
        return out;
    }

    /** Form-side carrier for vitals. All fields optional. */
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class Vitals {
        public final Integer bpSystolic;
        public final Integer bpDiastolic;
        public final Integer pulse;
        public final java.math.BigDecimal temperatureF;
        public final Integer respiratoryRate;
        public final Integer spo2;
        public final java.math.BigDecimal weightKg;
        public final java.math.BigDecimal heightCm;
        public final Integer bloodGlucose;
        public final String bloodGlucoseType;

        public boolean isEmpty() {
            return bpSystolic == null && bpDiastolic == null && pulse == null
                    && temperatureF == null && respiratoryRate == null && spo2 == null
                    && weightKg == null && heightCm == null && bloodGlucose == null;
        }
    }

    /** Form-side carrier for a single Rx row. */
    @Value
    public static class RxLine {
        Long medicineId;     // null for custom
        String customName;   // typed name when medicineId is null
        String dosage;
        String frequency;
        String duration;
        String instructions;

        public boolean hasContent() {
            return medicineId != null
                    || (customName != null && !customName.isBlank())
                    || (dosage != null && !dosage.isBlank())
                    || (duration != null && !duration.isBlank());
        }
    }

    public static List<RxLine> parseRxLines(String[] medicineIds, String[] customNames,
                                            String[] dosages, String[] frequencies,
                                            String[] durations, String[] instructions) {
        if (medicineIds == null && customNames == null) return new ArrayList<>();
        int len = max(medicineIds, customNames, dosages, frequencies, durations, instructions);
        List<RxLine> out = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            String midStr = at(medicineIds, i);
            Long mid = null;
            if (midStr != null && !midStr.isBlank()) {
                try { mid = Long.parseLong(midStr.trim()); } catch (NumberFormatException ignored) {}
            }
            out.add(new RxLine(mid, at(customNames, i), at(dosages, i),
                    at(frequencies, i), at(durations, i), at(instructions, i)));
        }
        return out;
    }

    private static String at(String[] arr, int i) {
        if (arr == null || i >= arr.length) return null;
        String s = arr[i];
        return s == null ? null : s.trim().isEmpty() ? null : s.trim();
    }

    private static int max(String[]... arrs) {
        int m = 0;
        for (String[] a : arrs) if (a != null && a.length > m) m = a.length;
        return m;
    }
}
