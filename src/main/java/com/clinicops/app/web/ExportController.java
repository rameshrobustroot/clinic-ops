package com.clinicops.app.web;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.Supplier;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.PatientRepository;
import com.clinicops.app.repository.SaleRepository;
import com.clinicops.app.repository.SupplierRepository;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.util.CsvWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Owner-only CSV exports — backup + offline analysis. Each endpoint streams a CSV with
 * UTF-8 BOM (so Excel recognises Unicode), one row per record, scoped to the caller's clinic.
 */
@Controller
@RequestMapping("/export")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class ExportController {

    private final PatientRepository patientRepository;
    private final SaleRepository saleRepository;
    private final MedicineRepository medicineRepository;
    private final SupplierRepository supplierRepository;
    private final CurrentUser currentUser;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int PAGE_SIZE = 1000;

    @GetMapping("/patients.csv")
    public void patients(HttpServletResponse response) throws IOException {
        prepare(response, "patients");
        try (PrintWriter out = response.getWriter()) {
            writeBom(out);
            CsvWriter.writeRow(out, List.of("MRN", "Name", "Phone", "Age", "Gender", "Address", "Allergies", "Created"));
            int page = 0;
            org.springframework.data.domain.Page<Patient> p;
            do {
                p = patientRepository.findAll(PageRequest.of(page, PAGE_SIZE, Sort.by("id")));
                for (Patient patient : p.getContent()) {
                    if (!currentUser.clinicId().equals(patient.getClinicId())) continue;
                    CsvWriter.writeRow(out, List.of(
                            nullToEmpty(patient.getMrn()),
                            nullToEmpty(patient.getName()),
                            nullToEmpty(patient.getPhone()),
                            patient.getAge() == null ? "" : patient.getAge(),
                            nullToEmpty(patient.getGender()),
                            nullToEmpty(patient.getAddress()),
                            nullToEmpty(patient.getAllergies()),
                            patient.getCreatedAt() == null ? "" : formatInstant(patient.getCreatedAt())));
                }
                page++;
            } while (p.hasNext());
        }
    }

    @GetMapping("/sales.csv")
    public void sales(HttpServletResponse response) throws IOException {
        prepare(response, "sales");
        try (PrintWriter out = response.getWriter()) {
            writeBom(out);
            CsvWriter.writeRow(out, List.of("Bill No", "Date", "Patient/Walk-in", "Subtotal", "Bill Discount",
                    "Total", "Payment Mode"));
            int page = 0;
            org.springframework.data.domain.Page<Sale> p;
            do {
                p = saleRepository.findByClinicIdOrderByCreatedAtDesc(
                        currentUser.clinicId(), PageRequest.of(page, PAGE_SIZE));
                for (Sale s : p.getContent()) {
                    String who = s.getPatientId() != null ? "Patient#" + s.getPatientId()
                            : (s.getWalkInName() != null ? s.getWalkInName() + " (walk-in)" : "Walk-in");
                    CsvWriter.writeRow(out, List.of(
                            nullToEmpty(s.getBillNo()),
                            s.getCreatedAt() == null ? "" : formatInstant(s.getCreatedAt()),
                            who,
                            s.getSubtotalAmount() == null ? "" : s.getSubtotalAmount(),
                            s.getBillDiscountAmount() == null ? "0" : s.getBillDiscountAmount(),
                            s.getTotalAmount() == null ? "" : s.getTotalAmount(),
                            nullToEmpty(s.getPaymentMode())));
                }
                page++;
            } while (p.hasNext());
        }
    }

    @GetMapping("/inventory.csv")
    public void inventory(HttpServletResponse response) throws IOException {
        prepare(response, "inventory");
        try (PrintWriter out = response.getWriter()) {
            writeBom(out);
            CsvWriter.writeRow(out, List.of("Name", "Generic", "Strength", "Manufacturer", "Form", "Unit", "Low-stock threshold", "Active"));
            for (Medicine m : medicineRepository.findByClinicIdOrderByNameAsc(currentUser.clinicId())) {
                CsvWriter.writeRow(out, List.of(
                        nullToEmpty(m.getName()),
                        nullToEmpty(m.getGenericName()),
                        nullToEmpty(m.getStrength()),
                        nullToEmpty(m.getManufacturer()),
                        nullToEmpty(m.getForm()),
                        nullToEmpty(m.getUnit()),
                        m.getLowStockThreshold() == null ? "" : m.getLowStockThreshold(),
                        Boolean.TRUE.equals(m.getActive()) ? "yes" : "no"));
            }
        }
    }

    @GetMapping("/suppliers.csv")
    public void suppliers(HttpServletResponse response) throws IOException {
        prepare(response, "suppliers");
        try (PrintWriter out = response.getWriter()) {
            writeBom(out);
            CsvWriter.writeRow(out, List.of("Name", "Phone", "Address", "Bank Acc", "IFSC", "UPI", "GSTIN"));
            for (Supplier s : supplierRepository.findByClinicIdOrderByNameAsc(currentUser.clinicId())) {
                CsvWriter.writeRow(out, List.of(
                        nullToEmpty(s.getName()),
                        nullToEmpty(s.getPhone()),
                        nullToEmpty(s.getAddress()),
                        nullToEmpty(s.getBankAccountNumber()),
                        nullToEmpty(s.getBankIfsc()),
                        nullToEmpty(s.getUpiId()),
                        nullToEmpty(s.getGstin())));
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private static void prepare(HttpServletResponse response, String name) {
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"clinicops-" + name + "-"
                        + java.time.LocalDate.now() + ".csv\"");
    }

    private static void writeBom(PrintWriter out) {
        // BOM so Excel auto-detects UTF-8
        out.write('\uFEFF');
    }

    private static String formatInstant(java.time.Instant i) {
        return i.atZone(ZoneId.systemDefault()).format(DATE_FMT);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
