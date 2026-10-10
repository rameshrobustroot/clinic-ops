package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
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
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for {@link SupplierBillController}.
 *
 * <p>Verifies the new-supplier round-trip (newSupplierId param), the confirmation banner,
 * and the + New Supplier shortcut link that was added to the bill form.
 */
@WebMvcTest(SupplierBillController.class)
@Import(SecurityConfig.class)
class SupplierBillControllerTest {

    @Autowired MockMvc mvc;

    @MockBean SupplierBillService supplierBillService;
    @MockBean SupplierRepository supplierRepository;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    // WebMvcConfig → TermsAcceptanceInterceptor → TermsService + ClinicRepository
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(supplierRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of());
    }

    // ── GET /supplier-bills/new ──────────────────────────────────────────

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_returns_200_with_form_and_suppliers() throws Exception {
        mvc.perform(get("/supplier-bills/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("supplier-bill/form"))
                .andExpect(model().attributeExists("form", "suppliers"));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_pre_selects_supplier_from_supplierId_param() throws Exception {
        mvc.perform(get("/supplier-bills/new").param("supplierId", "5"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form",
                        hasProperty("supplierId", Matchers.equalTo(5L))));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_with_newSupplierId_shows_confirmation_banner() throws Exception {
        Supplier supplier = Supplier.builder()
                .id(7L).clinicId(1L).name("MedTech Pharma").creditBalance(BigDecimal.ZERO)
                .build();
        when(supplierRepository.findById(7L)).thenReturn(Optional.of(supplier));

        mvc.perform(get("/supplier-bills/new").param("newSupplierId", "7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("newSupplierName", "MedTech Pharma"));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_with_newSupplierId_overrides_supplierId_param() throws Exception {
        // newSupplierId (7) takes priority over supplierId (5)
        Supplier supplier = Supplier.builder()
                .id(7L).clinicId(1L).name("Preferred Pharma").creditBalance(BigDecimal.ZERO)
                .build();
        when(supplierRepository.findById(7L)).thenReturn(Optional.of(supplier));

        mvc.perform(get("/supplier-bills/new")
                        .param("supplierId", "5")
                        .param("newSupplierId", "7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("form",
                        hasProperty("supplierId", Matchers.equalTo(7L))));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_without_newSupplierId_has_no_banner() throws Exception {
        mvc.perform(get("/supplier-bills/new"))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("newSupplierName"));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_contains_new_supplier_shortcut_link() throws Exception {
        // The "+ New Supplier" link must reference /suppliers/new with returnTo param
        mvc.perform(get("/supplier-bills/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/suppliers/new")));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void new_form_with_backSupplierId_exposes_it_to_template() throws Exception {
        mvc.perform(get("/supplier-bills/new").param("supplierId", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("backSupplierId", 3L));
    }

    @Test
    void unauthenticated_user_is_redirected_to_login() throws Exception {
        mvc.perform(get("/supplier-bills/new"))
                .andExpect(status().is3xxRedirection());
    }

    // ── GET /supplier-bills/{id} ─────────────────────────────────────────

    @Test
    @WithMockAppUser(role = "OWNER")
    void detail_view_returns_200() throws Exception {
        SupplierBill bill = SupplierBill.builder()
                .id(1L).clinicId(1L).billNumber("INV-001")
                .billDate(LocalDate.now()).totalAmount(new BigDecimal("5000"))
                .paidAmount(BigDecimal.ZERO).status("UNPAID")
                .build();
        when(supplierBillService.findById(1L, 1L)).thenReturn(bill);
        when(supplierBillService.listPayments(1L)).thenReturn(List.of());

        mvc.perform(get("/supplier-bills/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("supplier-bill/view"))
                .andExpect(model().attributeExists("bill", "payments", "paymentForm"));
    }
}
