package com.clinicops.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.PrescriptionItem;
import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.domain.Role;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.repository.PrescriptionItemRepository;
import com.clinicops.app.repository.ProcurementItemRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.repository.StockBatchRepository;
import com.clinicops.app.repository.VisitRepository;
import com.clinicops.app.service.DashboardService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The core day-in-the-clinic workflow, driven exclusively through HTTP with the real form field
 * names: stock in → register patient → consult with structured Rx → bill from inventory (FEFO)
 * → alerts / dashboard / export reflect it.
 */
class ClinicalWorkflowE2ETest extends BaseWebIntegrationTest {

    static final String MED = "Paracetamol E2E";
    static final String CUSTOM_MED = "Zincovit Custom";
    static final String PATIENT = "Meera Krishnan";

    @Autowired MedicineRepository medicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired PrescriptionItemRepository prescriptionItemRepository;
    @Autowired ProcurementItemRepository procurementItemRepository;
    @Autowired SaleRepository saleRepository;

    // ── Helpers (each one is a real HTTP round-trip) ─────────────────────

    private Long createMedicineViaHttp(String name) throws Exception {
        mvc.perform(post("/inventory/medicines").with(owner()).with(csrf())
                        .param("name", name).param("genericName", "Acetaminophen")
                        .param("strength", "500mg").param("form", "TABLET")
                        .param("unit", "tab").param("lowStockThreshold", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventory"))
                .andExpect(flash().attribute("flash", "Medicine added: " + name));
        resetSession();
        return medicineRepository.findByClinicIdOrderByNameAsc(clinic.getId()).stream()
                .filter(m -> name.equals(m.getName())).findFirst().orElseThrow().getId();
    }

    private void receiveBatchViaHttp(Long medId, String batchNo, LocalDate expiry, int qty, String salePrice)
            throws Exception {
        mvc.perform(post("/inventory/medicines/" + medId + "/batches").with(as(Role.PHARMACIST)).with(csrf())
                        .param("batchNumber", batchNo).param("expiryDate", expiry.toString())
                        .param("quantity", String.valueOf(qty))
                        .param("purchasePrice", "1.00").param("salePrice", salePrice))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventory/medicines/" + medId));
        resetSession();
    }

    private Long registerPatientViaHttp(String name, String phone) throws Exception {
        mvc.perform(post("/patients").with(as(Role.RECEPTIONIST)).with(csrf())
                        .param("name", name).param("phone", phone).param("age", "34")
                        .param("gender", "F").param("allergies", "Penicillin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/patients"))
                .andExpect(flash().attribute("flash", startsWith("Registered " + name)));
        resetSession();
        return patientRepository.findAll().stream()
                .filter(p -> name.equals(p.getName()) && clinic.getId().equals(p.getClinicId()))
                .findFirst().orElseThrow().getId();
    }

    private int qty(String batchNo) {
        return stockBatchRepository.findAll().stream()
                .filter(b -> batchNo.equals(b.getBatchNumber()) && clinic.getId().equals(b.getClinicId()))
                .findFirst().orElseThrow().getQuantity();
    }

    private static Long idFromRedirect(MvcResult r, String prefix) {
        String url = r.getResponse().getRedirectedUrl();
        assertThat(url).startsWith(prefix);
        return Long.parseLong(url.substring(prefix.length()));
    }

    // ── The full workflow ────────────────────────────────────────────────

    @Test
    void stockIn_register_consultWithRx_bill_thenAlertsDashboardAndExportReflectIt() throws Exception {
        // 1. Stock in: one medicine, a near-expiry batch and a long-dated batch.
        Long medId = createMedicineViaHttp(MED);
        receiveBatchViaHttp(medId, "NEAR-01", LocalDate.now().plusDays(45), 10, "2.00");
        receiveBatchViaHttp(medId, "FAR-01", LocalDate.now().plusDays(300), 20, "2.50");

        mvc.perform(get("/inventory/medicines/" + medId).with(owner()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("total", 30))
                .andExpect(content().string(containsString("NEAR-01")))
                .andExpect(content().string(containsString("FAR-01")));

        // 2. Alerts: the 45-day batch is "near expiry"; the 300-day one is not listed.
        mvc.perform(get("/alerts").with(as(Role.PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("batch #NEAR-01")))
                .andExpect(content().string(not(containsString("batch #FAR-01"))));

        // 3. Register patient; they appear in the list and via search.
        Long patientId = registerPatientViaHttp(PATIENT, "9845012345");
        Patient patient = patientRepository.findById(patientId).orElseThrow();
        assertThat(patient.getMrn()).startsWith("MRN-" + LocalDate.now().getYear() + "-");
        mvc.perform(get("/patients").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PATIENT)));
        mvc.perform(get("/patients/search").param("q", "Meera").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PATIENT)));

        // 4. Consultation: doctor opens the visit form and saves a structured Rx —
        //    line 1 from inventory, line 2 a custom (not stocked) medicine.
        mvc.perform(get("/visits/new").param("patientId", patientId.toString()).with(as(Role.DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(view().name("visit/form"))
                .andExpect(content().string(containsString(MED)));

        MvcResult created = mvc.perform(post("/visits").with(as(Role.DOCTOR)).with(csrf())
                        .param("patientId", patientId.toString())
                        .param("doctorId", doctor.getId().toString())
                        .param("diagnosis", "Viral fever")
                        .param("vitalPulse", "88")
                        .param("vitalTemperatureF", "100.4")
                        .param("rxMedicineId", medId.toString(), "")
                        .param("rxCustomName", "", CUSTOM_MED)
                        .param("rxDosage", "1-0-1", "0-0-1")
                        .param("rxFrequency", "Twice daily", "Once daily")
                        .param("rxDuration", "5 days", "10 days")
                        .param("rxInstructions", "After food", ""))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Long visitId = idFromRedirect(created, "/visits/");
        resetSession();

        Visit visit = visitRepository.findById(visitId).orElseThrow();
        assertThat(visit.getClinicId()).isEqualTo(clinic.getId());
        assertThat(visit.getPatientId()).isEqualTo(patientId);
        assertThat(visit.getEncounterNo()).startsWith("OPD-");
        List<PrescriptionItem> rx = prescriptionItemRepository.findByVisitIdOrderByLineNoAsc(visitId);
        assertThat(rx).hasSize(2);
        assertThat(rx.get(0).getMedicineId()).isEqualTo(medId);
        assertThat(rx.get(1).getMedicineId()).isNull();
        assertThat(rx.get(1).getCustomName()).isEqualTo(CUSTOM_MED);

        // Visit detail renders both Rx lines, the diagnosis and the encounter no.
        mvc.perform(get("/visits/" + visitId).with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(MED)))
                .andExpect(content().string(containsString(CUSTOM_MED)))
                .andExpect(content().string(containsString("1-0-1")))
                .andExpect(content().string(containsString("Viral fever")))
                .andExpect(content().string(containsString(visit.getEncounterNo())));
        mvc.perform(get("/patients/" + patientId).with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(visit.getEncounterNo())));

        // Only the custom medicine was queued for procurement, linked to this visit.
        List<ProcurementItem> pending = procurementItemRepository
                .findByClinicIdAndStatusOrderByCreatedAtDesc(clinic.getId(), ProcurementItem.Status.PENDING);
        assertThat(pending).extracting(ProcurementItem::getMedicineName).containsExactly(CUSTOM_MED);
        assertThat(pending.get(0).getSourceVisitId()).isEqualTo(visitId);
        assertThat(pending.get(0).getSource()).isEqualTo(ProcurementItem.Source.PRESCRIPTION);
        mvc.perform(get("/procurement").with(as(Role.PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(CUSTOM_MED)));

        // 5. Billing from the visit: the custom Rx line is skipped on the pre-filled form.
        mvc.perform(get("/sales/new").param("fromVisit", visitId.toString()).with(as(Role.PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("customSkipped", 1))
                .andExpect(content().string(containsString(PATIENT)));

        // Sell 12 → FEFO takes all 10 from NEAR-01 (₹2.00) and 2 from FAR-01 (₹2.50) = ₹25.00.
        MvcResult sold = mvc.perform(post("/sales").with(as(Role.PHARMACIST)).with(csrf())
                        .param("patientId", patientId.toString())
                        .param("visitId", visitId.toString())
                        .param("paymentMode", "UPI")
                        .param("medicineId", medId.toString())
                        .param("quantity", "12"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Long saleId = idFromRedirect(sold, "/sales/");
        resetSession();

        assertThat(qty("NEAR-01")).isZero();
        assertThat(qty("FAR-01")).isEqualTo(18);
        Sale sale = saleRepository.findById(saleId).orElseThrow();
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(sale.getPatientId()).isEqualTo(patientId);
        assertThat(sale.getVisitId()).isEqualTo(visitId);
        assertThat(sale.getBillNo()).startsWith("BILL-" + LocalDate.now().getYear() + "-");

        mvc.perform(get("/sales/" + saleId).with(as(Role.PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(sale.getBillNo())))
                .andExpect(content().string(containsString(MED)))
                .andExpect(content().string(containsString(PATIENT)))
                .andExpect(content().string(containsString("₹25.00")));
        mvc.perform(get("/sales").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(sale.getBillNo())));
        // The visit now links to its bill.
        mvc.perform(get("/visits/" + visitId).with(owner()))
                .andExpect(content().string(containsString(sale.getBillNo())));

        // 6. Alerts: the sold-out near-expiry batch drops off.
        mvc.perform(get("/alerts").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("batch #NEAR-01"))));

        // 7. Dashboard reflects today's activity.
        MvcResult dash = mvc.perform(get("/").with(owner()))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/home"))
                .andReturn();
        Map<String, Object> m = dash.getModelAndView().getModel();
        DashboardService.DashboardView dv = (DashboardService.DashboardView) m.get("dashboard");
        assertThat(dv.newPatientsToday()).isEqualTo(1);
        assertThat(dv.visitsToday()).isEqualTo(1);
        assertThat(dv.salesCountToday()).isEqualTo(1);
        assertThat(dv.salesTotalToday()).isEqualByComparingTo("25.00");
        assertThat(dv.topMedicines7d()).extracting(DashboardService.TopMedicine::name).containsExactly(MED);
        assertThat(dv.topMedicines7d().get(0).quantitySold()).isEqualTo(12);
        assertThat(((Number) m.get("pendingProcurement")).longValue()).isEqualTo(1);
        assertThat(dash.getResponse().getContentAsString()).contains(MED);

        // 8. CSV export (owner only) contains the patient row.
        mvc.perform(get("/export/patients.csv").with(owner()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(content().string(containsString(PATIENT)))
                .andExpect(content().string(containsString(patient.getMrn())))
                .andExpect(content().string(containsString("9845012345")));
        mvc.perform(get("/export/sales.csv").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(sale.getBillNo())));
    }

    // ── Focused guard-rails ──────────────────────────────────────────────

    @Test
    void sellingMoreThanInStockIsRejectedWithFriendlyError() throws Exception {
        Long medId = createMedicineViaHttp(MED);
        receiveBatchViaHttp(medId, "ONLY-01", LocalDate.now().plusDays(200), 3, "4.00");

        mvc.perform(post("/sales").with(as(Role.PHARMACIST)).with(csrf())
                        .param("paymentMode", "CASH")
                        .param("medicineId", medId.toString())
                        .param("quantity", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("sale/new"))
                .andExpect(model().attribute("error", containsString("Insufficient stock for " + MED)));
    }

    @Test
    void expiredBatchesAreSkippedByFefoAndPastExpiryCannotBeReceived() throws Exception {
        Long medId = createMedicineViaHttp(MED);
        // Receiving an already-expired batch over HTTP is refused.
        mvc.perform(post("/inventory/medicines/" + medId + "/batches").with(owner()).with(csrf())
                        .param("batchNumber", "PAST-01")
                        .param("expiryDate", LocalDate.now().minusDays(1).toString())
                        .param("quantity", "5"))
                .andExpect(redirectedUrl("/inventory/medicines/" + medId))
                .andExpect(flash().attribute("error", "Batch expiry must be a future date."));
        resetSession();
        assertThat(stockBatchRepository.findByMedicineIdOrderByExpiryDateAsc(medId)).isEmpty();

        // An expired batch that is already on the shelf is skipped by FEFO and shows as expired.
        stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinic.getId()).medicineId(medId).batchNumber("OLD-01")
                .expiryDate(LocalDate.now().minusDays(3)).quantity(50).initialQuantity(50)
                .salePrice(new BigDecimal("1.00")).build());
        resetSession();
        receiveBatchViaHttp(medId, "NEW-01", LocalDate.now().plusDays(200), 10, "3.00");

        mvc.perform(post("/sales").with(as(Role.PHARMACIST)).with(csrf())
                        .param("paymentMode", "CASH").param("walkInName", "Walk In")
                        .param("medicineId", medId.toString()).param("quantity", "4"))
                .andExpect(status().is3xxRedirection());
        resetSession();
        assertThat(qty("OLD-01")).isEqualTo(50);
        assertThat(qty("NEW-01")).isEqualTo(6);

        mvc.perform(get("/alerts").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("batch #OLD-01")));
    }

    @Test
    void visitWithoutDoctorOrContentIsRejected() throws Exception {
        Long patientId = registerPatientViaHttp(PATIENT, "9845012345");
        long before = visitRepository.count();

        mvc.perform(post("/visits").with(as(Role.DOCTOR)).with(csrf())
                        .param("patientId", patientId.toString())
                        .param("diagnosis", "No doctor chosen"))
                .andExpect(status().isOk())
                .andExpect(view().name("visit/form"))
                .andExpect(model().attribute("error", containsString("Select the consulting doctor")));

        mvc.perform(post("/visits").with(as(Role.DOCTOR)).with(csrf())
                        .param("patientId", patientId.toString())
                        .param("doctorId", doctor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("visit/form"))
                .andExpect(model().attribute("error", containsString("Enter at least one")));
        resetSession();
        assertThat(visitRepository.count()).isEqualTo(before);
    }

    @Test
    void pharmacistCannotCreateVisitsAndReceptionistCannotManageInventory() throws Exception {
        Long patientId = registerPatientViaHttp(PATIENT, "9845012345");
        mvc.perform(post("/visits").with(as(Role.PHARMACIST)).with(csrf())
                        .param("patientId", patientId.toString())
                        .param("doctorId", doctor.getId().toString())
                        .param("diagnosis", "x"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/inventory/medicines").with(as(Role.RECEPTIONIST)).with(csrf())
                        .param("name", "Should Not Exist"))
                .andExpect(status().isForbidden());
        resetSession();
        assertThat(medicineRepository.findByClinicIdOrderByNameAsc(clinic.getId()))
                .extracting(Medicine::getName).doesNotContain("Should Not Exist");
    }
}
