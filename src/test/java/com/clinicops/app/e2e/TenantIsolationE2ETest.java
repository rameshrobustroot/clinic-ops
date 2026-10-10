package com.clinicops.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.domain.Role;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.repository.ProcurementItemRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.repository.StockBatchRepository;
import com.clinicops.app.repository.VisitRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Cross-tenant HTTP probes: a user of clinic A requests clinic B's records by id, through every
 * detail / edit / write endpoint, and must neither see nor change them. Cross-tenant loads are
 * expected to surface as 404 (see GlobalExceptionHandler) — never 200 with the data.
 */
class TenantIsolationE2ETest extends BaseWebIntegrationTest {

    static final String B_PATIENT = "Zed Bravoclinic";
    static final String B_MEDICINE = "Bravocillin";
    static final String B_DIAGNOSIS = "Bravo-secret-diagnosis";
    static final String B_BATCH = "BRAVO-BATCH-9";
    static final String B_BILL = "BRAVO-BILL-1";
    static final String B_PROCUREMENT = "Bravo-procure-item";

    @Autowired PatientRepository patientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired SaleRepository saleRepository;
    @Autowired ProcurementItemRepository procurementItemRepository;

    Clinic clinicB;
    AppUser ownerB;
    Long patientB, visitB, medicineB, batchB, saleB;

