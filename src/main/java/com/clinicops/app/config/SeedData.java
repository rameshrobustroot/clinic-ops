package com.clinicops.app.config;

import com.clinicops.app.domain.*;
import com.clinicops.app.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Populates demo data on first start. Idempotent: skips when medicines already exist.
 * Designed to exercise every screen + every alert tier.
 */
@Component
@org.springframework.context.annotation.Profile("!test")
@RequiredArgsConstructor
@Order(100)
@Slf4j
public class SeedData {

    private static final Long CLINIC_ID = 1L;

    private final MedicineRepository medicineRepository;
    private final StockBatchRepository stockBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SupplierRepository supplierRepository;
    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final AppUserRepository userRepository;
    private final com.clinicops.app.repository.DoctorRepository doctorRepository;
    private final com.clinicops.app.service.MedicalIdSequence medicalIdSequence;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (medicineRepository.count() > 0) {
            log.info("Seed data: medicines already exist, skipping.");
            return;
        }
        log.info("Seed data: populating demo data...");

        Long adminId = userRepository.findByClinicIdAndUsername(CLINIC_ID, "admin").map(AppUser::getId).orElse(null);

        // Ensure the admin user has a Doctor row — visit.doctor_id → doctor.id (since changeset 017)
        Long doctorId = null;
        if (adminId != null) {
            doctorId = doctorRepository.findByClinicIdOrderByFullNameAsc(CLINIC_ID).stream()
                    .filter(d -> adminId.equals(d.getUserId()))
                    .findFirst()
                    .map(Doctor::getId)
                    .orElseGet(() -> doctorRepository.save(Doctor.builder()
                            .clinicId(CLINIC_ID)
                            .fullName("Admin Owner")
                            .userId(adminId)
                            .active(true)
                            .build()).getId());
        }

        // Suppliers
        Supplier apex = supplierRepository.save(Supplier.builder()
                .clinicId(CLINIC_ID).name("Apex Pharma Distributors").phone("9876543210").build());
        Supplier metro = supplierRepository.save(Supplier.builder()
                .clinicId(CLINIC_ID).name("Metro Medical Supplies").phone("9811122233").build());

        // Medicines + batches (varied expiry to populate every alerts tab)
        LocalDate today = LocalDate.now();

        Medicine paracetamol = saveMedicine("Crocin 500", "Paracetamol", "500mg", "TABLET",
                "GSK", "STRIP", 20);
        addBatch(paracetamol, "PCM-A-2412", today.plusDays(180), 80, "1.20", "2.00", apex, adminId);
        addBatch(paracetamol, "PCM-A-2411", today.plusDays(45),  30, "1.20", "2.00", apex, adminId); // near (<90d)

        Medicine amoxicillin = saveMedicine("Mox 500", "Amoxicillin", "500mg", "CAPSULE",
                "Cipla", "STRIP", 15);
        addBatch(amoxicillin, "AMX-2510", today.plusDays(15), 12, "3.50", "6.00", apex, adminId); // critical (<30d)
        addBatch(amoxicillin, "AMX-2509", today.plusDays(220), 60, "3.50", "6.00", apex, adminId);

        Medicine azithromycin = saveMedicine("Azee 500", "Azithromycin", "500mg", "TABLET",
                "Cipla", "STRIP", 10);
        addBatch(azithromycin, "AZI-2403", today.minusDays(8), 6, "5.20", "9.00", apex, adminId); // EXPIRED

        Medicine pantoprazole = saveMedicine("Pan 40", "Pantoprazole", "40mg", "TABLET",
                "Alkem", "STRIP", 25);
        addBatch(pantoprazole, "PAN-2602", today.plusDays(420), 120, "2.80", "5.00", metro, adminId);

        Medicine ors = saveMedicine("Electral", "Oral Rehydration Salts", null, "POWDER",
                "FDC", "BOTTLE", 10);
        addBatch(ors, "ORS-25-08", today.plusDays(70), 8, "18.00", "25.00", metro, adminId); // low stock + near

        Medicine cetirizine = saveMedicine("Cetzine", "Cetirizine", "10mg", "TABLET",
                "Dr. Reddy's", "STRIP", 15);
        addBatch(cetirizine, "CET-2606", today.plusDays(500), 200, "1.80", "3.00", apex, adminId);

