package com.clinicops.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the Thymeleaf template nav-active-key wiring.
 *
 * <p>These are intentionally plain Java (no Spring context) — they read the
 * template files directly and assert the correct fragment parameters.  Fast
 * and robust: if someone accidentally changes {@code 'sale'} back to
 * {@code 'dashboard'}, or removes Inventory from the More drawer, this test
 * fails immediately without requiring a full server boot.
 *
 * <p>Covers the changes made in the "Replace Inventory with Sale" feature:
 * <ul>
 *   <li>Sale templates pass {@code 'sale'}, not {@code 'dashboard'}, as active key.</li>
 *   <li>Layout primary nav has a Sale tab (href="/sales", active=='sale').</li>
 *   <li>Layout More drawer contains an Inventory link (href="/inventory").</li>
 *   <li>More button highlights when active is alerts, purchases, <em>or inventory</em>.</li>
 * </ul>
 */
class TemplateNavActiveKeyTest {

    private static final String TEMPLATES = "src/main/resources/templates";

    // ── Sale templates ───────────────────────────────────────────────────

    @Test
    void all_sale_templates_pass_sale_as_active_key() throws IOException {
        for (String template : List.of("sale/list.html", "sale/detail.html", "sale/new.html")) {
            String content = read(template);
            assertThat(content)
                    .describedAs("Template %s should pass 'sale' as active key (not 'dashboard')", template)
                    .contains("body(~{::section}, 'sale')");
            assertThat(content)
                    .describedAs("Template %s must NOT pass 'dashboard' as active key", template)
                    .doesNotContain("body(~{::section}, 'dashboard')");
        }
    }

    // ── Primary nav bar ──────────────────────────────────────────────────

    @Test
    void layout_primary_nav_has_sale_tab_linked_to_sales_url() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("Primary nav should contain a Sale link to /sales")
                .contains("href=\"/sales\"");
    }

    @Test
    void layout_sale_tab_activates_on_sale_active_key() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("Sale tab should highlight when active == 'sale'")
                .contains("active == 'sale'");
    }

    @Test
    void layout_primary_nav_does_not_show_inventory_as_top_level_tab() throws IOException {
        String layout = readLayout();
        // The inventory link should exist BUT only inside the More drawer section,
        // not as a direct sibling of the primary 5 tabs.
        // We verify the More drawer comment precedes the inventory link.
        int moreDrawerIdx = layout.indexOf("More drawer");
        int inventoryHrefIdx = layout.indexOf("href=\"/inventory\"");
        assertThat(moreDrawerIdx).isGreaterThan(0);
        assertThat(inventoryHrefIdx).isGreaterThan(0);
        assertThat(inventoryHrefIdx)
                .describedAs("Inventory link should appear AFTER the More drawer comment (i.e. inside the drawer, not in the primary bar)")
                .isGreaterThan(moreDrawerIdx);
    }

    // ── More drawer ──────────────────────────────────────────────────────

    @Test
    void layout_more_drawer_contains_inventory_link() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("More drawer should link to /inventory")
                .contains("href=\"/inventory\"");
    }

    @Test
    void layout_more_button_highlights_when_inventory_is_active() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("More button highlight condition must include inventory")
                .contains("active == 'alerts' or active == 'purchases' or active == 'inventory'");
    }

    @Test
    void layout_more_drawer_still_contains_alerts_and_purchases() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("Alerts must still be in the More drawer")
                .contains("href=\"/alerts\"");
        assertThat(layout)
                .describedAs("Purchases must still be in the More drawer")
                .contains("href=\"/purchases\"");
    }

    // ── Other nav tabs unchanged ─────────────────────────────────────────

    @Test
    void layout_appointment_tab_uses_schedule_active_key() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("Appointment tab should activate on 'schedule' key (unchanged)")
                .contains("active == 'schedule'");
        assertThat(layout).contains("href=\"/appointments\"");
    }

    @Test
    void layout_patients_tab_still_present() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("Patients tab must remain in primary nav")
                .contains("href=\"/patients\"");
        assertThat(layout).contains("active == 'patients'");
    }

    @Test
    void layout_dashboard_tab_still_present() throws IOException {
        String layout = readLayout();
        assertThat(layout)
                .describedAs("Dashboard tab must remain in primary nav")
                .contains("active == 'dashboard'");
    }

    // ── Inventory templates unchanged ────────────────────────────────────

    @Test
    void inventory_templates_still_use_inventory_active_key() throws IOException {
        // Spot-check: at least one inventory template should still use 'inventory'
        // (we didn't touch inventory templates — this guards against accidental edits)
        String listContent = read("inventory/list.html");
        assertThat(listContent)
                .describedAs("inventory/list.html should still pass 'inventory' as active key")
                .contains("'inventory'");
    }

    // ── Supplier bill form ───────────────────────────────────────────────

    @Test
    void supplier_bill_form_contains_new_supplier_shortcut_link() throws IOException {
        String form = read("supplier-bill/form.html");
        assertThat(form)
                .describedAs("Supplier bill form should have a '+New Supplier' link to /suppliers/new")
                .contains("/suppliers/new");
    }

    @Test
    void supplier_bill_form_has_new_supplier_banner_for_newSupplierName_attribute() throws IOException {
        String form = read("supplier-bill/form.html");
        assertThat(form)
                .describedAs("Supplier bill form should render a banner when newSupplierName is present")
                .contains("newSupplierName");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String readLayout() throws IOException {
        return read("fragments/layout.html");
    }

    private String read(String relativePath) throws IOException {
        Path path = Paths.get(TEMPLATES, relativePath);
        assertThat(path).describedAs("Template file must exist: %s", path).exists();
        return Files.readString(path);
    }
}
