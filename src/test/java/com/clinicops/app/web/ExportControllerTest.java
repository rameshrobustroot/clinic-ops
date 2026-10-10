package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.Supplier;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.TermsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Controller-layer tests for {@link ExportController} (owner-only CSV exports). */
@WebMvcTest(ExportController.class)
@Import(SecurityConfig.class)
class ExportControllerTest {

    private static final String BOM = "﻿";
    private static final String CRLF = "\r\n";

    @Autowired MockMvc mvc;

    @MockBean PatientRepository patientRepository;
    @MockBean SaleRepository saleRepository;
    @MockBean MedicineRepository medicineRepository;
    @MockBean SupplierRepository supplierRepository;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
    }

    private MvcResult download(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String fmt(Instant i) {
        return i.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    // ── /export/patients.csv ────────────────────────────────────────────

    @Nested
    class Patients {

        @Test
        @WithMockAppUser(role = "OWNER")
        void streams_bom_header_and_escaped_rows_with_attachment_filename() throws Exception {
            Instant created = Instant.parse("2026-03-04T05:06:00Z");
            Patient tricky = Patient.builder().id(1L).clinicId(1L).mrn("MRN-1")
                    .name("Kumar, \"Ravi\"").phone("9876543210").age(40).gender("MALE")
                    .address("12 Main St\nChennai").allergies(null).createdAt(created).build();
            when(patientRepository.findAll(eq(PageRequest.of(0, 1000, Sort.by("id")))))
                    .thenReturn(new PageImpl<>(List.of(tricky), PageRequest.of(0, 1000), 1));

            MvcResult r = download("/export/patients.csv");

            assertThat(r.getResponse().getHeader("Content-Disposition"))
                    .isEqualTo("attachment; filename=\"clinicops-patients-" + LocalDate.now() + ".csv\"");
            assertThat(body(r)).isEqualTo(BOM
                    + "MRN,Name,Phone,Age,Gender,Address,Allergies,Created" + CRLF
                    + "MRN-1,\"Kumar, \"\"Ravi\"\"\",9876543210,40,MALE,\"12 Main St\nChennai\",," + fmt(created) + CRLF);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void pages_through_all_rows_and_skips_other_tenants() throws Exception {
            Patient mine1 = Patient.builder().id(1L).clinicId(1L).mrn("M1").name("A").phone("9").build();
            Patient other = Patient.builder().id(2L).clinicId(2L).mrn("OTHER").name("Leak").phone("9").build();
            Patient mine2 = Patient.builder().id(3L).clinicId(1L).mrn("M3").name("C").phone("9").build();
            when(patientRepository.findAll(eq(PageRequest.of(0, 1000, Sort.by("id")))))
                    .thenReturn(new PageImpl<>(List.of(mine1, other), PageRequest.of(0, 1000), 1001));
            when(patientRepository.findAll(eq(PageRequest.of(1, 1000, Sort.by("id")))))
                    .thenReturn(new PageImpl<>(List.of(mine2), PageRequest.of(1, 1000), 1001));

            String csv = body(download("/export/patients.csv"));

            assertThat(csv).contains("M1,A,").contains("M3,C,").doesNotContain("OTHER").doesNotContain("Leak");
            assertThat(csv.split(CRLF)).hasSize(3);
        }
    }

    // ── /export/sales.csv ───────────────────────────────────────────────

    @Nested
    class Sales {

        @Test
        @WithMockAppUser(role = "OWNER")
        void renders_patient_walk_in_and_anonymous_rows() throws Exception {
            Instant at = Instant.parse("2026-01-02T03:04:00Z");
            Sale forPatient = Sale.builder().id(1L).clinicId(1L).billNo("B-1").patientId(5L)
                    .subtotalAmount(new BigDecimal("100.00")).billDiscountAmount(new BigDecimal("10.00"))
                    .totalAmount(new BigDecimal("90.00")).paymentMode("UPI").createdAt(at).build();
            Sale walkIn = Sale.builder().id(2L).clinicId(1L).billNo("B-2").walkInName("Raj, Jr")
                    .subtotalAmount(new BigDecimal("5")).totalAmount(new BigDecimal("5")).paymentMode("CASH").build();
            Sale anon = Sale.builder().id(3L).clinicId(1L).billNo("B-3").build();
            when(saleRepository.findByClinicIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(forPatient, walkIn, anon), PageRequest.of(0, 1000), 3));

            MvcResult r = download("/export/sales.csv");

            assertThat(r.getResponse().getHeader("Content-Disposition"))
                    .isEqualTo("attachment; filename=\"clinicops-sales-" + LocalDate.now() + ".csv\"");
            assertThat(body(r)).isEqualTo(BOM
                    + "Bill No,Date,Patient/Walk-in,Subtotal,Bill Discount,Total,Payment Mode" + CRLF
                    + "B-1," + fmt(at) + ",Patient#5,100.00,10.00,90.00,UPI" + CRLF
                    + "B-2,,\"Raj, Jr (walk-in)\",5,0,5,CASH" + CRLF
                    + "B-3,,Walk-in,,0,," + CRLF);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void queries_only_the_current_clinic() throws Exception {
            when(saleRepository.findByClinicIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));
            download("/export/sales.csv");
            org.mockito.Mockito.verify(saleRepository)
                    .findByClinicIdOrderByCreatedAtDesc(1L, PageRequest.of(0, 1000));
        }
    }

    // ── /export/inventory.csv + /export/suppliers.csv ───────────────────

    @Nested
    class InventoryAndSuppliers {

        @Test
        @WithMockAppUser(role = "OWNER")
        void inventory_rows_show_active_flag_and_threshold() throws Exception {
            Medicine m1 = Medicine.builder().id(1L).clinicId(1L).name("Crocin").genericName("Paracetamol")
                    .strength("500mg").manufacturer("GSK").form("TAB").unit("strip").lowStockThreshold(10)
                    .active(true).build();
            Medicine m2 = Medicine.builder().id(2L).clinicId(1L).name("Old \"syrup\"").active(false).build();
            when(medicineRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of(m1, m2));

            MvcResult r = download("/export/inventory.csv");

            assertThat(r.getResponse().getHeader("Content-Disposition"))
                    .isEqualTo("attachment; filename=\"clinicops-inventory-" + LocalDate.now() + ".csv\"");
            assertThat(body(r)).isEqualTo(BOM
                    + "Name,Generic,Strength,Manufacturer,Form,Unit,Low-stock threshold,Active" + CRLF
                    + "Crocin,Paracetamol,500mg,GSK,TAB,strip,10,yes" + CRLF
                    + "\"Old \"\"syrup\"\"\",,,,,,,no" + CRLF);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void supplier_rows_are_escaped() throws Exception {
            Supplier s = Supplier.builder().id(1L).clinicId(1L).name("MedTech").phone("9876543210")
                    .address("Plot 4, SIDCO").bankAccountNumber("1234").bankIfsc("HDFC0001234")
                    .upiId("med@upi").gstin("22AAAAA0000A1Z5").build();
            when(supplierRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of(s));

            MvcResult r = download("/export/suppliers.csv");

            assertThat(r.getResponse().getHeader("Content-Disposition"))
                    .isEqualTo("attachment; filename=\"clinicops-suppliers-" + LocalDate.now() + ".csv\"");
            assertThat(body(r)).isEqualTo(BOM
                    + "Name,Phone,Address,Bank Acc,IFSC,UPI,GSTIN" + CRLF
                    + "MedTech,9876543210,\"Plot 4, SIDCO\",1234,HDFC0001234,med@upi,22AAAAA0000A1Z5" + CRLF);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void empty_export_has_bom_and_header_only() throws Exception {
            when(supplierRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of());
            assertThat(body(download("/export/suppliers.csv")))
                    .isEqualTo(BOM + "Name,Phone,Address,Bank Acc,IFSC,UPI,GSTIN" + CRLF);
        }
    }

    // ── Access control ──────────────────────────────────────────────────

    @Nested
    class AccessControl {

        @ParameterizedTest
        @ValueSource(strings = {"/export/patients.csv", "/export/sales.csv", "/export/inventory.csv", "/export/suppliers.csv"})
        @WithMockAppUser(role = "DOCTOR")
        void non_owner_is_forbidden(String path) throws Exception {
            mvc.perform(get(path)).andExpect(status().isForbidden());
            verifyNoInteractions(patientRepository, saleRepository, medicineRepository, supplierRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"PHARMACIST", "RECEPTIONIST"})
        void other_clinic_roles_are_forbidden(String role) throws Exception {
            mvc.perform(get("/export/patients.csv").with(
                            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                    .authentication(auth(role))))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(patientRepository);
        }

        @Test
        void unauthenticated_is_redirected_to_login() throws Exception {
            mvc.perform(get("/export/patients.csv"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        private org.springframework.security.core.Authentication auth(String role) {
            var user = com.clinicops.app.domain.AppUser.builder().id(99L).clinicId(1L).username("u")
                    .fullName("U").role(com.clinicops.app.domain.Role.valueOf(role)).enabled(true)
                    .passwordHash("n/a").build();
            var principal = new com.clinicops.app.security.AppUserDetails(user);
            return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
        }
    }
}