    @BeforeEach
    void setUpClinicB() {
        clinicB = createClinic("e2eb" + suffix, "E2E Clinic B");
        ownerB = createUser(clinicB.getId(), "owner-b-" + suffix, Role.OWNER, true);
        Long doctorB = createDoctor(clinicB.getId(), "Dr. Bravo").getId();

        patientB = patientRepository.save(Patient.builder()
                .clinicId(clinicB.getId()).name(B_PATIENT).phone("9811111111")
                .mrn("MRN-B-" + suffix).build()).getId();
        visitB = visitRepository.save(Visit.builder()
                .clinicId(clinicB.getId()).patientId(patientB).doctorId(doctorB)
                .encounterType("OPD").encounterNo("OPD-B-" + suffix)
                .diagnosis(B_DIAGNOSIS).build()).getId();
        medicineB = medicineRepository.save(Medicine.builder()
                .clinicId(clinicB.getId()).name(B_MEDICINE).strength("500mg").unit("tab")
                .lowStockThreshold(5).active(true).build()).getId();
        batchB = stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinicB.getId()).medicineId(medicineB).batchNumber(B_BATCH)
                .expiryDate(LocalDate.now().plusDays(20)).quantity(40).initialQuantity(40)
                .salePrice(new BigDecimal("3.00")).build()).getId();
        saleB = saleRepository.save(Sale.builder()
                .clinicId(clinicB.getId()).billNo(B_BILL).patientId(patientB)
                .subtotalAmount(new BigDecimal("30.00")).billDiscountAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("30.00")).paymentMode("CASH").build()).getId();
        procurementItemRepository.save(ProcurementItem.builder()
                .clinicId(clinicB.getId()).medicineName(B_PROCUREMENT)
                .source(ProcurementItem.Source.PRESCRIPTION)
                .status(ProcurementItem.Status.PENDING).build());

        // A visible patient for clinic A so list pages have something legitimate to render.
        patientRepository.save(Patient.builder()
                .clinicId(clinic.getId()).name("Alpha Own Patient").phone("9822222222")
                .mrn("MRN-A-" + suffix).build());
        resetSession();
    }

    /** Asserts the response is the not-found page and leaks none of clinic B's identifying data. */
    private void assertHidden(ResultActions r) throws Exception {
        r.andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(B_PATIENT))))
                .andExpect(content().string(not(containsString(B_DIAGNOSIS))))
                .andExpect(content().string(not(containsString(B_MEDICINE))))
                .andExpect(content().string(not(containsString(B_BILL))));
        resetSession();
    }

    // ── Detail / edit pages by id ────────────────────────────────────────

    @Test
    void patientDetailAndEditOfOtherClinicAreNotFound() throws Exception {
        assertHidden(mvc.perform(get("/patients/" + patientB).with(owner())));
        assertHidden(mvc.perform(get("/patients/" + patientB + "/edit").with(owner())));
    }

    @Test
    void visitPagesOfOtherClinicAreNotFound() throws Exception {
        assertHidden(mvc.perform(get("/visits/" + visitB).with(owner())));
        assertHidden(mvc.perform(get("/visits/" + visitB + "/edit").with(owner())));
        assertHidden(mvc.perform(get("/visits/" + visitB + "/prescription.pdf").with(owner())));
        assertHidden(mvc.perform(get("/visits/new").param("patientId", patientB.toString()).with(owner())));
    }

    @Test
    void salePagesOfOtherClinicAreNotFound() throws Exception {
        assertHidden(mvc.perform(get("/sales/" + saleB).with(owner())));
        assertHidden(mvc.perform(get("/sales/" + saleB + "/receipt.pdf").with(owner())));
        assertHidden(mvc.perform(get("/sales/new").param("fromVisit", visitB.toString()).with(owner())));
    }

    @Test
    void newSaleFormPreselectingOtherClinicPatientDoesNotRevealThem() throws Exception {
        // SaleController.addFormModel loads the patient with a bare findById; the entity listener
        // is what stops this. Either a 404 or a form without the patient is acceptable — never the name.
        mvc.perform(get("/sales/new").param("patientId", patientB.toString()).with(owner()))
                .andExpect(content().string(not(containsString(B_PATIENT))));
    }

    @Test
    void inventoryMedicinePagesOfOtherClinicAreNotFound() throws Exception {
        assertHidden(mvc.perform(get("/inventory/medicines/" + medicineB).with(owner())));
        assertHidden(mvc.perform(get("/inventory/medicines/" + medicineB + "/edit").with(owner())));
    }

    // ── Writes against other clinic's ids ────────────────────────────────

    @Test
    void cannotUpdateOtherClinicPatient() throws Exception {
        mvc.perform(post("/patients/" + patientB).with(owner()).with(csrf())
                        .param("name", "Hijacked Name").param("phone", "9899999999"))
                .andExpect(status().isNotFound());
        resetSession();
        assertThat(patientRepository.findById(patientB).orElseThrow().getName()).isEqualTo(B_PATIENT);
    }

    @Test
    void cannotAdjustOrReceiveStockOnOtherClinicBatchOrMedicine() throws Exception {
        mvc.perform(post("/inventory/batches/" + batchB + "/adjust").with(owner()).with(csrf())
                        .param("medicineId", medicineB.toString()).param("newQuantity", "0"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/inventory/medicines/" + medicineB + "/batches").with(owner()).with(csrf())
                        .param("batchNumber", "INJECT-1")
                        .param("expiryDate", LocalDate.now().plusDays(200).toString())
                        .param("quantity", "5"))
                .andExpect(status().isNotFound());
        resetSession();
        assertThat(stockBatchRepository.findById(batchB).orElseThrow().getQuantity()).isEqualTo(40);
        assertThat(stockBatchRepository.findByMedicineIdOrderByExpiryDateAsc(medicineB)).hasSize(1);
    }

    @Test
    void cannotSellOtherClinicMedicine() throws Exception {
        mvc.perform(post("/sales").with(owner()).with(csrf())
                        .param("paymentMode", "CASH")
                        .param("medicineId", medicineB.toString())
                        .param("quantity", "5"))
                .andExpect(status().isNotFound());
        resetSession();
        assertThat(stockBatchRepository.findById(batchB).orElseThrow().getQuantity()).isEqualTo(40);
        // (No assertion on the sale header row: SaleService.record inserts it before the medicine
        // check and relies on transaction rollback, which the enclosing test transaction defers.)
        assertThat(stockBatchRepository.findAll()).filteredOn(b -> medicineB.equals(b.getMedicineId()))
                .extracting(StockBatch::getClinicId).containsOnly(clinicB.getId());
    }

    @Test
    void cannotCreateVisitForOtherClinicPatient() throws Exception {
        mvc.perform(post("/visits").with(owner()).with(csrf())
                        .param("patientId", patientB.toString())
                        .param("doctorId", doctor.getId().toString())
                        .param("diagnosis", "cross-tenant write"))
                .andExpect(status().isNotFound());
        resetSession();
        assertThat(visitRepository.findAll())
                .filteredOn(v -> patientB.equals(v.getPatientId()))
                .extracting(Visit::getClinicId)
                .containsOnly(clinicB.getId());
    }

    @Test
    void cannotBillOtherClinicPatient() throws Exception {
        Long medA = medicineRepository.save(Medicine.builder()
                .clinicId(clinic.getId()).name("Alphamol").unit("tab").active(true).build()).getId();
        stockBatchRepository.save(StockBatch.builder()
                .clinicId(clinic.getId()).medicineId(medA).batchNumber("A-1")
                .expiryDate(LocalDate.now().plusDays(300)).quantity(10).initialQuantity(10)
                .salePrice(BigDecimal.ONE).build());
        resetSession();

        mvc.perform(post("/sales").with(owner()).with(csrf())
                        .param("patientId", patientB.toString())
                        .param("paymentMode", "CASH")
                        .param("medicineId", medA.toString())
                        .param("quantity", "1"))
                .andExpect(status().isNotFound());
        resetSession();
        assertThat(saleRepository.findAll())
                .filteredOn(s -> patientB.equals(s.getPatientId()))
                .extracting(Sale::getClinicId)
                .containsOnly(clinicB.getId());
        // And clinic A's sales list must still render.
        mvc.perform(get("/sales").with(owner())).andExpect(status().isOk());
    }

    // ── List / search / export pages ─────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/patients", "/patients/search?q=Bravo", "/patients/search?q=Zed", "/patients/search.json?q=Zed",
            "/inventory", "/inventory/search?q=Bravo", "/sales", "/alerts", "/procurement", "/procurement?view=all",
            "/export/patients.csv", "/export/sales.csv", "/export/inventory.csv"})
    void listSearchAndExportPagesNeverShowOtherClinicData(String url) throws Exception {
        mvc.perform(get(url).with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(B_PATIENT))))
                .andExpect(content().string(not(containsString(B_MEDICINE))))
                .andExpect(content().string(not(containsString(B_BATCH))))
                .andExpect(content().string(not(containsString(B_BILL))))
                .andExpect(content().string(not(containsString(B_PROCUREMENT))));
    }

    @Test
    void patientListShowsOwnClinicPatientsOnly() throws Exception {
        mvc.perform(get("/patients").with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alpha Own Patient")))
                .andExpect(content().string(not(containsString(B_PATIENT))));
        // And the reverse: clinic B's owner sees B's patient but not A's.
        mvc.perform(get("/patients").with(as(ownerB)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(B_PATIENT)))
                .andExpect(content().string(not(containsString("Alpha Own Patient"))));
    }

    // ── Independent id sequences per clinic ──────────────────────────────

    @Test
    void twoClinicsCanEachRegisterPatientsAndBillIndependently() throws Exception {
        // MRN and bill numbers are globally-unique columns. Clinic A then clinic B each register a
        // patient and bill them over HTTP; neither flow may collide with the other clinic's numbers.
        // (MedicalIdSequence uses a global counter table, so this holds even when the tenant
        // filter is already active — see MedicalIdSequenceTest.TenantFilter.) Billing is done by
        // the PHARMACIST because receptionists may not record sales.
        AppUser recB = createUser(clinicB.getId(), "rec-b-" + suffix, Role.RECEPTIONIST, true);
        AppUser pharmB = createUser(clinicB.getId(), "pharm-b-" + suffix, Role.PHARMACIST, true);
        AppUser[][] actors = {
                {user(Role.RECEPTIONIST), user(Role.PHARMACIST)},
                {recB, pharmB}};
        for (AppUser[] pair : actors) {
            Long cid = pair[0].getClinicId();
            Long med = medicineRepository.save(Medicine.builder()
                    .clinicId(cid).name("Seqmed-" + cid).unit("tab").active(true).build()).getId();
            stockBatchRepository.save(StockBatch.builder()
                    .clinicId(cid).medicineId(med).batchNumber("SEQ-" + cid)
                    .expiryDate(LocalDate.now().plusDays(300)).quantity(10).initialQuantity(10)
                    .salePrice(BigDecimal.TEN).build());
            resetSession();

            mvc.perform(post("/patients").with(as(pair[0])).with(csrf())
                            .param("name", "Seq Patient " + cid).param("phone", "9833333333"))
                    .andExpect(status().is3xxRedirection());
            resetSession();
            Long pid = patientRepository.findAll().stream()
                    .filter(p -> ("Seq Patient " + cid).equals(p.getName())).findFirst().orElseThrow().getId();

            mvc.perform(post("/sales").with(as(pair[1])).with(csrf())
                            .param("patientId", pid.toString()).param("paymentMode", "CASH")
                            .param("medicineId", med.toString()).param("quantity", "1"))
                    .andExpect(status().is3xxRedirection());
            resetSession();
        }
        assertThat(patientRepository.findAll()).extracting(Patient::getMrn).doesNotHaveDuplicates();
        assertThat(saleRepository.findAll()).extracting(Sale::getBillNo).doesNotHaveDuplicates();
    }
}
