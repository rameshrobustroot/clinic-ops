package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.Supplier;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.InventoryService;
import com.clinicops.app.service.TermsService;
import com.clinicops.app.tenant.CrossTenantAccessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

/** Controller-layer tests for {@link InventoryController}. */
@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventoryControllerTest {

    @Autowired MockMvc mvc;

    @MockBean InventoryService inventoryService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        when(currentUser.clinicId()).thenReturn(1L);
        when(currentUser.userId()).thenReturn(99L);
    }

    private static Medicine med(long id, String name) {
        return Medicine.builder().id(id).clinicId(1L).name(name).strength("500mg").lowStockThreshold(10).build();
    }

    private static StockBatch batch(long id, long medId, int qty, LocalDate expiry) {
        return StockBatch.builder().id(id).clinicId(1L).medicineId(medId).batchNumber("BN" + id)
                .quantity(qty).expiryDate(expiry).build();
    }

    // ── GET /inventory + /inventory/search ──────────────────────────────

    @Nested
    class ListPage {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void computes_stock_totals_and_expiry_chips_per_medicine() throws Exception {
            List<Medicine> meds = List.of(med(1, "NoStock"), med(2, "Expired"), med(3, "Soon"),
                    med(4, "Quarter"), med(5, "Fresh"));
            when(inventoryService.search(1L, "a", 0)).thenReturn(new PageImpl<>(meds, PageRequest.of(0, 20), 5));
            // NoStock: only an empty batch → ignored
            when(inventoryService.batchesFor(1L)).thenReturn(List.of(batch(10, 1, 0, TODAY.plusDays(5))));
            when(inventoryService.batchesFor(2L)).thenReturn(List.of(batch(20, 2, 3, TODAY.minusDays(1))));
            when(inventoryService.batchesFor(3L)).thenReturn(List.of(batch(30, 3, 3, TODAY.plusDays(30))));
            when(inventoryService.batchesFor(4L)).thenReturn(List.of(batch(40, 4, 3, TODAY.plusDays(90))));
            when(inventoryService.batchesFor(5L)).thenReturn(List.of(batch(50, 5, 3, TODAY.plusDays(91))));
            when(inventoryService.totalStock(2L)).thenReturn(3);

            String label = "expires " + TODAY.minusDays(1).format(DateTimeFormatter.ofPattern("dd MMM"));
            mvc.perform(get("/inventory").param("q", "a"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/list"))
                    .andExpect(model().attribute("medicines", hasSize(5)))
                    .andExpect(model().attribute("q", "a"))
                    .andExpect(model().attribute("totals", hasEntry(2L, 3)))
                    .andExpect(model().attribute("expiryLabel", hasEntry(1L, "no stock")))
                    .andExpect(model().attribute("expiryLabel", hasEntry(2L, label)))
                    .andExpect(model().attribute("expiryClass", hasEntry(1L, "text-slate-400")))
                    .andExpect(model().attribute("expiryClass", hasEntry(2L, "text-red-600 font-semibold")))
                    .andExpect(model().attribute("expiryClass", hasEntry(3L, "text-red-500")))
                    .andExpect(model().attribute("expiryClass", hasEntry(4L, "text-amber-600")))
                    .andExpect(model().attribute("expiryClass", hasEntry(5L, "text-slate-400")))
                    .andExpect(content().string(containsString("Quarter")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void negative_page_is_clamped_and_null_query_is_blank() throws Exception {
            when(inventoryService.search(1L, null, 0)).thenReturn(new PageImpl<>(List.of()));
            mvc.perform(get("/inventory").param("page", "-1"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("q", ""));
            verify(inventoryService).search(1L, null, 0);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void htmx_search_returns_results_fragment() throws Exception {
            when(inventoryService.search(1L, "par", 0))
                    .thenReturn(new PageImpl<>(List.of(med(1, "Paracetamol"))));
            when(inventoryService.batchesFor(1L)).thenReturn(List.of(batch(10, 1, 5, TODAY.plusDays(10))));

            mvc.perform(get("/inventory/search").param("q", "par"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/list :: results"))
                    .andExpect(model().attribute("expiryClass", hasEntry(1L, "text-red-500")))
                    .andExpect(content().string(containsString("Paracetamol")));
        }

        @Test
        void unauthenticated_is_redirected_to_login() throws Exception {
            mvc.perform(get("/inventory"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    // ── Medicine create / edit / delete / update ────────────────────────

    @Nested
    class MedicineCrud {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void new_form_renders_for_pharmacist() throws Exception {
            mvc.perform(get("/inventory/medicines/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/medicine-form"))
                    .andExpect(model().attributeExists("medicine"));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void new_form_forbidden_for_doctor() throws Exception {
            mvc.perform(get("/inventory/medicines/new")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void new_form_forbidden_for_receptionist() throws Exception {
            mvc.perform(get("/inventory/medicines/new")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void valid_create_saves_and_redirects_with_flash() throws Exception {
            when(inventoryService.createMedicine(eq(1L), any(Medicine.class))).thenReturn(med(8, "Crocin"));

            mvc.perform(post("/inventory/medicines").with(csrf())
                            .param("name", "Crocin").param("genericName", "Paracetamol")
                            .param("strength", "650mg").param("lowStockThreshold", "20"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/inventory"))
                    .andExpect(flash().attribute("flash", "Medicine added: Crocin"));

            ArgumentCaptor<Medicine> captor = ArgumentCaptor.forClass(Medicine.class);
            verify(inventoryService).createMedicine(eq(1L), captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("Crocin");
            assertThat(captor.getValue().getGenericName()).isEqualTo("Paracetamol");
            assertThat(captor.getValue().getStrength()).isEqualTo("650mg");
            assertThat(captor.getValue().getLowStockThreshold()).isEqualTo(20);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void blank_name_rerenders_form_and_does_not_save() throws Exception {
            mvc.perform(post("/inventory/medicines").with(csrf()).param("name", " ").param("strength", "5mg"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/medicine-form"))
                    .andExpect(model().attribute("error", "Name is required"))
                    .andExpect(content().string(containsString("Name is required")));
            verify(inventoryService, never()).createMedicine(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void duplicate_medicine_shows_link_to_existing() throws Exception {
            when(inventoryService.createMedicine(eq(1L), any(Medicine.class)))
                    .thenThrow(new InventoryService.DuplicateMedicineException(med(4, "Crocin")));

            mvc.perform(post("/inventory/medicines").with(csrf()).param("name", "Crocin"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/medicine-form"))
                    .andExpect(model().attribute("duplicateId", 4L))
                    .andExpect(model().attribute("error", containsString("already exists")))
                    .andExpect(content().string(containsString("/inventory/medicines/4")));
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_create() throws Exception {
            mvc.perform(post("/inventory/medicines").with(csrf()).param("name", "X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void create_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/inventory/medicines").param("name", "X"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void create_ignores_client_supplied_id() throws Exception {
            when(inventoryService.createMedicine(eq(1L), any(Medicine.class))).thenReturn(med(8, "Crocin"));

            mvc.perform(post("/inventory/medicines").with(csrf()).param("id", "5").param("name", "Crocin"));

            ArgumentCaptor<Medicine> captor = ArgumentCaptor.forClass(Medicine.class);
            verify(inventoryService).createMedicine(eq(1L), captor.capture());
            assertThat(captor.getValue().getId()).isNull();
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void edit_form_loads_medicine() throws Exception {
            when(inventoryService.getOwnMedicineOrThrow(5L)).thenReturn(med(5, "Crocin"));

            mvc.perform(get("/inventory/medicines/5/edit"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/medicine-form"))
                    .andExpect(model().attribute("medicine", org.hamcrest.Matchers.hasProperty("name",
                            org.hamcrest.Matchers.is("Crocin"))))
                    .andExpect(content().string(containsString("value=\"Crocin\"")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_posts_to_update_endpoint() throws Exception {
            when(inventoryService.getOwnMedicineOrThrow(5L)).thenReturn(med(5, "Crocin"));

            mvc.perform(get("/inventory/medicines/5/edit"))
                    .andExpect(content().string(containsString("action=\"/inventory/medicines/5\"")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_for_cross_tenant_medicine_is_404() throws Exception {
            when(inventoryService.getOwnMedicineOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/inventory/medicines/77/edit"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_calls_service_and_redirects_to_detail() throws Exception {
            mvc.perform(post("/inventory/medicines/5").with(csrf())
                            .param("name", "Crocin Advance").param("lowStockThreshold", "15"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/inventory/medicines/5"))
                    .andExpect(flash().attribute("flash", "Medicine updated."));

            ArgumentCaptor<Medicine> captor = ArgumentCaptor.forClass(Medicine.class);
            verify(inventoryService).updateMedicine(eq(5L), captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("Crocin Advance");
            assertThat(captor.getValue().getLowStockThreshold()).isEqualTo(15);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_with_blank_name_is_rejected() throws Exception {
            mvc.perform(post("/inventory/medicines/5").with(csrf()).param("name", ""))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/medicine-form"))
                    .andExpect(model().attribute("error", "Name is required"))
                    // re-rendered form must still target the update endpoint, not create
                    .andExpect(content().string(containsString("action=\"/inventory/medicines/5\"")));
            verify(inventoryService, never()).updateMedicine(any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_to_a_duplicate_name_rerenders_with_link_to_existing() throws Exception {
            when(inventoryService.updateMedicine(eq(5L), any()))
                    .thenThrow(new InventoryService.DuplicateMedicineException(med(9, "Crocin")));

            mvc.perform(post("/inventory/medicines/5").with(csrf()).param("name", "Crocin"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/medicine-form"))
                    .andExpect(model().attribute("duplicateId", 9L))
                    .andExpect(content().string(containsString("action=\"/inventory/medicines/5\"")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void server_owned_fields_are_not_bound_on_create() throws Exception {
            when(inventoryService.createMedicine(eq(1L), any(Medicine.class))).thenReturn(med(8, "Crocin"));

            mvc.perform(post("/inventory/medicines").with(csrf())
                    .param("name", "Crocin").param("clinicId", "2").param("active", "false"));

            ArgumentCaptor<Medicine> captor = ArgumentCaptor.forClass(Medicine.class);
            verify(inventoryService).createMedicine(eq(1L), captor.capture());
            assertThat(captor.getValue().getClinicId()).isNull();
            assertThat(captor.getValue().getActive()).isNotEqualTo(Boolean.FALSE);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void new_form_posts_to_create_endpoint() throws Exception {
            mvc.perform(get("/inventory/medicines/new"))
                    .andExpect(content().string(containsString("action=\"/inventory/medicines\"")))
                    .andExpect(content().string(containsString("New Medicine")));
        }
    }

    // ── Shared-catalog (platform) medicines are read-only for clinics ───

    @Nested
    class PlatformMedicineReadOnly {

        @Test
        @WithMockAppUser(role = "OWNER")
        void edit_form_redirects_to_detail_with_notice() throws Exception {
            when(inventoryService.getOwnMedicineOrThrow(5L))
                    .thenThrow(new InventoryService.PlatformMedicineReadOnlyException());

            mvc.perform(get("/inventory/medicines/5/edit"))
                    .andExpect(redirectedUrl("/inventory/medicines/5"))
                    .andExpect(flash().attributeExists("flashInfo"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_redirects_to_detail_with_notice() throws Exception {
            when(inventoryService.updateMedicine(eq(5L), any()))
                    .thenThrow(new InventoryService.PlatformMedicineReadOnlyException());

            mvc.perform(post("/inventory/medicines/5").with(csrf()).param("name", "Renamed"))
                    .andExpect(redirectedUrl("/inventory/medicines/5"))
                    .andExpect(flash().attributeExists("flashInfo"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void delete_redirects_to_detail_with_notice() throws Exception {
            org.mockito.Mockito.doThrow(new InventoryService.PlatformMedicineReadOnlyException())
                    .when(inventoryService).softDeleteMedicine(5L);

            mvc.perform(post("/inventory/medicines/5/delete").with(csrf()))
                    .andExpect(redirectedUrl("/inventory/medicines/5"))
                    .andExpect(flash().attributeExists("flashInfo"));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void detail_page_hides_edit_and_delete_for_shared_medicine() throws Exception {
            Medicine shared = med(5, "Dolo");
            shared.setClinicId(null);
            when(inventoryService.getMedicineOrThrow(5L)).thenReturn(shared);
            when(inventoryService.batchesFor(5L)).thenReturn(List.of());
            when(inventoryService.totalStock(5L)).thenReturn(0);

            mvc.perform(get("/inventory/medicines/5"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Shared catalog")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            containsString("/inventory/medicines/5/edit"))));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void low_stock_chip_shows_at_exactly_the_threshold_like_the_alerts_page() throws Exception {
            when(inventoryService.getMedicineOrThrow(5L)).thenReturn(med(5, "Crocin")); // threshold 10
            when(inventoryService.batchesFor(5L)).thenReturn(List.of());

            when(inventoryService.totalStock(5L)).thenReturn(10);
            mvc.perform(get("/inventory/medicines/5"))
                    .andExpect(content().string(containsString("LOW STOCK")));

            when(inventoryService.totalStock(5L)).thenReturn(11);
            mvc.perform(get("/inventory/medicines/5"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("LOW STOCK"))));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void detail_page_shows_edit_for_own_medicine() throws Exception {
            when(inventoryService.getMedicineOrThrow(5L)).thenReturn(med(5, "Crocin"));
            when(inventoryService.batchesFor(5L)).thenReturn(List.of());
            when(inventoryService.totalStock(5L)).thenReturn(0);

            mvc.perform(get("/inventory/medicines/5"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("/inventory/medicines/5/edit")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void update_of_cross_tenant_medicine_is_404() throws Exception {
            when(inventoryService.updateMedicine(eq(77L), any())).thenThrow(new CrossTenantAccessException());
            mvc.perform(post("/inventory/medicines/77").with(csrf()).param("name", "x"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_update() throws Exception {
            mvc.perform(post("/inventory/medicines/5").with(csrf()).param("name", "x"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void delete_soft_deletes_and_redirects() throws Exception {
            mvc.perform(post("/inventory/medicines/5/delete").with(csrf()))
                    .andExpect(redirectedUrl("/inventory"))
                    .andExpect(flash().attribute("flash", "Medicine deleted."));
            verify(inventoryService).softDeleteMedicine(5L);
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_delete() throws Exception {
            mvc.perform(post("/inventory/medicines/5/delete").with(csrf()))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void delete_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/inventory/medicines/5/delete")).andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }
    }

    // ── GET /inventory/medicines/{id} ───────────────────────────────────

    @Nested
    class MedicineDetail {

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void shows_batches_with_expiry_chips_and_suppliers() throws Exception {
            when(inventoryService.getMedicineOrThrow(5L)).thenReturn(med(5, "Crocin"));
            when(inventoryService.batchesFor(5L)).thenReturn(List.of(
                    batch(1, 5, 2, TODAY.minusDays(3)),
                    batch(2, 5, 2, TODAY.plusDays(10)),
                    batch(3, 5, 2, TODAY.plusDays(60)),
                    batch(4, 5, 2, TODAY.plusDays(200))));
            when(inventoryService.totalStock(5L)).thenReturn(8);
            when(inventoryService.suppliers(1L)).thenReturn(List.of(
                    Supplier.builder().id(3L).clinicId(1L).name("MedTech").build()));

            mvc.perform(get("/inventory/medicines/5").param("newSupplierId", "3"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("inventory/medicine-detail"))
                    .andExpect(model().attribute("total", 8))
                    .andExpect(model().attribute("newSupplierId", 3L))
                    .andExpect(model().attribute("tomorrow", TODAY.plusDays(1).toString()))
                    .andExpect(model().attribute("batchClass", hasEntry(1L, "chip-red")))
                    .andExpect(model().attribute("batchLabel", hasEntry(1L, "3d expired")))
                    .andExpect(model().attribute("batchClass", hasEntry(2L, "chip-red")))
                    .andExpect(model().attribute("batchLabel", hasEntry(2L, "10d left")))
                    .andExpect(model().attribute("batchClass", hasEntry(3L, "chip-yellow")))
                    .andExpect(model().attribute("batchClass", hasEntry(4L, "chip-green")))
                    .andExpect(content().string(containsString("MedTech")))
                    .andExpect(content().string(containsString("BN1")));
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void cross_tenant_medicine_is_404() throws Exception {
            when(inventoryService.getMedicineOrThrow(77L)).thenThrow(new CrossTenantAccessException());
            mvc.perform(get("/inventory/medicines/77"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/404"));
        }
    }

    // ── Batches: add / adjust / write-off ───────────────────────────────

    @Nested
    class Batches {

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void add_batch_with_future_expiry_calls_service() throws Exception {
            LocalDate expiry = TODAY.plusMonths(6);
            mvc.perform(post("/inventory/medicines/5/batches").with(csrf())
                            .param("batchNumber", "BX1").param("expiryDate", expiry.toString())
                            .param("quantity", "100").param("purchasePrice", "1.50")
                            .param("salePrice", "2.00").param("supplierId", "3"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/inventory/medicines/5"));

            verify(inventoryService).addBatch(1L, 5L, 99L, "BX1", expiry, 100,
                    new BigDecimal("1.50"), new BigDecimal("2.00"), 3L);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void add_batch_expiring_today_or_earlier_is_rejected() throws Exception {
            for (LocalDate d : new LocalDate[] {TODAY, TODAY.minusDays(1)}) {
                mvc.perform(post("/inventory/medicines/5/batches").with(csrf())
                                .param("batchNumber", "BX1").param("expiryDate", d.toString())
                                .param("quantity", "10"))
                        .andExpect(redirectedUrl("/inventory/medicines/5"))
                        .andExpect(flash().attribute("error", "Batch expiry must be a future date."));
            }
            verify(inventoryService, never()).addBatch(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void add_batch_with_malformed_expiry_shows_error_instead_of_500() throws Exception {
            mvc.perform(post("/inventory/medicines/5/batches").with(csrf())
                            .param("batchNumber", "BX1").param("expiryDate", "12/2027")
                            .param("quantity", "10"))
                    .andExpect(redirectedUrl("/inventory/medicines/5"))
                    .andExpect(flash().attribute("error", "Batch expiry is not a valid date."));
            verify(inventoryService, never()).addBatch(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void add_batch_with_non_positive_quantity_is_rejected() throws Exception {
            mvc.perform(post("/inventory/medicines/5/batches").with(csrf())
                            .param("batchNumber", "BX1").param("expiryDate", TODAY.plusDays(30).toString())
                            .param("quantity", "0"))
                    .andExpect(redirectedUrl("/inventory/medicines/5"))
                    .andExpect(flash().attribute("error", "Quantity must be greater than zero."));
            verify(inventoryService, never()).addBatch(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void add_batch_missing_required_param_is_bad_request() throws Exception {
            mvc.perform(post("/inventory/medicines/5/batches").with(csrf())
                            .param("expiryDate", TODAY.plusDays(30).toString()).param("quantity", "5"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_add_batch() throws Exception {
            mvc.perform(post("/inventory/medicines/5/batches").with(csrf())
                            .param("batchNumber", "BX1").param("expiryDate", TODAY.plusDays(30).toString())
                            .param("quantity", "5"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void adjust_batch_defaults_blank_reason() throws Exception {
            mvc.perform(post("/inventory/batches/12/adjust").with(csrf())
                            .param("medicineId", "5").param("newQuantity", "7").param("reason", " "))
                    .andExpect(redirectedUrl("/inventory/medicines/5"));
            verify(inventoryService).adjustBatch(12L, 99L, 7, "manual adjustment");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void adjust_batch_passes_given_reason() throws Exception {
            mvc.perform(post("/inventory/batches/12/adjust").with(csrf())
                            .param("medicineId", "5").param("newQuantity", "0").param("reason", "damaged"))
                    .andExpect(redirectedUrl("/inventory/medicines/5"));
            verify(inventoryService).adjustBatch(12L, 99L, 0, "damaged");
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void adjust_cross_tenant_batch_is_404() throws Exception {
            org.mockito.Mockito.doThrow(new CrossTenantAccessException())
                    .when(inventoryService).adjustBatch(eq(77L), any(), anyInt(), any());
            mvc.perform(post("/inventory/batches/77/adjust").with(csrf())
                            .param("medicineId", "5").param("newQuantity", "1"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockAppUser(role = "RECEPTIONIST")
        void receptionist_cannot_adjust() throws Exception {
            mvc.perform(post("/inventory/batches/12/adjust").with(csrf())
                            .param("medicineId", "5").param("newQuantity", "7"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }

        @Test
        @WithMockAppUser(role = "PHARMACIST")
        void write_off_expired_redirects_to_alerts() throws Exception {
            mvc.perform(post("/inventory/write-off-expired").with(csrf()))
                    .andExpect(redirectedUrl("/alerts"));
            verify(inventoryService).writeOffExpired(1L, 99L);
        }

        @Test
        @WithMockAppUser(role = "DOCTOR")
        void doctor_cannot_write_off() throws Exception {
            mvc.perform(post("/inventory/write-off-expired").with(csrf()))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }

        @Test
        @WithMockAppUser(role = "OWNER")
        void write_off_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/inventory/write-off-expired")).andExpect(status().isForbidden());
            verifyNoInteractions(inventoryService);
        }
    }
}
