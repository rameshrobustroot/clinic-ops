package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.InvestigationItem;
import com.clinicops.app.domain.MedicalInvestigation;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.PrescriptionItem;
import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.domain.Role;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.integration.BaseIntegrationTest;
import com.clinicops.app.repository.InvestigationItemRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PrescriptionItemRepository;
import com.clinicops.app.repository.ProcurementItemRepository;
import com.clinicops.app.repository.VisitRepository;
import com.clinicops.app.service.VisitService.InvestigationLine;
import com.clinicops.app.service.VisitService.RxLine;
import com.clinicops.app.service.VisitService.Vitals;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Integration tests for {@link VisitService}: visit creation/update, structured Rx lines and the
 * procurement queue they feed, investigations, vitals and the tenant guard.
 */
class VisitServiceTest extends BaseIntegrationTest {

    @Autowired VisitService visitService;
    @Autowired VisitRepository visitRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired PrescriptionItemRepository prescriptionItemRepository;
    @Autowired InvestigationItemRepository investigationItemRepository;
    @Autowired ProcurementItemRepository procurementItemRepository;
    @Autowired MedicalMasterService masterService;

    Medicine crocin;   // clinic-private inventory medicine
    Long actingUserId; // the logged-in user saving the visit (procurement created_by → app_user)

    @BeforeEach
    void setUpMedicine() {
        actingUserId = appUserRepository.save(AppUser.builder().clinicId(clinicId)
                .username("visit-actor-" + System.nanoTime()).fullName("Front Desk").role(Role.RECEPTIONIST)
                .passwordHash("x").enabled(true).build()).getId();
        crocin = medicineRepository.save(Medicine.builder()
                .clinicId(clinicId).name("Crocin 500").form("TABLET").build());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static RxLine inventory(Long medId, String dosage, String freq, String duration, String instr) {
        return new RxLine(medId, null, dosage, freq, duration, instr);
    }

    private static RxLine custom(String name, String dosage, String freq, String duration, String instr) {
        return new RxLine(null, name, dosage, freq, duration, instr);
    }

    private Visit create(String text, List<RxLine> rx, Vitals vitals, List<InvestigationLine> inv)
            throws IOException {
        return visitService.create(patientId, doctorId, clinicId, actingUserId, "Fever", "notes",
                null, text, null, rx, vitals, inv);
    }

    private List<PrescriptionItem> allRxRowsFor(Long visitId) {
        return prescriptionItemRepository.findAll().stream()
                .filter(p -> p.getVisitId().equals(visitId)).toList();
    }

    private List<InvestigationItem> allInvestigationRowsFor(Long visitId) {
        return investigationItemRepository.findAll().stream()
                .filter(p -> p.getVisitId().equals(visitId)).toList();
    }

    private List<ProcurementItem> procurementFor(Long visitId) {
        return procurementItemRepository.findByClinicIdOrderByCreatedAtDesc(clinicId).stream()
                .filter(p -> visitId.equals(p.getSourceVisitId())).toList();
    }

    private Long createOtherClinic() {
        return clinicRepository.save(Clinic.builder()
                .code("visit-other-" + System.nanoTime()).name("Other").build()).getId();
    }

    // ── create ───────────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        void persists_core_fields_and_assigns_opd_encounter_number() throws IOException {
            LocalDate followUp = LocalDate.now().plusDays(7);
            Visit v = visitService.create(patientId, doctorId, clinicId, actingUserId, "Viral fever", "rest",
                    followUp, null, null, null, null, null);

            Visit loaded = visitRepository.findById(v.getId()).orElseThrow();
            assertThat(loaded.getClinicId()).isEqualTo(clinicId);
            assertThat(loaded.getPatientId()).isEqualTo(patientId);
            assertThat(loaded.getDoctorId()).isEqualTo(doctorId);
            assertThat(loaded.getDiagnosis()).isEqualTo("Viral fever");
            assertThat(loaded.getNotes()).isEqualTo("rest");
            assertThat(loaded.getFollowUpDate()).isEqualTo(followUp);
            assertThat(loaded.getVisitDate()).isNotNull();
            assertThat(loaded.getEncounterType()).isEqualTo("OPD");
            assertThat(loaded.getEncounterNo()).matches("OPD-" + Year.now().getValue() + "-\\d{5}");
            assertThat(loaded.getPrescriptionFile()).isNull();
            assertThat(loaded.getPrescriptionText()).isNull();
        }

