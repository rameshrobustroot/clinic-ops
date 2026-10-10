package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Doctor;
import com.clinicops.app.domain.InvestigationItem;
import com.clinicops.app.domain.MedicalInvestigation;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.PrescriptionItem;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.DoctorService;
import com.clinicops.app.service.FileStorageService;
import com.clinicops.app.service.MedicalMasterService;
import com.clinicops.app.service.PatientService;
import com.clinicops.app.service.PrescriptionPdfService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.service.VisitService;
import com.clinicops.app.tenant.CrossTenantAccessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

/** Controller-layer tests for {@link VisitController}. */
@WebMvcTest(VisitController.class)
@Import(SecurityConfig.class)
class VisitControllerTest {

    @Autowired MockMvc mvc;

    @MockBean VisitService visitService;
    @MockBean PatientService patientService;
    @MockBean FileStorageService fileStorage;
    @MockBean PrescriptionPdfService pdfService;
    @MockBean AppUserRepository userRepository;
    @MockBean MedicineRepository medicineRepository;
    @MockBean SaleRepository saleRepository;
    @MockBean DoctorService doctorService;
    @MockBean DoctorRepository doctorRepository;
    @MockBean MedicalMasterService masterService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    // Used by VisitController and by WebMvcConfig → TermsAcceptanceInterceptor
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private Patient patient;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(currentUser.userId()).thenReturn(99L);
        patient = Patient.builder().id(5L).clinicId(1L).mrn("MRN-5").name("Ravi Kumar")
                .phone("9876543210").build();
        doctor = Doctor.builder().id(10L).clinicId(1L).fullName("Asha").qualification("MBBS").build();
        when(patientService.getOrThrow(5L)).thenReturn(patient);
        when(doctorService.listActive(1L)).thenReturn(List.of(doctor));
        when(medicineRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of(
                Medicine.builder().id(3L).clinicId(1L).name("Paracetamol").strength("500mg").build()));
        when(masterService.listActiveInvestigations(1L)).thenReturn(List.of(
                MedicalInvestigation.builder().id(1L).name("CBC").build()));
    }

    private Visit existingVisit() {
        return Visit.builder().id(7L).clinicId(1L).patientId(5L).doctorId(10L).encounterNo("ENC-7")
                .visitDate(Instant.parse("2026-05-01T10:00:00Z"))
                .diagnosis("Old dx").notes("old notes").prescriptionText("old rx")
                .followUpDate(LocalDate.of(2026, 5, 10))
                .build();
    }

    // ── GET /visits/new ─────────────────────────────────────────────────

    @Nested
    class NewForm {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void renders_form_with_patient_doctors_and_rx_lookup_json() throws Exception {
            mvc.perform(get("/visits/new").param("patientId", "5"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("visit/form"))
                    .andExpect(model().attribute("patient", patient))
                    .andExpect(model().attribute("doctors", hasSize(1)))
                    .andExpect(model().attribute("selectedDoctorId", 10L))
                    .andExpect(model().attribute("canEditClinical", true))
                    .andExpect(model().attribute("today", LocalDate.now().toString()))
                    .andExpect(model().attribute("medicinesJson", containsString("Paracetamol · 500mg")))
                    .andExpect(model().attribute("masterInvestigationsJson", "[\"CBC\"]"))
                    .andExpect(content().string(containsString("Dr Asha · MBBS")))
                    .andExpect(content().string(containsString("Ravi Kumar")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void does_not_preselect_when_clinic_has_several_doctors() throws Exception {
            when(doctorService.listActive(1L)).thenReturn(List.of(doctor,
                    Doctor.builder().id(11L).clinicId(1L).fullName("Bala").build()));

            mvc.perform(get("/visits/new").param("patientId", "5"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeDoesNotExist("selectedDoctorId"));
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_gets_read_only_clinical_section() throws Exception {
            mvc.perform(get("/visits/new").param("patientId", "5"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("canEditClinical", false));
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_is_forbidden() throws Exception {
            mvc.perform(get("/visits/new").param("patientId", "5"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_patient_is_404() throws Exception {
            when(patientService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/visits/new").param("patientId", "77"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void missing_patientId_is_bad_request() throws Exception {
            mvc.perform(get("/visits/new")).andExpect(status().isBadRequest());
        }

        @Test
        void unauthenticated_is_redirected_to_login() throws Exception {
            mvc.perform(get("/visits/new").param("patientId", "5"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    // ── POST /visits ────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void valid_visit_is_created_and_redirects_to_detail() throws Exception {
            when(visitService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Visit.builder().id(70L).build());
            String followUp = LocalDate.now().plusDays(7).toString();

            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10")
                            .param("diagnosis", "Viral fever").param("notes", "3 days")
                            .param("followUpDate", followUp))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/visits/70"));

            verify(visitService).create(eq(5L), eq(10L), eq(1L), eq(99L), eq("Viral fever"), eq("3 days"),
                    eq(LocalDate.parse(followUp)), isNull(), isNull(), eq(List.of()), isNull(), eq(List.of()));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void rx_lines_vitals_and_investigations_are_parsed_and_passed_to_service() throws Exception {
            when(visitService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Visit.builder().id(71L).build());

            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10")
                            .param("rxMedicineId", "3", "")
                            .param("rxCustomName", "", "Ayur syrup")
                            .param("rxDosage", "1-0-1", "5ml")
                            .param("rxFrequency", "BD", "TDS")
                            .param("rxDuration", "5 days", "3 days")
                            .param("rxInstructions", "after food", "")
                            .param("vitalBpSystolic", "120").param("vitalBpDiastolic", "80")
                            .param("vitalTemperatureF", "99.5").param("vitalBloodGlucoseType", " ")
                            .param("investigationName", "CBC", "  ", "LFT")
                            .param("investigationInstructions", "fasting", "x", ""))
                    .andExpect(redirectedUrl("/visits/71"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VisitService.RxLine>> rx = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<VisitService.Vitals> vitals = ArgumentCaptor.forClass(VisitService.Vitals.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VisitService.InvestigationLine>> inv = ArgumentCaptor.forClass(List.class);
            verify(visitService).create(eq(5L), eq(10L), eq(1L), eq(99L), isNull(), isNull(), isNull(), isNull(),
                    isNull(), rx.capture(), vitals.capture(), inv.capture());

            assertThat(rx.getValue()).hasSize(2);
            assertThat(rx.getValue().get(0).getMedicineId()).isEqualTo(3L);
            assertThat(rx.getValue().get(0).getInstructions()).isEqualTo("after food");
            assertThat(rx.getValue().get(1).getMedicineId()).isNull();
            assertThat(rx.getValue().get(1).getCustomName()).isEqualTo("Ayur syrup");

            assertThat(vitals.getValue().bpSystolic).isEqualTo(120);
            assertThat(vitals.getValue().bpDiastolic).isEqualTo(80);
            assertThat(vitals.getValue().temperatureF).isEqualByComparingTo("99.5");
            assertThat(vitals.getValue().bloodGlucoseType).isNull();

            assertThat(inv.getValue()).extracting(VisitService.InvestigationLine::getName)
                    .containsExactly("CBC", "LFT");
            assertThat(inv.getValue().get(0).getInstructions()).isEqualTo("fasting");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void vitals_alone_are_enough_content() throws Exception {
            when(visitService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Visit.builder().id(72L).build());

            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10").param("vitalPulse", "72"))
                    .andExpect(redirectedUrl("/visits/72"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void uploaded_prescription_file_is_forwarded() throws Exception {
            when(visitService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Visit.builder().id(73L).build());
            MockMultipartFile file = new MockMultipartFile("prescriptionFile", "rx.pdf",
                    "application/pdf", new byte[] {1, 2, 3});

            mvc.perform(multipart("/visits").file(file).with(csrf())
                            .param("patientId", "5").param("doctorId", "10"))
                    .andExpect(redirectedUrl("/visits/73"));

            ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
            verify(visitService).create(eq(5L), eq(10L), eq(1L), eq(99L), isNull(), isNull(), isNull(), isNull(),
                    captor.capture(), any(), isNull(), any());
            assertThat(captor.getValue().getOriginalFilename()).isEqualTo("rx.pdf");
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void past_follow_up_date_rerenders_form_with_values_preserved() throws Exception {
            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10")
                            .param("diagnosis", "Migraine").param("notes", "photophobia")
                            .param("prescriptionText", "Rest")
                            .param("followUpDate", LocalDate.now().minusDays(1).toString()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("visit/form"))
                    .andExpect(model().attribute("error", "Follow-up date cannot be in the past."))
                    .andExpect(model().attribute("diagnosisVal", "Migraine"))
                    .andExpect(model().attribute("notesVal", "photophobia"))
                    .andExpect(model().attribute("prescriptionTextVal", "Rest"))
                    .andExpect(model().attribute("selectedDoctorId", 10L))
                    .andExpect(content().string(containsString("Follow-up date cannot be in the past.")))
                    .andExpect(content().string(containsString("value=\"Migraine\"")));
            verify(visitService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void malformed_follow_up_date_rerenders_form_instead_of_500() throws Exception {
            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10")
                            .param("diagnosis", "Migraine")
                            .param("followUpDate", "31/12/2030"))   // not ISO yyyy-MM-dd
                    .andExpect(status().isOk())
                    .andExpect(view().name("visit/form"))
                    .andExpect(model().attribute("error", "Follow-up date is not a valid date."))
                    .andExpect(model().attribute("diagnosisVal", "Migraine"));
            verify(visitService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void today_as_follow_up_date_is_allowed() throws Exception {
            when(visitService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Visit.builder().id(74L).build());
            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10")
                            .param("followUpDate", LocalDate.now().toString()))
                    .andExpect(redirectedUrl("/visits/74"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void missing_doctor_rerenders_form_with_error() throws Exception {
            mvc.perform(post("/visits").with(csrf()).param("patientId", "5").param("diagnosis", "Cold"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("visit/form"))
                    .andExpect(model().attribute("error", "Select the consulting doctor before saving."))
                    .andExpect(model().attribute("diagnosisVal", "Cold"));
            verify(visitService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_not_active_in_this_clinic_is_rejected() throws Exception {
            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "999").param("diagnosis", "Cold"))
                    .andExpect(view().name("visit/form"))
                    .andExpect(model().attribute("error", "Select the consulting doctor before saving."));
            verify(visitService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void empty_visit_is_rejected() throws Exception {
            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10")
                            .param("diagnosis", "  ").param("rxMedicineId", "").param("rxCustomName", "")
                            .param("investigationName", " "))
                    .andExpect(status().isOk())
                    .andExpect(view().name("visit/form"))
                    .andExpect(model().attribute("error",
                            "Enter at least one vital, diagnosis, prescription, investigation or note before saving."));
            verify(visitService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_create_visits() throws Exception {
            mvc.perform(post("/visits").with(csrf())
                            .param("patientId", "5").param("doctorId", "10").param("diagnosis", "x"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(visitService);
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/visits").param("patientId", "5").param("doctorId", "10").param("diagnosis", "x"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(visitService);
        }
    }

    // ── GET /visits/{id}/edit ───────────────────────────────────────────

    @Nested
    class EditForm {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void prefills_rx_and_investigation_json_from_existing_visit() throws Exception {
            when(visitService.getOrThrow(7L)).thenReturn(existingVisit());
            when(visitService.rxItemsFor(7L)).thenReturn(List.of(
                    PrescriptionItem.builder().id(1L).visitId(7L).medicineId(3L).dosage("1-0-1").build(),
                    PrescriptionItem.builder().id(2L).visitId(7L).customName("Herbal").build()));
            when(visitService.investigationsFor(7L)).thenReturn(List.of(
                    InvestigationItem.builder().id(1L).visitId(7L).name("CBC").build()));

            mvc.perform(get("/visits/7/edit"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("visit/form"))
                    .andExpect(model().attribute("visit", org.hamcrest.Matchers.hasProperty("id", org.hamcrest.Matchers.is(7L))))
                    .andExpect(model().attribute("selectedDoctorId", 10L))
                    .andExpect(model().attribute("initialLinesJson", containsString("\"medicineId\":\"3\"")))
                    .andExpect(model().attribute("initialLinesJson", containsString("\"customName\":\"Herbal\"")))
                    .andExpect(model().attribute("initialInvestigationsJson", containsString("\"name\":\"CBC\"")))
                    .andExpect(content().string(containsString("ENC-7")))
                    .andExpect(content().string(containsString("/visits/7")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_visit_is_404() throws Exception {
            when(visitService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/visits/77/edit")).andExpect(status().isNotFound());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_is_forbidden() throws Exception {
            mvc.perform(get("/visits/7/edit")).andExpect(status().isForbidden());
        }
    }

    // ── POST /visits/{id} ───────────────────────────────────────────────

    @Nested
    class Update {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_updates_clinical_fields_and_reassigns_doctor() throws Exception {
            Doctor other = Doctor.builder().id(11L).clinicId(1L).fullName("Bala").build();
            when(doctorService.listActive(1L)).thenReturn(List.of(doctor, other));

            mvc.perform(post("/visits/7").with(csrf())
                            .param("doctorId", "11").param("diagnosis", "New dx").param("notes", "n")
                            .param("rxMedicineId", "3").param("rxDosage", "1-0-1")
                            .param("investigationName", "CBC"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/visits/7"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VisitService.RxLine>> rx = ArgumentCaptor.forClass(List.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VisitService.InvestigationLine>> inv = ArgumentCaptor.forClass(List.class);
            verify(visitService).update(eq(7L), eq(99L), eq(11L), eq("New dx"), eq("n"), isNull(), isNull(),
                    isNull(), rx.capture(), isNull(), inv.capture());
            assertThat(rx.getValue()).hasSize(1);
            assertThat(inv.getValue()).hasSize(1);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_doctor_falls_back_to_existing_doctor() throws Exception {
            when(visitService.getOrThrow(7L)).thenReturn(existingVisit());

            mvc.perform(post("/visits/7").with(csrf()).param("doctorId", "999").param("diagnosis", "dx"))
                    .andExpect(redirectedUrl("/visits/7"));

            verify(visitService).update(eq(7L), eq(99L), eq(10L), eq("dx"), any(), any(), any(), any(),
                    any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_can_only_change_vitals_clinical_fields_are_preserved() throws Exception {
            Visit existing = existingVisit();
            when(visitService.getOrThrow(7L)).thenReturn(existing);

            mvc.perform(post("/visits/7").with(csrf())
                            .param("diagnosis", "Tampered").param("notes", "Tampered")
                            .param("prescriptionText", "Tampered")
                            .param("followUpDate", "2030-01-01")
                            .param("rxMedicineId", "3").param("investigationName", "CBC")
                            .param("vitalWeightKg", "70.5"))
                    .andExpect(redirectedUrl("/visits/7"));

            ArgumentCaptor<VisitService.Vitals> vitals = ArgumentCaptor.forClass(VisitService.Vitals.class);
            verify(visitService).update(eq(7L), eq(99L), eq(10L), eq("Old dx"), eq("old notes"),
                    eq(LocalDate.of(2026, 5, 10)), eq("old rx"), isNull(), isNull(), vitals.capture(), isNull());
            assertThat(vitals.getValue().weightKg).isEqualByComparingTo("70.5");
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_upload_is_discarded() throws Exception {
            when(visitService.getOrThrow(7L)).thenReturn(existingVisit());
            MockMultipartFile file = new MockMultipartFile("prescriptionFile", "x.pdf",
                    "application/pdf", new byte[] {1});

            mvc.perform(multipart("/visits/7").file(file).with(csrf()).param("vitalPulse", "80"))
                    .andExpect(redirectedUrl("/visits/7"));

            verify(visitService).update(eq(7L), eq(99L), eq(10L), any(), any(), any(), any(),
                    isNull(), isNull(), any(), isNull());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_update_is_404() throws Exception {
            when(visitService.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new CrossTenantAccessException());

            mvc.perform(post("/visits/77").with(csrf()).param("doctorId", "10").param("diagnosis", "x"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void malformed_follow_up_date_redirects_back_to_edit_with_error() throws Exception {
            mvc.perform(post("/visits/7").with(csrf())
                            .param("doctorId", "10").param("diagnosis", "dx")
                            .param("followUpDate", "tomorrow"))
                    .andExpect(redirectedUrl("/visits/7/edit"))
                    .andExpect(flash().attribute("error", "Follow-up date is not a valid date."));
            verify(visitService, never()).update(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_cannot_update() throws Exception {
            mvc.perform(post("/visits/7").with(csrf()).param("diagnosis", "x"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(visitService);
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void update_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/visits/7").param("diagnosis", "x"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(visitService);
        }
    }

    // ── GET /visits/{id} ────────────────────────────────────────────────

    @Nested
    class Detail {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void renders_visit_with_rx_names_doctor_and_existing_bill() throws Exception {
            Visit v = existingVisit();
            v.setVitalBpSystolic(120);
            v.setVitalBpDiastolic(80);
            when(visitService.getOrThrow(7L)).thenReturn(v);
            when(visitService.rxItemsFor(7L)).thenReturn(List.of(
                    PrescriptionItem.builder().id(1L).visitId(7L).medicineId(3L).dosage("1-0-1").build(),
                    PrescriptionItem.builder().id(2L).visitId(7L).customName("Herbal tea").build()));
            when(visitService.investigationsFor(7L)).thenReturn(List.of(
                    InvestigationItem.builder().id(1L).visitId(7L).name("CBC")
                            .status(InvestigationItem.Status.PENDING).build()));
            when(medicineRepository.findById(3L)).thenReturn(Optional.of(
                    Medicine.builder().id(3L).clinicId(1L).name("Paracetamol").build()));
            when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor));
            when(saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(7L)).thenReturn(Optional.of(
                    Sale.builder().id(30L).clinicId(1L).billNo("B-0030").build()));

            mvc.perform(get("/visits/7"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("visit/detail"))
                    .andExpect(model().attribute("medNames", hasEntry(3L, "Paracetamol")))
                    .andExpect(model().attribute("doctor", doctor))
                    .andExpect(model().attributeExists("existingBill", "investigations", "rxItems", "patient"))
                    .andExpect(content().string(containsString("Paracetamol")))
                    .andExpect(content().string(containsString("Herbal tea")))
                    .andExpect(content().string(containsString("B-0030")))
                    .andExpect(content().string(containsString("CBC")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void visit_without_doctor_or_bill_omits_those_attributes() throws Exception {
            Visit v = existingVisit();
            v.setDoctorId(null);
            when(visitService.getOrThrow(7L)).thenReturn(v);
            when(visitService.rxItemsFor(7L)).thenReturn(List.of());
            when(visitService.investigationsFor(7L)).thenReturn(List.of());
            when(saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(7L)).thenReturn(Optional.empty());

            mvc.perform(get("/visits/7"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeDoesNotExist("doctor", "existingBill"))
                    .andExpect(content().string(containsString("old rx")));
            verify(doctorRepository, never()).findById(any());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_sees_neither_bill_rx_nor_existing_bill_link() throws Exception {
            when(visitService.getOrThrow(7L)).thenReturn(existingVisit());
            when(visitService.rxItemsFor(7L)).thenReturn(List.of(
                    PrescriptionItem.builder().id(2L).visitId(7L).customName("Herbal tea").build()));
            when(visitService.investigationsFor(7L)).thenReturn(List.of());
            when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor));
            when(saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(7L)).thenReturn(Optional.empty());

            mvc.perform(get("/visits/7"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Herbal tea")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/sales/"))));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_visit_is_404() throws Exception {
            when(visitService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/visits/77"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }

    // ── GET /visits/{id}/file + /visits/{id}/prescription.pdf ───────────

    @Nested
    class FileDownloads {

        @TempDir Path tmp;

        @Test
        @WithMockAppUser(role = "OWNER")
        void visit_without_uploaded_file_returns_404() throws Exception {
            when(visitService.getOrThrow(7L)).thenReturn(existingVisit());
            mvc.perform(get("/visits/7/file")).andExpect(status().isNotFound());
            verifyNoInteractions(fileStorage);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void uploaded_file_is_streamed() throws Exception {
            Path f = Files.writeString(tmp.resolve("rx.txt"), "hello rx");
            Visit v = existingVisit();
            v.setPrescriptionFile("2026/rx.txt");
            when(visitService.getOrThrow(7L)).thenReturn(v);
            when(fileStorage.resolve("2026/rx.txt")).thenReturn(f);

            mvc.perform(get("/visits/7/file"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("hello rx"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_file_is_404() throws Exception {
            when(visitService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/visits/77/file")).andExpect(status().isNotFound());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void prescription_pdf_is_inline_with_sanitised_filename() throws Exception {
            Visit v = existingVisit();
            Clinic clinic = new Clinic();
            List<PrescriptionItem> rx = List.of(
                    PrescriptionItem.builder().id(1L).visitId(7L).medicineId(3L).build(),
                    PrescriptionItem.builder().id(2L).visitId(7L).medicineId(3L).build());
            Medicine para = Medicine.builder().id(3L).clinicId(1L).name("Paracetamol").build();
            when(visitService.getOrThrow(7L)).thenReturn(v);
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor));
            when(visitService.rxItemsFor(7L)).thenReturn(rx);
            when(visitService.investigationsFor(7L)).thenReturn(List.of());
            when(medicineRepository.findById(3L)).thenReturn(Optional.of(para));
            when(pdfService.generate(any(), any(), any(), any(), anyList(), anyMap(), anyList()))
                    .thenReturn(new byte[] {'%', 'P', 'D', 'F'});

            mvc.perform(get("/visits/7/prescription.pdf"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition",
                            "inline; filename=\"prescription-Ravi_Kumar-7.pdf\""))
                    .andExpect(content().bytes(new byte[] {'%', 'P', 'D', 'F'}));

            verify(pdfService).generate(same(clinic), same(patient), same(v), same(doctor), same(rx),
                    eq(Map.of(3L, para)), eq(List.of()));
            // de-duplicated lookup
            verify(medicineRepository, org.mockito.Mockito.times(1)).findById(3L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_pdf_is_404() throws Exception {
            when(visitService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/visits/77/prescription.pdf")).andExpect(status().isNotFound());
            verifyNoInteractions(pdfService);
        }

        @Test
        void unauthenticated_pdf_is_redirected_to_login() throws Exception {
            mvc.perform(get("/visits/7/prescription.pdf"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

}
