package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Supplier;
import com.clinicops.app.domain.SupplierBill;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.SupplierBillService;
import com.clinicops.app.service.TermsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link PurchasesController} — the OWNER/PHARMACIST purchases hub. */
@WebMvcTest(PurchasesController.class)
@Import(SecurityConfig.class)
class PurchasesControllerTest {

    @Autowired MockMvc mvc;

    @MockBean SupplierBillService supplierBillService;
    @MockBean SupplierRepository supplierRepository;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(supplierBillService.outstandingTotal(1L)).thenReturn(new BigDecimal("1500.00"));
        when(supplierBillService.overdueCount(1L)).thenReturn(2L);
    }

    private static SupplierBill bill(long id, String status, long supplierId) {
        return SupplierBill.builder().id(id).clinicId(1L).supplierId(supplierId).billNumber("INV-" + id)
                .billDate(LocalDate.of(2026, 9, 1)).dueDate(LocalDate.of(2026, 9, 30))
                .totalAmount(new BigDecimal("500.00")).paidAmount(BigDecimal.ZERO).status(status).build();
    }

    private static Supplier supplier(long id, String name) {
        return Supplier.builder().id(id).clinicId(1L).name(name).creditBalance(BigDecimal.ZERO).build();
    }

    @Nested
    class Authorization {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_is_forbidden() throws Exception {
            mvc.perform(get("/purchases")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_is_forbidden() throws Exception {
            mvc.perform(get("/purchases")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_redirected_to_platform() throws Exception {
            mvc.perform(get("/purchases")).andExpect(redirectedUrl("/platform"));
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/purchases")).andExpect(redirectedUrlPattern("**/login"));
        }
    }

    @Nested
    class Hub {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void shows_aggregates_unpaid_bills_and_supplier_preview() throws Exception {
            when(supplierBillService.listAll(1L)).thenReturn(List.of(
                    bill(1L, "OVERDUE", 10L), bill(2L, "PAID", 10L), bill(3L, "UNPAID", 11L)));
            when(supplierRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of(
                    supplier(10L, "Alpha Pharma"), supplier(11L, "Beta Meds")));

            mvc.perform(get("/purchases"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("purchases/hub"))
                    .andExpect(model().attribute("outstandingTotal", new BigDecimal("1500.00")))
                    .andExpect(model().attribute("overdueCount", 2L))
                    .andExpect(model().attribute("recentUnpaidBills", hasSize(2)))
                    .andExpect(model().attribute("supplierNames", Map.of(10L, "Alpha Pharma", 11L, "Beta Meds")))
                    .andExpect(model().attribute("supplierCount", 2))
                    .andExpect(model().attribute("supplierPreview", hasSize(2)))
                    .andExpect(content().string(containsString("INV-3")))
                    .andExpect(content().string(containsString("Alpha Pharma")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void caps_unpaid_bills_and_supplier_preview_at_five() throws Exception {
            List<SupplierBill> bills = new ArrayList<>();
            IntStream.rangeClosed(1, 8).forEach(i -> bills.add(bill(i, "UNPAID", 1L)));
            List<Supplier> suppliers = new ArrayList<>();
            IntStream.rangeClosed(1, 7).forEach(i -> suppliers.add(supplier(i, "Supplier " + i)));
            when(supplierBillService.listAll(1L)).thenReturn(bills);
            when(supplierRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(suppliers);

            mvc.perform(get("/purchases"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("recentUnpaidBills", hasSize(5)))
                    .andExpect(model().attribute("supplierCount", 7))
                    .andExpect(model().attribute("supplierPreview", hasSize(5)));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void renders_empty_state() throws Exception {
            when(supplierBillService.outstandingTotal(1L)).thenReturn(BigDecimal.ZERO);
            when(supplierBillService.overdueCount(1L)).thenReturn(0L);

            mvc.perform(get("/purchases"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("recentUnpaidBills", hasSize(0)))
                    .andExpect(model().attribute("supplierCount", 0));
        }
    }
}
