package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.clinicops.app.domain.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lightweight render smoke test — instantiates the PDF services directly (no
 * Spring context) and verifies the output is a non-empty, well-formed PDF
 * (starts with %PDF, ends with %%EOF). Confirms the new bordered-card layout
 * renders without throwing.
 *
 * Set the system property {@code -DkeepPdf=true} to also write the artifacts
 * under {@code target/pdf-smoke/} for manual inspection.
 */
class PdfRenderSmokeTest {

    @Test
    void prescription_renders_valid_pdf() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        PrescriptionPdfService svc = new PrescriptionPdfService(storage);

        Clinic clinic = Clinic.builder()
                .name("Demo Clinic")
                .tagline("Compassionate care, every day")
                .address("12 MG Road, Bengaluru 560001")
                .phone("9876543210")
                .email("hello@demo.example")
                .website("demo.example")
                .licenseNo("KA-123/2024")
                .gstin("29ABCDE1234F1Z5")
                .footerNote("Powered by ClinicOps")
                .build();

        Patient patient = Patient.builder()
                .name("Asha Kumar")
                .phone("9876500001")
                .mrn("MRN-2026-00042")
                .age(34)
                .gender("F")
                .allergies("Penicillin, sulfa drugs")
                .build();

        Visit visit = Visit.builder()
                .encounterNo("OPD-2026-00088")
                .visitDate(Instant.now())
                .diagnosis("Acute pharyngitis with low-grade fever; rule out streptococcal infection.")
                .notes("Throat congested, no exudate. Advised hydration and rest. Review in 3 days if not improving.")
                .vitalBpSystolic(124)
                .vitalBpDiastolic(82)
                .vitalPulse(88)
                .vitalTemperatureF(new BigDecimal("100.4"))
                .vitalSpo2(98)
                .vitalRespiratoryRate(18)
                .vitalWeightKg(new BigDecimal("62.5"))
                .vitalHeightCm(new BigDecimal("162.0"))
                .vitalBloodGlucose(110)
                .vitalBloodGlucoseType("Random")
                .followUpDate(LocalDate.now().plusDays(3))
                .build();

        Doctor doctor = Doctor.builder()
                .fullName("Ramesh Iyer")
                .qualification("MBBS, MD (General Medicine)")
                .registrationNo("KMC-44521")
                .specialization("Internal Medicine")
                .build();

        PrescriptionItem rx1 = PrescriptionItem.builder()
                .medicineId(1L).dosage("1-0-1").duration("5d")
                .frequency("After food").instructions("Take with warm water").build();
        PrescriptionItem rx2 = PrescriptionItem.builder()
                .medicineId(2L).dosage("0-0-1").duration("3d")
                .frequency("Before bed").build();
        PrescriptionItem rxCustom = PrescriptionItem.builder()
                .customName("Saline gargles").dosage("3 times/day").duration("5d").build();

        InvestigationItem inv1 = InvestigationItem.builder()
                .name("CBC").instructions("Fasting not required").build();
        InvestigationItem inv2 = InvestigationItem.builder()
                .name("Throat swab culture").build();

        Medicine med1 = Medicine.builder()
                .id(1L).name("Paracetamol").genericName("Paracetamol").strength("650mg")
                .form("TABLET").unit("STRIP").lowStockThreshold(10).build();
        Medicine med2 = Medicine.builder()
                .id(2L).name("Cetirizine").genericName("Cetirizine Hydrochloride").strength("10mg")
                .form("TABLET").unit("STRIP").lowStockThreshold(10).build();

        byte[] pdf = svc.generate(clinic, patient, visit, doctor,
                List.of(rx1, rx2, rxCustom),
                Map.of(1L, med1, 2L, med2),
                List.of(inv1, inv2));

        assertValidPdf(pdf);
        maybeWrite(pdf, "prescription.pdf");
    }

    @Test
    void sale_receipt_renders_valid_pdf() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        SaleReceiptPdfService svc = new SaleReceiptPdfService(storage);

        Clinic clinic = Clinic.builder()
                .name("Demo Clinic")
                .address("12 MG Road, Bengaluru 560001")
                .phone("9876543210")
                .gstin("29ABCDE1234F1Z5")
                .licenseNo("KA-123/2024")
                .footerNote("Bill issued under Drug License KA-123/2024")
                .build();

        Patient patient = Patient.builder()
                .name("Asha Kumar")
                .mrn("MRN-2026-00042")
                .phone("9876500001")
                .age(34)
                .gender("F")
                .build();

        Sale sale = Sale.builder()
                .billNo("BILL-2026-00021")
                .createdAt(Instant.now())
                .subtotalAmount(new BigDecimal("285.00"))
                .billDiscountType("PERCENT")
                .billDiscountValue(new BigDecimal("10"))
                .billDiscountAmount(new BigDecimal("28.50"))
                .totalAmount(new BigDecimal("256.50"))
                .paymentMode("UPI")
                .build();

        SaleItem item1 = SaleItem.builder()
                .medicineId(1L).quantity(10)
                .unitPrice(new BigDecimal("12.50"))
                .grossAmount(new BigDecimal("125.00"))
                .discountAmount(BigDecimal.ZERO)
                .lineTotal(new BigDecimal("125.00")).build();
        SaleItem item2 = SaleItem.builder()
                .medicineId(2L).quantity(8)
                .unitPrice(new BigDecimal("20.00"))
                .grossAmount(new BigDecimal("160.00"))
                .discountType("AMOUNT").discountValue(new BigDecimal("10"))
                .discountAmount(new BigDecimal("10.00"))
                .lineTotal(new BigDecimal("150.00")).build();

        byte[] pdf = svc.generate(clinic, sale, patient,
                List.of(item1, item2),
                Map.of(1L, "Paracetamol 650mg", 2L, "Cetirizine 10mg"));

        assertValidPdf(pdf);
        maybeWrite(pdf, "sale-receipt.pdf");
    }

    @Test
    void sale_receipt_walkin_renders_valid_pdf() {
        FileStorageService storage = mock(FileStorageService.class);
        SaleReceiptPdfService svc = new SaleReceiptPdfService(storage);

        Clinic clinic = Clinic.builder().name("Demo Clinic").build();
        Sale sale = Sale.builder()
                .billNo("BILL-2026-00022")
                .createdAt(Instant.now())
                .walkInName("Rajesh Walk-in")
                .walkInPhone("9876500099")
                .subtotalAmount(new BigDecimal("50.00"))
                .billDiscountAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("50.00"))
                .paymentMode("CASH")
                .build();
        SaleItem item = SaleItem.builder()
                .medicineId(1L).quantity(5)
                .unitPrice(new BigDecimal("10.00"))
                .grossAmount(new BigDecimal("50.00"))
                .discountAmount(BigDecimal.ZERO)
                .lineTotal(new BigDecimal("50.00")).build();

        byte[] pdf = svc.generate(clinic, sale, null, List.of(item),
                Map.of(1L, "Aspirin 75mg"));

        assertValidPdf(pdf);
    }

    // ---- helpers ---------------------------------------------------------

    private static void assertValidPdf(byte[] pdf) {
        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(1000);
        // %PDF-...
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // PDF trailer
        String tail = new String(pdf, Math.max(0, pdf.length - 32), Math.min(32, pdf.length));
        assertThat(tail).contains("%%EOF");
    }

    private static void maybeWrite(byte[] pdf, String name) throws Exception {
        if (!Boolean.getBoolean("keepPdf")) return;
        Path dir = Path.of("target", "pdf-smoke");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), pdf);
    }
}
