package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Supplier;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.TermsService;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;

/** Controller-layer tests for {@link SupplierController} — OWNER/PHARMACIST supplier CRUD. */
@WebMvcTest(SupplierController.class)
@Import(SecurityConfig.class)
class SupplierControllerTest {

    @Autowired MockMvc mvc;

    @MockBean SupplierRepository supplierRepository;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private Supplier existing;

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        existing = Supplier.builder().id(5L).clinicId(1L).name("MedTech Pharma").phone("9876543210")
                .creditBalance(BigDecimal.ZERO).build();
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> {
            Supplier s = inv.getArgument(0);
            if (s.getId() == null) s.setId(42L);
            return s;
        });
    }

    // ── Authorization ──────────────────────────────────────────────────

    @Nested
    class Authorization {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_list_suppliers() throws Exception {
            mvc.perform(get("/suppliers")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_create_supplier() throws Exception {
            mvc.perform(post("/suppliers").with(csrf()).param("name", "X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(supplierRepository);
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_update_supplier() throws Exception {
            mvc.perform(post("/suppliers/5").with(csrf()).param("name", "X"))
                    .andExpect(status().isForbidden());
            verify(supplierRepository, never()).save(any());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void pharmacist_can_list_suppliers() throws Exception {
            mvc.perform(get("/suppliers")).andExpect(status().isOk());
        }

        @Test
        @WithMockAppUser(role = "PLATFORM_ADMIN")
        void platform_admin_is_redirected_to_platform() throws Exception {
            mvc.perform(get("/suppliers")).andExpect(redirectedUrl("/platform"));
        }

        @Test
        void anonymous_user_is_redirected_to_login() throws Exception {
            mvc.perform(get("/suppliers")).andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/suppliers").param("name", "X")).andExpect(status().isForbidden());
            verifyNoInteractions(supplierRepository);
        }
    }

    // ── GET views ─────────────────────────────────────────────────────

    @Nested
    class Views {

        @Test
        @WithMockAppUser(role = "OWNER")
        void lists_clinic_suppliers() throws Exception {
            when(supplierRepository.findByClinicIdOrderByNameAsc(1L)).thenReturn(List.of(existing));

            mvc.perform(get("/suppliers"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("supplier/list"))
                    .andExpect(model().attribute("suppliers", List.of(existing)))
                    .andExpect(content().string(containsString("MedTech Pharma")));
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void new_form_carries_return_to() throws Exception {
            mvc.perform(get("/suppliers/new").param("returnTo", "/supplier-bills/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("supplier/form"))
                    .andExpect(model().attributeExists("supplier"))
                    .andExpect(model().attribute("returnTo", "/supplier-bills/new"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_loads_own_clinic_supplier() throws Exception {
            mvc.perform(get("/suppliers/5/edit"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("supplier/form"))
                    .andExpect(model().attribute("supplier", existing));
        }

        @Test
        @WithMockAppUser(role = "OWNER", clinicId = 1L)
        void edit_form_for_other_clinic_supplier_is_404() throws Exception {
            when(supplierRepository.findById(9L)).thenReturn(Optional.of(
                    Supplier.builder().id(9L).clinicId(2L).name("Rival Pharma").build()));

            mvc.perform(get("/suppliers/9/edit"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_for_missing_supplier_is_404() throws Exception {
            mvc.perform(get("/suppliers/404/edit"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }

    // ── POST /suppliers ───────────────────────────────────────────────

    @Nested
    class Create {

        @Test
        @WithMockAppUser(role = "OWNER")
        void creates_supplier_in_current_clinic_ignoring_posted_clinic_id() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "New Pharma")
                            .param("phone", "9123456789")
                            .param("clinicId", "2"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/suppliers"))
                    .andExpect(flash().attribute("flash", "Supplier added: New Pharma"));

            ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
            verify(supplierRepository).save(captor.capture());
            assertThat(captor.getValue().getClinicId()).isEqualTo(1L);
            assertThat(captor.getValue().getName()).isEqualTo("New Pharma");
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void returns_to_caller_with_new_supplier_id() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "New Pharma")
                            .param("returnTo", "/supplier-bills/new"))
                    .andExpect(redirectedUrl("/supplier-bills/new?newSupplierId=42"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void return_to_with_query_string_appends_with_ampersand() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "New Pharma")
                            .param("returnTo", "/supplier-bills/new?supplierId=3"))
                    .andExpect(redirectedUrl("/supplier-bills/new?supplierId=3&newSupplierId=42"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void absolute_return_to_is_ignored_to_prevent_open_redirect() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "New Pharma")
                            .param("returnTo", "https://evil.example.com/phish"))
                    .andExpect(redirectedUrl("/suppliers"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void protocol_relative_return_to_is_ignored() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "New Pharma")
                            .param("returnTo", "//evil.example.com"))
                    .andExpect(redirectedUrl("/suppliers"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void backslash_return_to_is_ignored() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "New Pharma")
                            .param("returnTo", "/\\evil.example.com"))
                    .andExpect(redirectedUrl("/suppliers"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void blank_name_rerenders_form_and_keeps_return_to() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "")
                            .param("returnTo", "/supplier-bills/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("supplier/form"))
                    .andExpect(model().attributeHasFieldErrors("supplier", "name"))
                    .andExpect(model().attribute("returnTo", "/supplier-bills/new"))
                    .andExpect(content().string(containsString("Name is required")));
            verify(supplierRepository, never()).save(any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_gstin_and_ifsc_are_rejected() throws Exception {
            mvc.perform(post("/suppliers").with(csrf())
                            .param("name", "X Pharma")
                            .param("gstin", "BAD")
                            .param("bankIfsc", "BAD"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("supplier", "gstin", "bankIfsc"));
            verify(supplierRepository, never()).save(any());
        }
    }

    // ── POST /suppliers/{id} ──────────────────────────────────────────

    @Nested
    class Update {

        @Test
        @WithMockAppUser(role = "OWNER")
        void updates_editable_fields() throws Exception {
            mvc.perform(post("/suppliers/5").with(csrf())
                            .param("name", "MedTech Pharma Ltd")
                            .param("phone", "9123456789")
                            .param("upiId", "medtech@upi")
                            .param("notes", "Net 30"))
                    .andExpect(redirectedUrl("/suppliers"))
                    .andExpect(flash().attribute("flash", "Supplier updated."));

            ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
            verify(supplierRepository).save(captor.capture());
            Supplier saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getName()).isEqualTo("MedTech Pharma Ltd");
            assertThat(saved.getUpiId()).isEqualTo("medtech@upi");
            assertThat(saved.getNotes()).isEqualTo("Net 30");
            assertThat(saved.getClinicId()).isEqualTo(1L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void invalid_update_rerenders_form() throws Exception {
            mvc.perform(post("/suppliers/5").with(csrf()).param("name", " "))
                    .andExpect(status().isOk())
                    .andExpect(view().name("supplier/form"))
                    .andExpect(model().attributeHasFieldErrors("supplier", "name"));
            verify(supplierRepository, never()).save(any());
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST", clinicId = 1L)
        void updating_other_clinic_supplier_is_404_and_not_saved() throws Exception {
            when(supplierRepository.findById(9L)).thenReturn(Optional.of(
                    Supplier.builder().id(9L).clinicId(2L).name("Rival Pharma").build()));

            mvc.perform(post("/suppliers/9").with(csrf()).param("name", "Hijacked"))
                    .andExpect(status().isNotFound());
            verify(supplierRepository, never()).save(any());
        }
    }
}