        Medicine cough = saveMedicine("Ascoril LS", "Levosalbutamol+Ambroxol+Guaifenesin", "100ml", "SYRUP",
                "Glenmark", "BOTTLE", 8);
        addBatch(cough, "ASC-2511", today.plusDays(25), 5, "85.00", "120.00", metro, adminId); // low + critical

        Medicine metformin = saveMedicine("Glycomet 500", "Metformin", "500mg", "TABLET",
                "USV", "STRIP", 20);
        addBatch(metformin, "MET-2604", today.plusDays(310), 90, "1.10", "2.20", apex, adminId);

        Medicine vitaminB = saveMedicine("Becosules", "Vitamin B Complex", null, "CAPSULE",
                "Pfizer", "STRIP", 15);
        addBatch(vitaminB, "BEC-2603", today.plusDays(260), 50, "2.40", "4.50", metro, adminId);

        Medicine omeprazole = saveMedicine("Omez 20", "Omeprazole", "20mg", "CAPSULE",
                "Dr. Reddy's", "STRIP", 20);
        // No batches — appears as "no stock" in inventory + low-stock alert

        // Patients
        Patient rajesh = patientRepository.save(Patient.builder()
                .clinicId(CLINIC_ID).mrn(medicalIdSequence.nextPatientMrn())
                .name("Rajesh Kumar").phone("9812345001").age(48).gender("MALE")
                .address("MG Road, Bengaluru").allergies("Penicillin")
                .notes("Hypertension. On amlodipine.").build());
        Patient priya = patientRepository.save(Patient.builder()
                .clinicId(CLINIC_ID).mrn(medicalIdSequence.nextPatientMrn())
                .name("Priya Sharma").phone("9812345002").age(32).gender("FEMALE")
                .address("Indiranagar, Bengaluru").build());
        Patient arjun = patientRepository.save(Patient.builder()
                .clinicId(CLINIC_ID).mrn(medicalIdSequence.nextPatientMrn())
                .name("Arjun Reddy").phone("9812345003").age(7).gender("MALE")
                .notes("Mother: Lakshmi · 9812345099").build());

        // Visits
        Visit rajeshOld = visitRepository.save(Visit.builder()
                .clinicId(CLINIC_ID)
                .patientId(rajesh.getId()).doctorId(doctorId)
                .encounterType("OPD").encounterNo(medicalIdSequence.nextEncounterNo("OPD"))
                .visitDate(Instant.now().minus(40, ChronoUnit.DAYS))
                .diagnosis("Routine BP review").notes("BP 138/86. Continue current meds.")
                .prescriptionText("Tab Amlodipine 5mg · 1-0-0 · 30 days")
                .followUpDate(today.minusDays(10))
                .build());

        visitRepository.save(Visit.builder()
                .clinicId(CLINIC_ID)
                .patientId(rajesh.getId()).doctorId(doctorId)
                .encounterType("OPD").encounterNo(medicalIdSequence.nextEncounterNo("OPD"))
                .visitDate(Instant.now().minus(3, ChronoUnit.HOURS))
                .diagnosis("Mild fever, body ache").notes("Temp 100.4F. Throat clear.")
                .prescriptionText("Tab Crocin 500 · 1-0-1 · 3 days\nORS sachets · as needed")
                .followUpDate(today.plusDays(5))
                .build());

        visitRepository.save(Visit.builder()
                .clinicId(CLINIC_ID)
                .patientId(priya.getId()).doctorId(doctorId)
                .encounterType("OPD").encounterNo(medicalIdSequence.nextEncounterNo("OPD"))
                .visitDate(Instant.now().minus(2, ChronoUnit.DAYS))
                .diagnosis("Allergic rhinitis")
                .prescriptionText("Tab Cetzine 10mg · 0-0-1 · 7 days")
                .build());

        visitRepository.save(Visit.builder()
                .clinicId(CLINIC_ID)
                .patientId(arjun.getId()).doctorId(doctorId)
                .encounterType("OPD").encounterNo(medicalIdSequence.nextEncounterNo("OPD"))
                .visitDate(Instant.now().minus(1, ChronoUnit.HOURS))
                .diagnosis("Acute viral pharyngitis")
                .notes("Throat congested. Afebrile.")
                .prescriptionText("Syp Ascoril LS · 5ml · 1-0-1 · 5 days\nORS · 1 sachet if needed")
                .followUpDate(today.plusDays(3))
                .build());

