package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.PrescriptionItem;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.SaleItem;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.RxQuantityHeuristic;
import com.clinicops.app.service.SaleReceiptPdfService;
import com.clinicops.app.service.SaleService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.service.VisitService;
import com.clinicops.app.tenant.CrossTenantAccessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link SaleController}. */
@WebMvcTest(SaleController.class)
@Import(SecurityConfig.class)
class SaleControllerTest {

    @Autowired MockMvc mvc;

    @MockBean SaleService saleService;
    @MockBean VisitService visitService;
    @MockBean MedicineRepository medicineRepository;
    @MockBean PatientRepository patientRepository;
    @MockBean SaleReceiptPdfService receiptPdfService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private Patient patient;
    private Medicine para;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(currentUser.userId()).thenReturn(99L);
        patient = Patient.builder().id(5L).clinicId(1L).mrn("MRN-5").name("Ravi").phone("9876543210").build();
        para = Medicine.builder().id(3L).clinicId(1L).name("Paracetamol").strength("500mg").build();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(patient));
        when(medicineRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of(para));
        when(medicineRepository.findById(3L)).thenReturn(Optional.of(para));
    }

    private static Sale sale(long id, Long patientId) {
        return Sale.builder().id(id).clinicId(1L).billNo("B-" + id).patientId(patientId)
                .paymentMode("CASH").subtotalAmount(new BigDecimal("100.00"))
                .totalAmount(new BigDecimal("100.00")).createdAt(Instant.now()).build();
    }

    // ── GET /sales ──────────────────────────────────────────────────────

    @Nested
    class ListPage {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void lists_sales_with_resolved_patient_names_and_paging() throws Exception {
            Sale walkIn = sale(2, null);
            walkIn.setWalkInName("Walk Guy");
            when(saleService.salesPage(1L, 1)).thenReturn(new PageImpl<>(
                    List.of(sale(1, 5L), walkIn, sale(3, 5L)), PageRequest.of(1, 20), 50));

            mvc.perform(get("/sales").param("page", "1"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("sale/list"))
                    .andExpect(model().attribute("sales", hasSize(3)))
                    .andExpect(model().attribute("patientNames", hasEntry(5L, "Ravi")))
                    .andExpect(model().attribute("pageNo", 1))
                    .andExpect(model().attribute("totalPages", 3))
                    .andExpect(model().attribute("hasPrev", true))
                    .andExpect(model().attribute("hasNext", true))
                    .andExpect(content().string(containsString("B-1")))
                    .andExpect(content().string(containsString("Ravi")));
            // patient name lookup is de-duplicated
            verify(patientRepository, times(1)).findById(5L);
        }

        @Test
        void unauthenticated_is_redirected_to_login() throws Exception {
            mvc.perform(get("/sales"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    // ── GET /sales/new ──────────────────────────────────────────────────

    @Nested
    class NewSale {

        @Test
        @WithMockAppUser(role = "OWNER")
        void blank_form_defaults_to_cash_with_medicine_lookup() throws Exception {
            mvc.perform(get("/sales/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("sale/new"))
                    .andExpect(model().attribute("paymentMode", "CASH"))
                    .andExpect(model().attribute("walkInName", ""))
                    .andExpect(model().attribute("billDiscountType", ""))
                    .andExpect(model().attribute("medicinesJson", containsString("Paracetamol · 500mg")))
                    .andExpect(model().attribute("initialLinesJson", "[]"))
                    .andExpect(model().attributeDoesNotExist("patient", "fromVisit"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void patientId_param_preselects_patient() throws Exception {
            mvc.perform(get("/sales/new").param("patientId", "5"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("patient", patient))
                    .andExpect(content().string(containsString("data-patient-name=\"Ravi\"")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void garbage_patientId_is_ignored() throws Exception {
            mvc.perform(get("/sales/new").param("patientId", "abc"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeDoesNotExist("patient"));
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void from_visit_prefills_lines_from_rx_and_counts_skipped_custom_items() throws Exception {
            Visit visit = Visit.builder().id(7L).clinicId(1L).patientId(5L).encounterNo("ENC-7").build();
            when(visitService.getOrThrow(7L)).thenReturn(visit);
            when(visitService.rxItemsFor(7L)).thenReturn(List.of(
                    PrescriptionItem.builder().id(1L).medicineId(3L).dosage("1-0-1").duration("5 days").build(),
                    PrescriptionItem.builder().id(2L).customName("Herbal").build()));
            int expectedQty = RxQuantityHeuristic.compute("1-0-1", "5 days");

            mvc.perform(get("/sales/new").param("fromVisit", "7"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("sale/new"))
                    .andExpect(model().attribute("fromVisit", visit))
                    .andExpect(model().attribute("customSkipped", 1))
                    .andExpect(model().attribute("patient", patient))
                    .andExpect(model().attribute("initialLinesJson",
                            containsString("\"quantity\":\"" + expectedQty + "\"")))
                    .andExpect(model().attribute("initialLinesJson", containsString("\"medicineId\":\"3\"")))
                    .andExpect(content().string(containsString("ENC-7")))
                    .andExpect(content().string(containsString("name=\"visitId\" value=\"7\"")))
                    .andExpect(content().string(containsString("custom Rx item(s) skipped")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void from_visit_without_custom_items_has_no_skip_warning() throws Exception {
            when(visitService.getOrThrow(7L)).thenReturn(Visit.builder().id(7L).clinicId(1L).patientId(5L).build());
            when(visitService.rxItemsFor(7L)).thenReturn(List.of());

            mvc.perform(get("/sales/new").param("fromVisit", "7"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeDoesNotExist("customSkipped"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void from_cross_tenant_visit_is_404() throws Exception {
            when(visitService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/sales/new").param("fromVisit", "77"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }

    // ── POST /sales ─────────────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void records_sale_and_redirects_to_detail() throws Exception {
            when(saleService.record(any(), any(), any(), any(), any(), any(), any(), anyList(), any(), any()))
                    .thenReturn(sale(50, 5L));

            mvc.perform(post("/sales").with(csrf())
                            .param("patientId", "5").param("visitId", "7").param("paymentMode", "UPI")
                            .param("medicineId", "3", "", "4")
                            .param("quantity", "10", "5", "0")
                            .param("lineDiscountType", "PERCENT", "", "")
                            .param("lineDiscountValue", "10", "", "")
                            .param("billDiscountType", "AMOUNT").param("billDiscountValue", "15.50"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/sales/50"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SaleService.SaleLine>> lines = ArgumentCaptor.forClass(List.class);
            verify(saleService).record(eq(1L), eq(99L), eq(5L), eq(7L), eq("UPI"), isNull(), isNull(),
                    lines.capture(), eq("AMOUNT"), eq(new BigDecimal("15.50")));
            assertThat(lines.getValue()).hasSize(1);
            SaleService.SaleLine line = lines.getValue().get(0);
            assertThat(line.getMedicineId()).isEqualTo(3L);
            assertThat(line.getQuantity()).isEqualTo(10);
            assertThat(line.getDiscountType()).isEqualTo("PERCENT");
            assertThat(line.getDiscountValue()).isEqualByComparingTo("10");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void walk_in_sale_passes_walk_in_details_and_null_patient() throws Exception {
            when(saleService.record(any(), any(), any(), any(), any(), any(), any(), anyList(), any(), any()))
                    .thenReturn(sale(51, null));

            mvc.perform(post("/sales").with(csrf())
                            .param("patientId", "").param("paymentMode", "CASH")
                            .param("walkInName", "Walk Guy").param("walkInPhone", "9123456789")
                            .param("medicineId", "3").param("quantity", "2"))
                    .andExpect(redirectedUrl("/sales/51"));

            verify(saleService).record(eq(1L), eq(99L), isNull(), isNull(), eq("CASH"), eq("Walk Guy"),
                    eq("9123456789"), anyList(), isNull(), isNull());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void zero_or_unparseable_bill_discount_drops_discount_type() throws Exception {
            when(saleService.record(any(), any(), any(), any(), any(), any(), any(), anyList(), any(), any()))
                    .thenReturn(sale(52, 5L));

            for (String v : new String[] {"0", "abc", "-5", ""}) {
                mvc.perform(post("/sales").with(csrf())
                                .param("patientId", "5").param("medicineId", "3").param("quantity", "1")
                                .param("billDiscountType", "PERCENT").param("billDiscountValue", v))
                        .andExpect(redirectedUrl("/sales/52"));
            }
            verify(saleService, times(4)).record(eq(1L), eq(99L), eq(5L), isNull(), isNull(), isNull(), isNull(),
                    anyList(), isNull(), isNull());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void no_valid_lines_rerenders_form_preserving_input() throws Exception {
            mvc.perform(post("/sales").with(csrf())
                            .param("patientId", "5").param("paymentMode", "CARD")
                            .param("medicineId", "3").param("quantity", "0")
                            .param("billDiscountType", "PERCENT").param("billDiscountValue", "5"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("sale/new"))
                    .andExpect(model().attribute("error", "Add at least one medicine line."))
                    .andExpect(model().attribute("paymentMode", "CARD"))
                    .andExpect(model().attribute("billDiscountType", "PERCENT"))
                    .andExpect(model().attribute("billDiscountValue", "5"))
                    .andExpect(model().attribute("patient", patient))
                    .andExpect(model().attribute("initialLinesJson", containsString("\"quantity\":\"0\"")))
                    .andExpect(content().string(containsString("Add at least one medicine line.")));
            verify(saleService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void no_lines_at_all_rerenders_form() throws Exception {
            mvc.perform(post("/sales").with(csrf()).param("paymentMode", "CASH"))
                    .andExpect(view().name("sale/new"))
                    .andExpect(model().attribute("error", "Add at least one medicine line."))
                    .andExpect(model().attribute("initialLinesJson", "[]"));
            verify(saleService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void service_rejection_is_shown_on_form() throws Exception {
            when(saleService.record(any(), any(), any(), any(), any(), any(), any(), anyList(), any(), any()))
                    .thenThrow(new IllegalStateException("Insufficient stock for Paracetamol"));

            mvc.perform(post("/sales").with(csrf())
                            .param("patientId", "5").param("medicineId", "3").param("quantity", "999"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("sale/new"))
                    .andExpect(model().attribute("error", "Insufficient stock for Paracetamol"))
                    .andExpect(content().string(containsString("Insufficient stock for Paracetamol")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_walk_in_phone_rejected_by_service_is_shown_on_form() throws Exception {
            when(saleService.record(any(), any(), any(), any(), any(), any(), any(), anyList(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Walk-in phone must be a 10-digit mobile"));

            mvc.perform(post("/sales").with(csrf())
                            .param("walkInName", "W").param("walkInPhone", "123")
                            .param("medicineId", "3").param("quantity", "1"))
                    .andExpect(view().name("sale/new"))
                    .andExpect(model().attribute("walkInPhone", "123"))
                    .andExpect(model().attribute("error", "Walk-in phone must be a 10-digit mobile"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/sales").param("medicineId", "3").param("quantity", "1"))
                    .andExpect(status().isForbidden());
            verify(saleService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ── GET /sales/{id} + receipt ───────────────────────────────────────

    @Nested
    class Detail {

        @Test
        @WithMockAppUser(role = "OWNER")
        void renders_sale_with_items_and_patient() throws Exception {
            Sale s = sale(9, 5L);
            when(saleService.getOrThrow(9L)).thenReturn(s);
            when(saleService.itemsFor(9L)).thenReturn(List.of(
                    SaleItem.builder().id(1L).saleId(9L).medicineId(3L).quantity(2)
                            .unitPrice(new BigDecimal("5.00")).grossAmount(new BigDecimal("10.00"))
                            .discountAmount(BigDecimal.ZERO).lineTotal(new BigDecimal("10.00")).build(),
                    SaleItem.builder().id(2L).saleId(9L).medicineId(3L).quantity(1)
                            .unitPrice(new BigDecimal("5.00")).grossAmount(new BigDecimal("5.00"))
                            .discountAmount(BigDecimal.ZERO).lineTotal(new BigDecimal("5.00")).build()));

            mvc.perform(get("/sales/9"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("sale/detail"))
                    .andExpect(model().attribute("sale", s))
                    .andExpect(model().attribute("patient", patient))
                    .andExpect(model().attribute("medById", hasEntry(3L, para)))
                    .andExpect(content().string(containsString("B-9")))
                    .andExpect(content().string(containsString("Paracetamol")));
            verify(medicineRepository, times(1)).findById(3L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void walk_in_sale_has_null_patient() throws Exception {
            Sale s = sale(10, null);
            s.setWalkInName("Walk Guy");
            when(saleService.getOrThrow(10L)).thenReturn(s);
            when(saleService.itemsFor(10L)).thenReturn(List.of());

            mvc.perform(get("/sales/10"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("patient", nullValue()))
                    .andExpect(content().string(containsString("Walk Guy")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_sale_is_404() throws Exception {
            when(saleService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/sales/77"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void receipt_pdf_is_inline_with_bill_number_filename() throws Exception {
            Sale s = sale(9, 5L);
            Clinic clinic = new Clinic();
            List<SaleItem> items = List.of(SaleItem.builder().id(1L).saleId(9L).medicineId(3L).quantity(1).build());
            when(saleService.getOrThrow(9L)).thenReturn(s);
            when(saleService.itemsFor(9L)).thenReturn(items);
            when(clinicRepository.findById(1L)).thenReturn(Optional.of(clinic));
            when(receiptPdfService.generate(any(), any(), any(), anyList(), anyMap())).thenReturn(new byte[] {1, 2});

            mvc.perform(get("/sales/9/receipt.pdf"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition", "inline; filename=\"receipt-B-9.pdf\""))
                    .andExpect(content().bytes(new byte[] {1, 2}));

            verify(receiptPdfService).generate(same(clinic), same(s), same(patient), same(items),
                    eq(Map.of(3L, "Paracetamol 500mg")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_receipt_is_404() throws Exception {
            when(saleService.getOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/sales/77/receipt.pdf")).andExpect(status().isNotFound());
            verify(receiptPdfService, never()).generate(any(), any(), any(), any(), any());
        }
    }

    // ── Billing roles: OWNER / DOCTOR / PHARMACIST only ─────────────────

    @Nested
    class RoleAccess {

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                "/sales", "/sales/new", "/sales/1", "/sales/1/receipt.pdf"})
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_open_any_sales_page(String url) throws Exception {
            mvc.perform(get(url)).andExpect(status().isForbidden());
            org.mockito.Mockito.verifyNoInteractions(saleService, receiptPdfService);
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_record_a_sale() throws Exception {
            mvc.perform(post("/sales").with(csrf())
                            .param("paymentMode", "CASH").param("walkInName", "X")
                            .param("medicineId", "1").param("quantity", "1"))
                    .andExpect(status().isForbidden());
            org.mockito.Mockito.verifyNoInteractions(saleService);
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_can_open_sales_list() throws Exception {
            when(saleService.salesPage(1L, 0)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
            mvc.perform(get("/sales")).andExpect(status().isOk());
        }
    }
}
