package com.clinicops.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.domain.ProcurementItem.Status;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.ProcurementService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.tenant.CrossTenantAccessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link ProcurementController} — OWNER/PHARMACIST to-order list. */
@WebMvcTest(ProcurementController.class)
@Import(SecurityConfig.class)
class ProcurementControllerTest {

    @Autowired MockMvc mvc;

    @MockBean ProcurementService procurementService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private ProcurementItem pending;
    private ProcurementItem received;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(currentUser.userId()).thenReturn(99L);
        pending = ProcurementItem.builder().id(1L).clinicId(1L).medicineName("Paracetamol 500")
                .source(ProcurementItem.Source.MANUAL).status(Status.PENDING)
                .createdAt(Instant.parse("2026-09-30T08:00:00Z")).build();
        received = ProcurementItem.builder().id(2L).clinicId(1L).medicineName("Amoxicillin")
                .source(ProcurementItem.Source.PRESCRIPTION).sourceVisitId(77L).status(Status.RECEIVED)
                .createdAt(Instant.parse("2026-09-29T08:00:00Z")).build();
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_view_procurement() throws Exception {
            mvc.perform(get("/procurement")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_add_items() throws Exception {
            mvc.perform(post("/procurement/add").with(csrf()).param("medicineName", "X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(procurementService);
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_change_status() throws Exception {
            mvc.perform(post("/procurement/1/status").with(csrf()).param("status", "ORDERED"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(procurementService);
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_redirected_to_platform() throws Exception {
            mvc.perform(get("/procurement")).andExpect(redirectedUrl("/platform"));
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/procurement")).andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/procurement/add").param("medicineName", "X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(procurementService);
        }
    }

    // ── GET /procurement ──────────────────────────────────────────────

    @Nested
    class ListItems {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void default_view_shows_pending_items() throws Exception {
            when(procurementService.pending(1L)).thenReturn(List.of(pending));

            mvc.perform(get("/procurement"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("procurement/list"))
                    .andExpect(model().attribute("items", List.of(pending)))
                    .andExpect(model().attribute("showAll", false))
                    .andExpect(model().attributeExists("statuses"))
                    .andExpect(content().string(containsString("Paracetamol 500")));
            verify(procurementService, never()).all(anyLong());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void view_all_shows_every_item() throws Exception {
            when(procurementService.all(1L)).thenReturn(List.of(pending, received));

            mvc.perform(get("/procurement").param("view", "ALL"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("items", List.of(pending, received)))
                    .andExpect(model().attribute("showAll", true))
                    .andExpect(content().string(containsString("Amoxicillin")));
            verify(procurementService, never()).pending(anyLong());
        }
    }

    // ── POST /procurement/add ─────────────────────────────────────────

    @Nested
    class AddManual {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void adds_trimmed_item_for_current_user_and_clinic() throws Exception {
            mvc.perform(post("/procurement/add").with(csrf())
                            .param("medicineName", "  Cetirizine 10  ")
                            .param("notes", "2 strips"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/procurement"));
            verify(procurementService).addManual(1L, 99L, "Cetirizine 10", "2 strips");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void notes_are_optional() throws Exception {
            mvc.perform(post("/procurement/add").with(csrf()).param("medicineName", "ORS"))
                    .andExpect(redirectedUrl("/procurement"));
            verify(procurementService).addManual(org.mockito.ArgumentMatchers.eq(1L),
                    org.mockito.ArgumentMatchers.eq(99L), org.mockito.ArgumentMatchers.eq("ORS"), isNull());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void blank_medicine_name_is_silently_ignored() throws Exception {
            mvc.perform(post("/procurement/add").with(csrf()).param("medicineName", "   "))
                    .andExpect(redirectedUrl("/procurement"));
            verify(procurementService, never()).addManual(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void missing_medicine_name_is_400() throws Exception {
            mvc.perform(post("/procurement/add").with(csrf())).andExpect(status().isBadRequest());
        }
    }

    // ── POST /procurement/{id}/status ─────────────────────────────────

    @Nested
    class UpdateStatus {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void updates_status() throws Exception {
            mvc.perform(post("/procurement/1/status").with(csrf()).param("status", "ORDERED"))
                    .andExpect(redirectedUrl("/procurement"));
            verify(procurementService).updateStatus(1L, Status.ORDERED);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void unknown_status_is_400() throws Exception {
            mvc.perform(post("/procurement/1/status").with(csrf()).param("status", "LOST"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(procurementService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void other_clinic_item_is_404() throws Exception {
            doThrow(new CrossTenantAccessException()).when(procurementService).updateStatus(55L, Status.CANCELLED);

            mvc.perform(post("/procurement/55/status").with(csrf()).param("status", "CANCELLED"))
                    .andExpect(status().isNotFound());
        }
    }
}