        // Sales — to populate dashboard "top medicines (7d)"
        recordSale(rajesh.getId(), List.of(
                new SaleLine(paracetamol, 1, "20.00"),
                new SaleLine(ors, 2, "50.00")
        ), adminId);
        recordSale(priya.getId(), List.of(
                new SaleLine(cetirizine, 1, "30.00")
        ), adminId);
        recordSale(arjun.getId(), List.of(
                new SaleLine(cough, 1, "120.00"),
                new SaleLine(ors, 1, "25.00")
        ), adminId);
        recordSale(null, List.of(
                new SaleLine(paracetamol, 2, "40.00"),
                new SaleLine(pantoprazole, 1, "50.00")
        ), adminId);

        log.info("Seed data: done — 10 medicines, ~13 batches, 3 patients, 4 visits, 4 sales.");
    }

    private Medicine saveMedicine(String name, String generic, String strength, String form,
                                   String mfg, String unit, int lowStock) {
        return medicineRepository.save(Medicine.builder()
                .clinicId(CLINIC_ID).name(name).genericName(generic)
                .strength(strength).form(form).manufacturer(mfg)
                .unit(unit).lowStockThreshold(lowStock)
                .build());
    }

    private StockBatch addBatch(Medicine m, String batchNo, LocalDate expiry, int qty,
                                 String buy, String sell, Supplier supplier, Long userId) {
        StockBatch batch = stockBatchRepository.save(StockBatch.builder()
                .clinicId(CLINIC_ID)
                .medicineId(m.getId()).supplierId(supplier.getId())
                .batchNumber(batchNo).expiryDate(expiry)
                .quantity(qty).initialQuantity(qty)
                .purchasePrice(new BigDecimal(buy)).salePrice(new BigDecimal(sell))
                .receivedDate(LocalDate.now().minusDays(30))
                .build());
        stockMovementRepository.save(StockMovement.builder()
                .clinicId(CLINIC_ID)
                .stockBatchId(batch.getId())
                .movementType(StockMovement.MovementType.IN)
                .quantity(qty).referenceType("SEED").createdBy(userId)
                .build());
        return batch;
    }

    private record SaleLine(Medicine medicine, int qty, String lineTotal) {}

    private void recordSale(Long patientId, List<SaleLine> lines, Long userId) {
        BigDecimal total = lines.stream()
                .map(l -> new BigDecimal(l.lineTotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Sale sale = saleRepository.save(Sale.builder()
                .clinicId(CLINIC_ID).billNo(medicalIdSequence.nextBillNo())
                .patientId(patientId)
                .totalAmount(total).paymentMode("CASH").createdBy(userId)
                .walkInName(patientId == null ? "Walk-in" : null)
                .build());

        for (SaleLine line : lines) {
            StockBatch firstBatch = stockBatchRepository
                    .findByMedicineIdOrderByExpiryDateAsc(line.medicine().getId()).stream()
                    .filter(b -> b.getQuantity() > 0).findFirst().orElse(null);
            if (firstBatch == null) continue;

            BigDecimal lineTotal = new BigDecimal(line.lineTotal());
            BigDecimal unitPrice = lineTotal.divide(BigDecimal.valueOf(line.qty()), 2, java.math.RoundingMode.HALF_UP);

            saleItemRepository.save(SaleItem.builder()
                    .clinicId(CLINIC_ID)
                    .saleId(sale.getId()).stockBatchId(firstBatch.getId())
                    .medicineId(line.medicine().getId()).quantity(line.qty())
                    .unitPrice(unitPrice).grossAmount(lineTotal).lineTotal(lineTotal).build());

            firstBatch.setQuantity(firstBatch.getQuantity() - line.qty());
            stockBatchRepository.save(firstBatch);

            stockMovementRepository.save(StockMovement.builder()
                    .clinicId(CLINIC_ID)
                    .stockBatchId(firstBatch.getId())
                    .movementType(StockMovement.MovementType.OUT)
                    .quantity(line.qty())
                    .referenceId(sale.getId()).referenceType("SALE")
                    .createdBy(userId).build());
        }
    }
}