        @Test
        void consecutive_visits_get_increasing_encounter_numbers() throws IOException {
            Visit a = create(null, null, null, null);
            Visit b = create(null, null, null, null);
            int na = Integer.parseInt(a.getEncounterNo().substring(a.getEncounterNo().lastIndexOf('-') + 1));
            int nb = Integer.parseInt(b.getEncounterNo().substring(b.getEncounterNo().lastIndexOf('-') + 1));
            assertThat(nb).isEqualTo(na + 1);
        }

        @Test
        void stores_uploaded_prescription_file() throws IOException {
            MockMultipartFile file = new MockMultipartFile("f", "rx.pdf", "application/pdf", new byte[]{1, 2});
            Visit v = visitService.create(patientId, doctorId, clinicId, actingUserId, null, null, null,
                    null, file, null, null, null);
            assertThat(v.getPrescriptionFile()).endsWith(".pdf");
        }
    }

    // ── prescription text derivation ─────────────────────────────────────

    @Nested
    class PrescriptionText {

        @Test
        void free_text_is_used_when_no_structured_lines() throws IOException {
            Visit v = create("Tab Dolo 650 SOS", null, null, null);
            assertThat(v.getPrescriptionText()).isEqualTo("Tab Dolo 650 SOS");
        }

        @Test
        void blank_free_text_is_stored_as_null() throws IOException {
            assertThat(create("   ", null, null, null).getPrescriptionText()).isNull();
        }

        @Test
        void free_text_is_used_when_structured_lines_are_empty_or_all_blank() throws IOException {
            assertThat(create("fallback", List.of(), null, null).getPrescriptionText())
                    .isEqualTo("fallback");
            assertThat(create("fallback", List.of(custom(" ", null, "BD", null, "x")), null, null)
                    .getPrescriptionText()).isEqualTo("fallback");
        }

        @Test
        void structured_lines_take_precedence_over_free_text() throws IOException {
            Visit v = create("IGNORED", List.of(custom("Zincovit", null, null, null, null)), null, null);
            assertThat(v.getPrescriptionText()).isEqualTo("Zincovit");
        }

        @Test
        void formats_each_line_with_middle_dot_separators_joined_by_newline() throws IOException {
            Visit v = create(null, List.of(
                    inventory(crocin.getId(), "1-0-1", "BD", "5 days", "after food"),
                    custom("Zincovit", "0-0-1", null, "10 days", null)), null, null);

            // inventory line uses the medicine's own name; blank parts are omitted entirely
            assertThat(v.getPrescriptionText()).isEqualTo(
                    "Crocin 500 · 1-0-1 · BD · 5 days · after food\n"
                            + "Zincovit · 0-0-1 · 10 days");
        }

        @Test
        void blank_optional_parts_do_not_leave_dangling_separators() throws IOException {
            Visit v = create(null, List.of(custom("ORS", "  ", " ", "3 days", "")), null, null);
            assertThat(v.getPrescriptionText()).isEqualTo("ORS · 3 days");
        }

        @Test
        void line_without_any_name_renders_dash_placeholder_but_is_not_persisted() throws IOException {
            // hasContent() is true (dosage), so the text gets a "—" row, but with no display name
            // the row is skipped when persisting PrescriptionItems.
            Visit v = create(null, List.of(custom(null, "1-0-1", null, null, null)), null, null);

            assertThat(v.getPrescriptionText()).isEqualTo("— · 1-0-1");
            assertThat(visitService.rxItemsFor(v.getId())).isEmpty();
            assertThat(procurementFor(v.getId())).isEmpty();
        }

        @Test
        void unknown_medicine_id_falls_back_to_custom_name() throws IOException {
            Visit v = create(null, List.of(new RxLine(987654L, "Imported Syrup", "5ml", null, null, null)),
                    null, null);
            assertThat(v.getPrescriptionText()).isEqualTo("Imported Syrup · 5ml");
        }
    }

    // ── structured Rx persistence + procurement ──────────────────────────

    @Nested
    class RxItemsAndProcurement {

        @Test
        void inventory_medicine_line_links_medicine_and_raises_no_procurement() throws IOException {
            Visit v = create(null, List.of(inventory(crocin.getId(), "1-0-1", "BD", "5 days", "after food")),
                    null, null);

            List<PrescriptionItem> items = visitService.rxItemsFor(v.getId());
            assertThat(items).hasSize(1);
            PrescriptionItem item = items.get(0);
            assertThat(item.getClinicId()).isEqualTo(clinicId);
            assertThat(item.getLineNo()).isEqualTo(1);
            assertThat(item.getMedicineId()).isEqualTo(crocin.getId());
            assertThat(item.getCustomName()).isNull();
            assertThat(item.getDosage()).isEqualTo("1-0-1");
            assertThat(item.getFrequency()).isEqualTo("BD");
            assertThat(item.getDuration()).isEqualTo("5 days");
            assertThat(item.getInstructions()).isEqualTo("after food");
            assertThat(item.getActive()).isTrue();

            assertThat(procurementFor(v.getId())).isEmpty();
        }

        @Test
        void platform_catalog_medicine_counts_as_inventory_and_raises_no_procurement() throws IOException {
            Medicine platform = medicineRepository.save(Medicine.builder()
                    .clinicId(null).name("Paracetamol (catalog)").build());

            Visit v = create(null, List.of(inventory(platform.getId(), "1-1-1", null, null, null)), null, null);

            assertThat(visitService.rxItemsFor(v.getId()))
                    .extracting(PrescriptionItem::getMedicineId).containsExactly(platform.getId());
            assertThat(procurementFor(v.getId())).isEmpty();
        }

        @Test
        void custom_medicine_is_queued_for_procurement_as_pending() throws IOException {
            Visit v = create(null, List.of(custom("Zincovit", "0-0-1", null, null, null)), null, null);

            List<PrescriptionItem> items = visitService.rxItemsFor(v.getId());
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getMedicineId()).isNull();
            assertThat(items.get(0).getCustomName()).isEqualTo("Zincovit");

            List<ProcurementItem> queued = procurementFor(v.getId());
            assertThat(queued).hasSize(1);
            ProcurementItem p = queued.get(0);
            assertThat(p.getClinicId()).isEqualTo(clinicId);
            assertThat(p.getMedicineName()).isEqualTo("Zincovit");
            assertThat(p.getMedicineId()).isNull();
            assertThat(p.getSource()).isEqualTo(ProcurementItem.Source.PRESCRIPTION);
            assertThat(p.getStatus()).isEqualTo(ProcurementItem.Status.PENDING);
            assertThat(p.getNotes()).isEqualTo("Prescribed in visit " + v.getEncounterNo() + ". Not in inventory.");
            // created_by is an app_user FK: the acting user, never the doctor-table id
            assertThat(p.getCreatedBy()).isEqualTo(actingUserId);
            assertThat(p.getCreatedBy()).isNotEqualTo(doctorId);
        }

        @Test
        void mixed_lines_get_sequential_line_numbers_skipping_blank_rows() throws IOException {
            Visit v = create(null, List.of(
                    inventory(crocin.getId(), "1-0-1", null, null, null),
                    custom(null, null, "BD", null, null),          // no content → skipped
                    custom("Zincovit", null, null, null, null),
                    custom("Becosules", null, null, null, null)), null, null);

            List<PrescriptionItem> items = visitService.rxItemsFor(v.getId());
            assertThat(items).extracting(PrescriptionItem::getLineNo).containsExactly(1, 2, 3);
            assertThat(items).extracting(PrescriptionItem::getCustomName)
                    .containsExactly(null, "Zincovit", "Becosules");
            assertThat(procurementFor(v.getId())).extracting(ProcurementItem::getMedicineName)
                    .containsExactlyInAnyOrder("Zincovit", "Becosules");
        }

        @Test
        void unknown_medicine_id_is_treated_as_custom_and_queued() throws IOException {
            Visit v = create(null, List.of(new RxLine(987654L, "Imported Syrup", null, null, null, null)),
                    null, null);

            assertThat(visitService.rxItemsFor(v.getId()))
                    .singleElement()
                    .satisfies(i -> {
                        assertThat(i.getMedicineId()).isNull();
                        assertThat(i.getCustomName()).isEqualTo("Imported Syrup");
                    });
            assertThat(procurementFor(v.getId())).extracting(ProcurementItem::getMedicineName)
                    .containsExactly("Imported Syrup");
        }

        @Test
        void cross_tenant_medicine_in_rx_is_rejected() {
            Long other = createOtherClinic();
            Medicine foreign = medicineRepository.save(Medicine.builder()
                    .clinicId(other).name("Foreign Drug").build());

            TenantContext.set(clinicId);
            assertThatThrownBy(() -> create(null, List.of(inventory(foreign.getId(), "1-0-1", null, null, null)),
                    null, null))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    // ── vitals ───────────────────────────────────────────────────────────

    @Nested
    class VitalsMapping {

        @Test
        void maps_every_vital_onto_the_visit() throws IOException {
            Vitals vitals = Vitals.builder()
                    .bpSystolic(120).bpDiastolic(80).pulse(72)
                    .temperatureF(new BigDecimal("98.6")).respiratoryRate(16).spo2(98)
                    .weightKg(new BigDecimal("70.0")).heightCm(new BigDecimal("170.0"))
                    .bloodGlucose(110).bloodGlucoseType("FASTING")
                    .build();

            Visit v = create(null, null, vitals, null);

            assertThat(v.getVitalBpSystolic()).isEqualTo(120);
            assertThat(v.getVitalBpDiastolic()).isEqualTo(80);
            assertThat(v.getVitalPulse()).isEqualTo(72);
            assertThat(v.getVitalTemperatureF()).isEqualByComparingTo("98.6");
            assertThat(v.getVitalRespiratoryRate()).isEqualTo(16);
            assertThat(v.getVitalSpo2()).isEqualTo(98);
            assertThat(v.getVitalWeightKg()).isEqualByComparingTo("70");
            assertThat(v.getVitalHeightCm()).isEqualByComparingTo("170");
            assertThat(v.getVitalBloodGlucose()).isEqualTo(110);
            assertThat(v.getVitalBloodGlucoseType()).isEqualTo("FASTING");
            // 70 / (1.70 * 1.70) = 24.22… → 24.2 (1 dp, HALF_UP)
            assertThat(v.getVitalBmi()).isEqualByComparingTo("24.2");
        }

        @Test
        void null_vitals_leave_all_vital_fields_null() throws IOException {
            Visit v = create(null, null, null, null);
            assertThat(v.getVitalBpSystolic()).isNull();
            assertThat(v.getVitalPulse()).isNull();
            assertThat(v.getVitalBloodGlucoseType()).isNull();
            assertThat(v.getVitalBmi()).isNull();
        }
    }

    // ── investigations ───────────────────────────────────────────────────

    @Nested
    class Investigations {

        @Test
        void persists_lines_with_sequential_numbers_and_pending_status_skipping_blank_names()
                throws IOException {
            Visit v = create(null, null, null, List.of(
                    new InvestigationLine("CBC", "  fasting  "),
                    new InvestigationLine("   ", "ignored"),
                    new InvestigationLine(null, "ignored"),
                    new InvestigationLine("  Lipid profile ", "   ")));

            List<InvestigationItem> items = visitService.investigationsFor(v.getId());
            assertThat(items).extracting(InvestigationItem::getLineNo).containsExactly(1, 2);
            assertThat(items).extracting(InvestigationItem::getName).containsExactly("CBC", "Lipid profile");
            // instructions are trimmed; whitespace-only becomes null
            assertThat(items).extracting(InvestigationItem::getInstructions).containsExactly("fasting", null);
            assertThat(items).allSatisfy(i -> {
                assertThat(i.getStatus()).isEqualTo(InvestigationItem.Status.PENDING);
                assertThat(i.getClinicId()).isEqualTo(clinicId);
                assertThat(i.getActive()).isTrue();
            });
        }

        @Test
        void new_investigation_names_are_auto_added_to_clinic_master_list() throws IOException {
            create(null, null, null, List.of(new InvestigationLine("Serum Ferritin", null)));

            assertThat(masterService.listAllInvestigations(clinicId))
                    .extracting(MedicalInvestigation::getName).contains("Serum Ferritin");
        }

        @Test
        void null_investigations_persist_nothing() throws IOException {
            Visit v = create(null, null, null, null);
            assertThat(visitService.investigationsFor(v.getId())).isEmpty();
        }
    }

    // ── update ───────────────────────────────────────────────────────────

    @Nested
    class Update {

        Visit visit;

        @BeforeEach
        void createVisitWithLines() throws IOException {
            MockMultipartFile file = new MockMultipartFile("f", "orig.pdf", "application/pdf", new byte[]{1});
            visit = visitService.create(patientId, doctorId, clinicId, actingUserId, "Dx1", "N1",
                    LocalDate.now().plusDays(3), null, file,
                    List.of(inventory(crocin.getId(), "1-0-1", null, "5 days", null),
                            custom("OldCustom", null, null, null, null)),
                    Vitals.builder().pulse(80).bpSystolic(130).build(),
                    List.of(new InvestigationLine("CBC", null), new InvestigationLine("ESR", null)));
        }

        private Visit update(Long newDoctorId, String text, MockMultipartFile file, List<RxLine> rx,
                             Vitals vitals, List<InvestigationLine> inv) throws IOException {
            return visitService.update(visit.getId(), actingUserId, newDoctorId, "Dx2", "N2",
                    LocalDate.now().plusDays(10), text, file, rx, vitals, inv);
        }

        @Test
        void overwrites_diagnosis_notes_and_follow_up() throws IOException {
            Visit u = update(null, null, null, null, null, null);
            assertThat(u.getDiagnosis()).isEqualTo("Dx2");
            assertThat(u.getNotes()).isEqualTo("N2");
            assertThat(u.getFollowUpDate()).isEqualTo(LocalDate.now().plusDays(10));
        }

        @Test
        void null_doctor_keeps_existing_doctor_and_non_null_replaces_it() throws IOException {
            assertThat(update(null, null, null, null, null, null).getDoctorId()).isEqualTo(doctorId);
            assertThat(update(4242L, null, null, null, null, null).getDoctorId()).isEqualTo(4242L);
        }

        @Test
        void null_vitals_keep_existing_vitals() throws IOException {
            Visit u = update(null, null, null, null, null, null);
            assertThat(u.getVitalPulse()).isEqualTo(80);
            assertThat(u.getVitalBpSystolic()).isEqualTo(130);
        }

        @Test
        void supplied_vitals_replace_all_fields_including_clearing_omitted_ones() throws IOException {
            Visit u = update(null, null, null, null, Vitals.builder().pulse(90).build(), null);
            assertThat(u.getVitalPulse()).isEqualTo(90);
            assertThat(u.getVitalBpSystolic()).isNull();
        }

        @Test
        void keeps_existing_file_when_no_new_file_or_empty_file_uploaded() throws IOException {
            String original = visit.getPrescriptionFile();
            assertThat(original).isNotNull();

            assertThat(update(null, null, null, null, null, null).getPrescriptionFile()).isEqualTo(original);
            MockMultipartFile empty = new MockMultipartFile("f", "new.pdf", "application/pdf", new byte[0]);
            assertThat(update(null, null, empty, null, null, null).getPrescriptionFile()).isEqualTo(original);
        }

        @Test
        void replaces_file_when_new_one_uploaded() throws IOException {
            String original = visit.getPrescriptionFile();
            MockMultipartFile png = new MockMultipartFile("f", "new.png", "image/png", new byte[]{9});
            Visit u = update(null, null, png, null, null, null);
            assertThat(u.getPrescriptionFile()).isNotEqualTo(original).endsWith(".png");
        }

        @Test
        void null_rx_lines_leave_existing_rx_items_and_text_untouched() throws IOException {
            String originalText = visit.getPrescriptionText();

            Visit u = update(null, "new free text", null, null, null, null);

            assertThat(u.getPrescriptionText()).isEqualTo(originalText);
            assertThat(visitService.rxItemsFor(visit.getId())).hasSize(2);
            assertThat(allRxRowsFor(visit.getId())).allMatch(PrescriptionItem::getActive);
        }

        @Test
        void supplied_rx_lines_soft_delete_old_rows_and_insert_new_ones() throws IOException {
            Visit u = update(null, null, null, List.of(custom("NewCustom", "1-1-1", null, null, null)), null, null);

            List<PrescriptionItem> active = visitService.rxItemsFor(visit.getId());
            assertThat(active).singleElement().satisfies(i -> {
                assertThat(i.getCustomName()).isEqualTo("NewCustom");
                assertThat(i.getLineNo()).isEqualTo(1);
            });

            // Old rows are still in the DB for audit, just inactive
            List<PrescriptionItem> all = allRxRowsFor(visit.getId());
            assertThat(all).hasSize(3);
            assertThat(all).filteredOn(i -> !i.getActive())
                    .extracting(PrescriptionItem::getCustomName)
                    .containsExactlyInAnyOrder(null, "OldCustom");

            assertThat(u.getPrescriptionText()).isEqualTo("NewCustom · 1-1-1");
        }

        @Test
        void empty_rx_list_clears_items_and_falls_back_to_free_text() throws IOException {
            Visit u = update(null, "Rx on paper", null, new ArrayList<>(), null, null);

            assertThat(visitService.rxItemsFor(visit.getId())).isEmpty();
            assertThat(allRxRowsFor(visit.getId())).hasSize(2).noneMatch(PrescriptionItem::getActive);
            assertThat(u.getPrescriptionText()).isEqualTo("Rx on paper");
        }

        @Test
        void earlier_procurement_is_not_retracted_and_new_custom_lines_are_queued_by_acting_user()
                throws IOException {
            assertThat(procurementFor(visit.getId())).extracting(ProcurementItem::getMedicineName)
                    .containsExactly("OldCustom");

            update(null, null, null, List.of(custom("NewCustom", null, null, null, null)), null, null);

            List<ProcurementItem> queued = procurementFor(visit.getId());
            assertThat(queued).extracting(ProcurementItem::getMedicineName)
                    .containsExactlyInAnyOrder("OldCustom", "NewCustom");
            // update() records the acting user, not the doctor, as requester (same as create)
            assertThat(queued).filteredOn(p -> p.getMedicineName().equals("NewCustom"))
                    .extracting(ProcurementItem::getCreatedBy).containsExactly(actingUserId);
        }

        @Test
        void null_investigations_leave_existing_ones_untouched() throws IOException {
            update(null, null, null, null, null, null);
            assertThat(visitService.investigationsFor(visit.getId()))
                    .extracting(InvestigationItem::getName).containsExactly("CBC", "ESR");
        }

        @Test
        void supplied_investigations_soft_delete_old_rows_and_insert_new_ones() throws IOException {
            update(null, null, null, null, null, List.of(new InvestigationLine("HbA1c", "fasting")));

            assertThat(visitService.investigationsFor(visit.getId()))
                    .singleElement().satisfies(i -> {
                        assertThat(i.getName()).isEqualTo("HbA1c");
                        assertThat(i.getLineNo()).isEqualTo(1);
                        assertThat(i.getStatus()).isEqualTo(InvestigationItem.Status.PENDING);
                    });
            List<InvestigationItem> all = allInvestigationRowsFor(visit.getId());
            assertThat(all).hasSize(3);
            assertThat(all).filteredOn(i -> !i.getActive())
                    .extracting(InvestigationItem::getName).containsExactlyInAnyOrder("CBC", "ESR");
        }

        @Test
        void unknown_visit_is_rejected() {
            assertThatThrownBy(() -> visitService.update(-1L, 1L, null, null, null, null, null, null,
                    null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Visit not found");
        }

        @Test
        void cross_tenant_update_is_rejected() {
            Long other = createOtherClinic();
            TenantContext.set(other);
            assertThatThrownBy(() -> update(null, null, null, null, null, null))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    // ── getOrThrow ───────────────────────────────────────────────────────

    @Nested
    class GetOrThrow {

        @Test
        void returns_own_clinic_visit() throws IOException {
            Visit v = create(null, null, null, null);
            TenantContext.set(clinicId);
            assertThat(visitService.getOrThrow(v.getId()).getId()).isEqualTo(v.getId());
        }

        @Test
        void unknown_id_throws_not_found() {
            assertThatThrownBy(() -> visitService.getOrThrow(-99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Visit not found: -99");
        }

        @Test
        void other_clinic_visit_is_rejected() throws IOException {
            Visit v = create(null, null, null, null);
            Long other = createOtherClinic();
            TenantContext.set(other);
            assertThatThrownBy(() -> visitService.getOrThrow(v.getId()))
                    .isInstanceOf(CrossTenantAccessException.class);
        }
    }

    // ── follow-ups ───────────────────────────────────────────────────────

    @Nested
    class FollowUps {

        LocalDate today = LocalDate.now();

        private Visit visitWithFollowUp(Long clinic, Long patient, LocalDate date) {
            return visitRepository.save(Visit.builder()
                    .clinicId(clinic).patientId(patient).encounterType("OPD")
                    .followUpDate(date).build());
        }

        @Test
        void followUpsFor_returns_only_that_day_for_that_clinic() {
            Long other = createOtherClinic();
            Patient p2 = patientRepository.save(Patient.builder()
                    .clinicId(other).name("Other").phone("9000000001").mrn("MRN-FU-" + System.nanoTime())
                    .build());

            Visit hit = visitWithFollowUp(clinicId, patientId, today);
            visitWithFollowUp(clinicId, patientId, today.plusDays(1));
            visitWithFollowUp(clinicId, patientId, null);
            visitWithFollowUp(other, p2.getId(), today);

            assertThat(visitService.followUpsFor(clinicId, today))
                    .extracting(Visit::getId).containsExactly(hit.getId());
        }

        @Test
        void followUpsBetween_is_inclusive_and_sorted_ascending() {
            Visit d3 = visitWithFollowUp(clinicId, patientId, today.plusDays(3));
            Visit d1 = visitWithFollowUp(clinicId, patientId, today.plusDays(1));
            Visit d5 = visitWithFollowUp(clinicId, patientId, today.plusDays(5));
            visitWithFollowUp(clinicId, patientId, today.plusDays(6));
            visitWithFollowUp(clinicId, patientId, today);

            assertThat(visitService.followUpsBetween(clinicId, today.plusDays(1), today.plusDays(5)))
                    .extracting(Visit::getId).containsExactly(d1.getId(), d3.getId(), d5.getId());
        }

        @Test
        void followUpsBetween_with_no_matches_is_empty() {
            visitWithFollowUp(clinicId, patientId, today);
            assertThat(visitService.followUpsBetween(clinicId, today.plusDays(10), today.plusDays(20)))
                    .isEmpty();
        }
    }
}
