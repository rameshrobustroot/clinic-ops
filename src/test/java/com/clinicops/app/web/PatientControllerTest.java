package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.PrescriptionItem;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.PrescriptionItemRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.repository.VisitRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.NotFoundException;
import com.clinicops.app.service.PatientService;
import com.clinicops.app.service.SaleService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.tenant.CrossTenantAccessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for {@link PatientController}, including the bean-validation
 * constraints on {@link com.clinicops.app.web.dto.PatientForm}.
 */
@WebMvcTest(PatientController.class)
@Import(SecurityConfig.class)
class PatientControllerTest {

    @Autowired MockMvc mvc;

    @MockBean PatientService patientService;
    @MockBean VisitRepository visitRepository;
    @MockBean SaleService saleService;
    @MockBean SaleRepository saleRepository;
    @MockBean PrescriptionItemRepository prescriptionItemRepository;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    // WebMvcConfig → TermsAcceptanceInterceptor → TermsService + ClinicRepository
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(currentUser.userId()).thenReturn(99L);
    }

    private static Patient patient(long id, String name) {
        return Patient.builder().id(id).clinicId(1L).mrn("MRN-" + id).name(name)
                .phone("9876543210").age(30).gender("MALE").build();
    }

    private static PageImpl<Patient> pageOf(List<Patient> content, int pageNo, long total) {
        return new PageImpl<>(content, PageRequest.of(pageNo, 20), total);
    }

    // ── GET /patients ───────────────────────────────────────────────────

    @Nested
    class ListPage {

        @Test
        @WithMockAppUser(role = "OWNER")
        void renders_list_with_paging_model() throws Exception {
            when(patientService.search(1L, "ram", 0))
                    .thenReturn(pageOf(List.of(patient(1, "Ramesh")), 0, 45));

            mvc.perform(get("/patients").param("q", "ram"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("patient/list"))
                    .andExpect(model().attribute("patients", hasSize(1)))
                    .andExpect(model().attribute("q", "ram"))
                    .andExpect(model().attribute("pageNo", 0))
                    .andExpect(model().attribute("totalPages", 3))
                    .andExpect(model().attribute("totalElements", 45L))
                    .andExpect(model().attribute("hasPrev", false))
                    .andExpect(model().attribute("hasNext", true))
                    .andExpect(content().string(containsString("Ramesh")))
                    .andExpect(content().string(containsString("MRN-1")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void null_query_becomes_empty_string_and_negative_page_is_clamped_to_zero() throws Exception {
            when(patientService.search(1L, null, 0)).thenReturn(pageOf(List.of(), 0, 0));

            mvc.perform(get("/patients").param("page", "-5"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("q", ""));

            verify(patientService).search(1L, null, 0);
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void any_clinic_role_can_view_list() throws Exception {
            when(patientService.search(anyLong(), any(), any(Integer.class))).thenReturn(pageOf(List.of(), 0, 0));
            mvc.perform(get("/patients")).andExpect(status().isOk());
        }

        @Test
        void unauthenticated_is_redirected_to_login() throws Exception {
            mvc.perform(get("/patients"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_confined_to_platform_area() throws Exception {
            mvc.perform(get("/patients"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/platform"));
            verify(patientService, never()).search(any(), any(), any(Integer.class));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void owner_with_outdated_terms_is_sent_to_accept_page() throws Exception {
            Clinic clinic = new Clinic();
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(termsService.requiresAcceptance(clinic)).thenReturn(true);

            mvc.perform(get("/patients"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/terms/accept"));
        }
    }

    // ── GET /patients/search (HTMX) and /patients/search.json ───────────

    @Nested
    class Search {

        @Test
        @WithMockAppUser(role = "OWNER")
        void htmx_fragment_renders_results_only() throws Exception {
            when(patientService.search(1L, "ra", 0)).thenReturn(pageOf(List.of(patient(1, "Ramesh")), 0, 1));

            mvc.perform(get("/patients/search").param("q", "ra"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("patient/list :: results"))
                    .andExpect(content().string(containsString("Ramesh")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void json_search_returns_at_most_ten_and_blanks_nulls() throws Exception {
            List<Patient> many = new ArrayList<>();
            for (int i = 1; i <= 12; i++) many.add(patient(i, "P" + i));
            many.get(0).setPhone(null);
            many.get(0).setAge(null);
            many.get(0).setGender(null);
            when(patientService.search(1L, "p", 0)).thenReturn(pageOf(many, 0, 12));

            mvc.perform(get("/patients/search.json").param("q", "p"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(10)))
                    .andExpect(jsonPath("$[0].id", is(1)))
                    .andExpect(jsonPath("$[0].mrn", is("MRN-1")))
                    .andExpect(jsonPath("$[0].name", is("P1")))
                    .andExpect(jsonPath("$[0].phone", is("")))
                    .andExpect(jsonPath("$[0].age", is("")))
                    .andExpect(jsonPath("$[0].gender", is("")))
                    .andExpect(jsonPath("$[1].age", is(30)));
        }
    }

    // ── GET /patients/new + POST /patients ──────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "OWNER")
        void new_form_renders_empty_form_and_carries_returnTo() throws Exception {
            mvc.perform(get("/patients/new").param("returnTo", "/sales/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("patient/form"))
                    .andExpect(model().attributeExists("form"))
                    .andExpect(model().attribute("returnTo", "/sales/new"))
                    .andExpect(content().string(containsString("name=\"returnTo\"")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void valid_post_creates_patient_and_redirects_to_list_with_flash() throws Exception {
            Patient saved = patient(42, "Anita");
            when(patientService.create(eq(1L), any(Patient.class))).thenReturn(saved);

            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita")
                            .param("phone", "9876543210")
                            .param("age", "34")
                            .param("gender", "FEMALE")
                            .param("allergies", "Penicillin")
                            .param("address", "Chennai")
                            .param("notes", "VIP"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/patients"))
                    .andExpect(flash().attribute("flash", "Registered Anita (MRN-42)"));

            ArgumentCaptor<Patient> captor = ArgumentCaptor.forClass(Patient.class);
            verify(patientService).create(eq(1L), captor.capture());
            Patient p = captor.getValue();
            assertThat(p.getName()).isEqualTo("Anita");
            assertThat(p.getPhone()).isEqualTo("9876543210");
            assertThat(p.getAge()).isEqualTo(34);
            assertThat(p.getGender()).isEqualTo("FEMALE");
            assertThat(p.getAllergies()).isEqualTo("Penicillin");
            assertThat(p.getAddress()).isEqualTo("Chennai");
            assertThat(p.getNotes()).isEqualTo("VIP");
            assertThat(p.getId()).isNull();
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void relative_returnTo_redirects_back_with_patientId() throws Exception {
            when(patientService.create(eq(1L), any(Patient.class))).thenReturn(patient(42, "Anita"));

            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210")
                            .param("returnTo", "/sales/new"))
                    .andExpect(redirectedUrl("/sales/new?patientId=42"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void returnTo_with_existing_query_appends_with_ampersand() throws Exception {
            when(patientService.create(eq(1L), any(Patient.class))).thenReturn(patient(42, "Anita"));

            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210")
                            .param("returnTo", "/appointments/new?date=2026-06-01"))
                    .andExpect(redirectedUrl("/appointments/new?date=2026-06-01&patientId=42"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void protocol_relative_returnTo_is_ignored_to_prevent_open_redirect() throws Exception {
            when(patientService.create(eq(1L), any(Patient.class))).thenReturn(patient(42, "Anita"));

            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210")
                            .param("returnTo", "//evil.example.com/x"))
                    .andExpect(redirectedUrl("/patients"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void backslash_returnTo_is_ignored_to_prevent_open_redirect() throws Exception {
            // Browsers resolve "/\evil.com" as "//evil.com".
            when(patientService.create(eq(1L), any(Patient.class))).thenReturn(patient(42, "Anita"));

            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210")
                            .param("returnTo", "/\\evil.example.com/x"))
                    .andExpect(redirectedUrl("/patients"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void absolute_returnTo_is_ignored_to_prevent_open_redirect() throws Exception {
            when(patientService.create(eq(1L), any(Patient.class))).thenReturn(patient(42, "Anita"));

            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210")
                            .param("returnTo", "https://evil.example.com/"))
                    .andExpect(redirectedUrl("/patients"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/patients").param("name", "Anita").param("phone", "9876543210"))
                    .andExpect(status().isForbidden());
            verify(patientService, never()).create(any(), any());
        }

        @Test
        void unauthenticated_post_is_redirected_to_login() throws Exception {
            mvc.perform(post("/patients").with(csrf()).param("name", "A").param("phone", "9876543210"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
            verify(patientService, never()).create(any(), any());
        }
    }

    // ── PatientForm bean validation ─────────────────────────────────────

    @Nested
    class Validation {

        @Test
        @WithMockAppUser(role = "OWNER")
        void blank_name_rerenders_form_with_error_and_does_not_save() throws Exception {
            mvc.perform(post("/patients").with(csrf())
                            .param("name", "  ").param("phone", "9876543210")
                            .param("returnTo", "/sales/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("patient/form"))
                    .andExpect(model().attributeHasFieldErrorCode("form", "name", "NotBlank"))
                    .andExpect(model().attribute("returnTo", "/sales/new"))
                    .andExpect(content().string(containsString("Name is required")));
            verify(patientService, never()).create(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void missing_phone_is_rejected() throws Exception {
            mvc.perform(post("/patients").with(csrf()).param("name", "Anita"))
                    .andExpect(view().name("patient/form"))
                    .andExpect(model().attributeHasFieldErrorCode("form", "phone", "NotBlank"));
            verify(patientService, never()).create(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void phone_not_matching_indian_mobile_pattern_is_rejected() throws Exception {
            for (String bad : new String[] {"12345", "5876543210", "98765432101", "98765abcde"}) {
                mvc.perform(post("/patients").with(csrf()).param("name", "Anita").param("phone", bad))
                        .andExpect(view().name("patient/form"))
                        .andExpect(model().attributeHasFieldErrorCode("form", "phone", "Pattern"))
                        .andExpect(content().string(containsString("Enter a 10-digit mobile starting with 6/7/8/9")));
            }
            verify(patientService, never()).create(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void negative_age_is_rejected() throws Exception {
            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210").param("age", "-1"))
                    .andExpect(view().name("patient/form"))
                    .andExpect(model().attributeHasFieldErrorCode("form", "age", "Min"))
                    .andExpect(content().string(containsString("Age cannot be negative")));
            verify(patientService, never()).create(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void age_over_130_is_rejected() throws Exception {
            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210").param("age", "131"))
                    .andExpect(model().attributeHasFieldErrorCode("form", "age", "Max"))
                    .andExpect(content().string(containsString("Age cannot exceed 130")));
            verify(patientService, never()).create(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void non_numeric_age_is_a_type_mismatch_error() throws Exception {
            mvc.perform(post("/patients").with(csrf())
                            .param("name", "Anita").param("phone", "9876543210").param("age", "abc"))
                    .andExpect(view().name("patient/form"))
                    .andExpect(model().attributeHasFieldErrorCode("form", "age", "typeMismatch"));
            verify(patientService, never()).create(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void name_longer_than_128_is_rejected() throws Exception {
            mvc.perform(post("/patients").with(csrf())
                            .param("name", "x".repeat(129)).param("phone", "9876543210"))
                    .andExpect(model().attributeHasFieldErrorCode("form", "name", "Size"));
            verify(patientService, never()).create(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void boundary_ages_zero_and_130_are_accepted() throws Exception {
            when(patientService.create(eq(1L), any(Patient.class))).thenReturn(patient(1, "A"));
            for (String age : new String[] {"0", "130"}) {
                mvc.perform(post("/patients").with(csrf())
                                .param("name", "A").param("phone", "6000000000").param("age", age))
                        .andExpect(redirectedUrl("/patients"));
            }
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_update_rerenders_edit_form_with_patientId_and_does_not_save() throws Exception {
            mvc.perform(post("/patients/7").with(csrf()).param("name", "").param("phone", "9876543210"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("patient/form"))
                    .andExpect(model().attribute("patientId", 7L))
                    .andExpect(model().attributeHasFieldErrors("form", "name"))
                    .andExpect(content().string(containsString("/patients/7")));
            verify(patientService, never()).update(any(), any());
        }
    }

    // ── GET /patients/{id} ──────────────────────────────────────────────

    @Nested
    class Detail {

        @Test
        @WithMockAppUser(role = "OWNER")
        void renders_patient_with_visits_sales_and_bill_links() throws Exception {
            Patient p = patient(5, "Kumar");
            p.setAllergies("Sulfa");
            Visit billed = Visit.builder().id(10L).clinicId(1L).patientId(5L).encounterNo("ENC-10")
                    .visitDate(Instant.parse("2026-05-01T10:00:00Z")).diagnosis("Cold").build();
            Visit withRx = Visit.builder().id(11L).clinicId(1L).patientId(5L).encounterNo("ENC-11")
                    .visitDate(Instant.parse("2026-05-02T10:00:00Z")).diagnosis("Fever").build();
            Sale sale = Sale.builder().id(3L).clinicId(1L).billNo("B-0003").paymentMode("CASH")
                    .totalAmount(new BigDecimal("120.00")).createdAt(Instant.now()).build();

            when(patientService.getOrThrow(5L)).thenReturn(p);
            when(visitRepository.findByPatientIdOrderByVisitDateDesc(5L)).thenReturn(List.of(billed, withRx));
            when(saleService.salesForPatient(5L)).thenReturn(List.of(sale));
            when(saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(10L)).thenReturn(Optional.of(sale));
            when(saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(11L)).thenReturn(Optional.empty());
            when(prescriptionItemRepository.findByVisitIdOrderByLineNoAsc(10L)).thenReturn(List.of());
            when(prescriptionItemRepository.findByVisitIdOrderByLineNoAsc(11L))
                    .thenReturn(List.of(PrescriptionItem.builder().id(1L).visitId(11L).build()));

            mvc.perform(get("/patients/5"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("patient/detail"))
                    .andExpect(model().attribute("patient", p))
                    .andExpect(model().attribute("visits", hasSize(2)))
                    .andExpect(model().attribute("sales", hasSize(1)))
                    .andExpect(model().attribute("visitBillNo", hasEntry(10L, "B-0003")))
                    .andExpect(model().attribute("visitHasRx", hasEntry(11L, true)))
                    .andExpect(model().attribute("visitHasRx", hasEntry(10L, false)))
                    .andExpect(content().string(containsString("Sulfa")))
                    .andExpect(content().string(containsString("ENC-11")))
                    .andExpect(content().string(containsString("/sales/new?fromVisit=11")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/sales/new?fromVisit=10"))));
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_sees_no_billing_actions_or_sales_history() throws Exception {
            Patient p = patient(5, "Kumar");
            Visit withRx = Visit.builder().id(11L).clinicId(1L).patientId(5L).encounterNo("ENC-11")
                    .visitDate(Instant.parse("2026-05-02T10:00:00Z")).diagnosis("Fever").build();
            Sale sale = Sale.builder().id(3L).clinicId(1L).billNo("B-0003").paymentMode("CASH")
                    .totalAmount(new BigDecimal("120.00")).createdAt(Instant.now()).build();
            when(patientService.getOrThrow(5L)).thenReturn(p);
            when(visitRepository.findByPatientIdOrderByVisitDateDesc(5L)).thenReturn(List.of(withRx));
            when(saleService.salesForPatient(5L)).thenReturn(List.of(sale));
            when(saleRepository.findFirstByVisitIdOrderByCreatedAtDesc(11L)).thenReturn(Optional.empty());
            when(prescriptionItemRepository.findByVisitIdOrderByLineNoAsc(11L))
                    .thenReturn(List.of(PrescriptionItem.builder().id(1L).visitId(11L).build()));

            mvc.perform(get("/patients/5"))
                    .andExpect(status().isOk())
                    // receptionists still register visits…
                    .andExpect(content().string(containsString("/visits/new?patientId=5")))
                    // …but never see sale buttons or bills (SaleController would 403 them)
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/sales/"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Sales history"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("B-0003"))));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_patient_is_reported_as_404() throws Exception {
            when(patientService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());

            mvc.perform(get("/patients/77"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void unknown_patient_id_is_404() throws Exception {
            when(patientService.getOrThrow(404L)).thenThrow(new NotFoundException("Patient not found: 404"));

            mvc.perform(get("/patients/404"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }

    // ── GET /patients/{id}/edit + POST /patients/{id} ───────────────────

    @Nested
    class Update {

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_is_prefilled_from_patient() throws Exception {
            when(patientService.getOrThrow(5L)).thenReturn(patient(5, "Kumar"));

            mvc.perform(get("/patients/5/edit"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("patient/form"))
                    .andExpect(model().attribute("patientId", 5L))
                    .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("name", is("Kumar"))))
                    .andExpect(content().string(containsString("value=\"Kumar\"")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_for_cross_tenant_patient_is_404() throws Exception {
            when(patientService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/patients/77/edit")).andExpect(status().isNotFound());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void valid_update_calls_service_and_redirects_to_detail() throws Exception {
            when(patientService.update(eq(5L), any(Patient.class))).thenReturn(patient(5, "Kumar R"));

            mvc.perform(post("/patients/5").with(csrf())
                            .param("name", "Kumar R").param("phone", "8123456789").param("age", "41"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/patients/5"))
                    .andExpect(flash().attribute("flash", "Patient updated."));

            ArgumentCaptor<Patient> captor = ArgumentCaptor.forClass(Patient.class);
            verify(patientService).update(eq(5L), captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("Kumar R");
            assertThat(captor.getValue().getPhone()).isEqualTo("8123456789");
            assertThat(captor.getValue().getAge()).isEqualTo(41);
            assertThat(captor.getValue().getAllergies()).isNull();
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_of_cross_tenant_patient_is_404() throws Exception {
            when(patientService.update(eq(77L), any(Patient.class))).thenThrow(new CrossTenantAccessException());

            mvc.perform(post("/patients/77").with(csrf()).param("name", "X").param("phone", "9876543210"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/patients/5").param("name", "X").param("phone", "9876543210"))
                    .andExpect(status().isForbidden());
            verify(patientService, never()).update(any(), any());
        }
    }

}
