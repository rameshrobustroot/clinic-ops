package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.Visit;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.AlertsService;
import com.clinicops.app.service.DashboardService;
import com.clinicops.app.service.ProcurementService;
import com.clinicops.app.service.SupplierBillService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.service.VisitService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link DashboardController}. */
@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
class DashboardControllerTest {

    @Autowired MockMvc mvc;

    @MockBean DashboardService dashboardService;
    @MockBean AlertsService alertsService;
    @MockBean VisitService visitService;
    @MockBean ProcurementService procurementService;
    @MockBean SupplierBillService supplierBillService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        StockBatch b = StockBatch.builder().id(1L).build();
        when(alertsService.load(1L)).thenReturn(new AlertsService.AlertsView(
                List.of(b, b), List.of(b), List.of(b, b, b, b), List.of(), Map.of(), Map.of()));
        when(dashboardService.load(1L)).thenReturn(new DashboardService.DashboardView(
                3, 7, 12, new BigDecimal("4500.50"),
                List.of(new DashboardService.TopMedicine(3L, "Paracetamol", 42))));
    }

    @Test
    @WithMockAppUser(role = "OWNER", fullName = "Dr Owner")
    void home_aggregates_counters_from_all_services() throws Exception {
        when(visitService.followUpsFor(eq(1L), eq(LocalDate.now())))
                .thenReturn(List.of(Visit.builder().id(1L).build()));
        when(procurementService.pendingCount(1L)).thenReturn(4L);
        when(supplierBillService.outstandingTotal(1L)).thenReturn(new BigDecimal("1200.00"));
        when(supplierBillService.overdueCount(1L)).thenReturn(2L);

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/home"))
                .andExpect(model().attributeExists("dashboard"))
                .andExpect(model().attribute("expiredCount", 2))
                .andExpect(model().attribute("criticalCount", 1))
                .andExpect(model().attribute("lowStockCount", 0))
                .andExpect(model().attribute("followUpsToday", hasSize(1)))
                .andExpect(model().attribute("pendingProcurement", 4L))
                .andExpect(model().attribute("outstandingBills", new BigDecimal("1200.00")))
                .andExpect(model().attribute("overdueBillCount", 2L))
                .andExpect(content().string(containsString("Dr Owner")))
                .andExpect(content().string(containsString("Paracetamol")))
                .andExpect(content().string(containsString("4500.50")))
                .andExpect(content().string(containsString("href=\"/procurement\"")));
    }

    @Test
    @WithMockAppUser(role = "RECEPTIONIST")
    void zero_state_hides_procurement_banner() throws Exception {
        when(visitService.followUpsFor(eq(1L), eq(LocalDate.now()))).thenReturn(List.of());
        when(procurementService.pendingCount(1L)).thenReturn(0L);
        when(supplierBillService.outstandingTotal(1L)).thenReturn(BigDecimal.ZERO);
        when(supplierBillService.overdueCount(1L)).thenReturn(0L);

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingProcurement", 0L))
                .andExpect(content().string(not(containsString("href=\"/procurement\""))));
    }

    private void stubQuietDay() {
        when(visitService.followUpsFor(eq(1L), eq(LocalDate.now()))).thenReturn(List.of());
        when(procurementService.pendingCount(1L)).thenReturn(0L);
        when(supplierBillService.outstandingTotal(1L)).thenReturn(BigDecimal.ZERO);
        when(supplierBillService.overdueCount(1L)).thenReturn(0L);
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void owner_sees_new_sale_button_and_sales_tile() throws Exception {
        stubQuietDay();
        mvc.perform(get("/"))
                .andExpect(content().string(containsString("href=\"/sales/new\"")))
                .andExpect(content().string(containsString("4500.50")));
    }

    @Test
    @WithMockAppUser(role = "RECEPTIONIST")
    void receptionist_sees_no_sales_button_tile_or_revenue() throws Exception {
        stubQuietDay();
        // Receptionists don't bill (SaleController is OWNER/DOCTOR/PHARMACIST), so no sales
        // links anywhere on the page — nav, quick action or tile — and no revenue figure.
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/sales"))))
                .andExpect(content().string(not(containsString("4500.50"))));
    }

    @Test
    void unauthenticated_is_redirected_to_login() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        verify(dashboardService, never()).load(1L);
    }

    @Test
    @WithMockAppUser(role = "PLATFORM_ADMIN")
    void platform_admin_is_redirected_to_platform_home() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/platform"));
        verify(dashboardService, never()).load(org.mockito.ArgumentMatchers.any());
    }
}
