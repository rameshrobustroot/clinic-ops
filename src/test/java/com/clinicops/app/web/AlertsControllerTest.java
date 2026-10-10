package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.AlertsService;
import com.clinicops.app.service.TermsService;
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

/** Controller-layer tests for {@link AlertsController}. */
@WebMvcTest(AlertsController.class)
@Import(SecurityConfig.class)
class AlertsControllerTest {

    @Autowired MockMvc mvc;

    @MockBean AlertsService alertsService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private AlertsService.AlertsView view;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        LocalDate today = LocalDate.now();
        Medicine crocin = Medicine.builder().id(3L).clinicId(1L).name("Crocin").build();
        Medicine dolo = Medicine.builder().id(4L).clinicId(1L).name("Dolo").build();
        StockBatch expired = StockBatch.builder().id(1L).medicineId(3L).batchNumber("EXP1").quantity(5)
                .expiryDate(today.minusDays(4)).build();
        StockBatch critical = StockBatch.builder().id(2L).medicineId(3L).batchNumber("CRIT1").quantity(6)
                .expiryDate(today.plusDays(12)).build();
        // medicine 9 is not in medicinesById → fallback label
        StockBatch near = StockBatch.builder().id(3L).medicineId(9L).batchNumber("NEAR1").quantity(7)
                .expiryDate(today.plusDays(60)).build();
        view = new AlertsService.AlertsView(
                List.of(expired), List.of(critical), List.of(near),
                List.of(new AlertsService.LowStockEntry(dolo, 2, 10)),
                Map.of(3L, crocin, 4L, dolo),
                Map.of(1L, -4L, 2L, 12L, 3L, 60L));
    }

    @Test
    @WithMockAppUser(role = "PHARMACIST")
    void renders_all_alert_sections_with_write_off_button() throws Exception {
        when(alertsService.load(1L)).thenReturn(view);

        mvc.perform(get("/alerts"))
                .andExpect(status().isOk())
                .andExpect(view().name("alerts/view"))
                .andExpect(model().attribute("view", view))
                .andExpect(model().attribute("today", LocalDate.now()))
                .andExpect(content().string(containsString("EXP1")))
                .andExpect(content().string(containsString("4d expired")))
                .andExpect(content().string(containsString("12d left")))
                .andExpect(content().string(containsString("Medicine 9")))
                .andExpect(content().string(containsString("reorder when ≤ 10")))
                .andExpect(content().string(containsString("/inventory/write-off-expired")));
        verify(alertsService).load(1L);
    }

    @Test
    @WithMockAppUser(role = "DOCTOR")
    void doctor_sees_alerts_but_not_write_off_button() throws Exception {
        when(alertsService.load(1L)).thenReturn(view);

        mvc.perform(get("/alerts"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/inventory/write-off-expired"))));
    }

    @Test
    @WithMockAppUser(role = "OWNER")
    void empty_view_shows_empty_states() throws Exception {
        when(alertsService.load(1L)).thenReturn(new AlertsService.AlertsView(
                List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of()));

        mvc.perform(get("/alerts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No expired stock")))
                .andExpect(content().string(containsString("No low-stock medicines.")))
                .andExpect(content().string(not(containsString("/inventory/write-off-expired"))));
    }

    @Test
    void unauthenticated_is_redirected_to_login() throws Exception {
        mvc.perform(get("/alerts"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
