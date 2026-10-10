package com.clinicops.app.service;

import com.clinicops.app.domain.*;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Apollo-style A4 prescription PDF.
 *
 * <p>Layout (top → bottom):
 * <ol>
 *   <li>Header  — Logo + Doctor (left) | Clinic details (right), solid rule below
 *   <li>Patient strip — Name/MRN/Phone (left) | Date/OPD (right), thin rule below
 *   <li>Vitals (drug allergies + recorded vitals) — shown when present
 *   <li>Chief Complaints (blue heading, bullets) — from visit.notes
 *   <li>Diagnosis (blue heading, bullets)
 *   <li>Medication Prescribed — table with M-N-E-N dosage column + legend
 *   <li>Diagnostic Tests — numbered list
 *   <li>Follow-up / Advice
 *   <li>Doctor signature (right) + Disclaimer
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class PrescriptionPdfService {

    private final FileStorageService fileStorage;

    // ── Palette ───────────────────────────────────────────────────────────────
    private static final Color APOLLO_BLUE  = new Color(0x1A, 0x4F, 0x8A); // headings, table header
    private static final Color BLUE_SOFT    = new Color(0xE8, 0xF0, 0xF8); // table header bg
    private static final Color BLUE_DARK    = new Color(0x0D, 0x2B, 0x55); // header rule top bar
    private static final Color TEXT_DARK    = new Color(0x11, 0x11, 0x11);
    private static final Color TEXT_MED     = new Color(0x44, 0x44, 0x44);
    private static final Color TEXT_GRAY    = new Color(0x77, 0x77, 0x77);
    private static final Color BORDER       = new Color(0xCC, 0xCC, 0xCC);
    private static final Color ZEBRA        = new Color(0xF8, 0xF8, 0xF8);
    private static final Color ALLERGY_RED  = new Color(0xBB, 0x11, 0x11);
    private static final Color ALLERGY_BG   = new Color(0xFF, 0xF0, 0xF0);
    private static final Color CHIP_BG      = new Color(0xEC, 0xF0, 0xF5);
    private static final Color NOTE_BG      = new Color(0xFF, 0xF8, 0xE1);
    private static final Color NOTE_BORDER  = new Color(0xE8, 0xC9, 0x5A);

    // ── Fonts ─────────────────────────────────────────────────────────────────
    private static final Font F_DOC_NAME   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  12f, APOLLO_BLUE);
    private static final Font F_DOC_QUAL   = FontFactory.getFont(FontFactory.HELVETICA,        9f, TEXT_MED);
    private static final Font F_DOC_REG    = FontFactory.getFont(FontFactory.HELVETICA,        8f, TEXT_GRAY);
    private static final Font F_CLINIC_NM  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   9f, TEXT_DARK);
    private static final Font F_CLINIC_SUB = FontFactory.getFont(FontFactory.HELVETICA,        8f, TEXT_MED);
    private static final Font F_CLINIC_CNT = FontFactory.getFont(FontFactory.HELVETICA,        8f, TEXT_GRAY);
    private static final Font F_PAT_NAME   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  10f, TEXT_DARK);
    private static final Font F_PAT_DETAIL = FontFactory.getFont(FontFactory.HELVETICA,        9f, TEXT_MED);
    private static final Font F_VISIT_LBL  = FontFactory.getFont(FontFactory.HELVETICA,        8f, TEXT_GRAY);
    private static final Font F_VISIT_VAL  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   9f, TEXT_DARK);
    private static final Font F_SEC_HDR    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  10f, APOLLO_BLUE);
    private static final Font F_BULLET     = FontFactory.getFont(FontFactory.HELVETICA,        9.5f, TEXT_DARK);
    private static final Font F_TH         = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   8.5f, APOLLO_BLUE);
    private static final Font F_MED_NAME   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   9f, TEXT_DARK);
    private static final Font F_MED_GEN    = FontFactory.getFont(FontFactory.HELVETICA,        7.5f, TEXT_GRAY);
    private static final Font F_DOSE       = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   9f, TEXT_DARK);
    private static final Font F_DOSE_LEGEND= FontFactory.getFont(FontFactory.HELVETICA,        7f, TEXT_GRAY);
    private static final Font F_CELL       = FontFactory.getFont(FontFactory.HELVETICA,        9f, TEXT_DARK);
    private static final Font F_CHIP       = FontFactory.getFont(FontFactory.HELVETICA,        7.5f, APOLLO_BLUE);
    private static final Font F_NOTE_CHIP  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   7.5f, new Color(0x7A, 0x60, 0x00));
    private static final Font F_DISCLAIMER = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 7f, TEXT_GRAY);
    private static final Font F_SIGN_NAME  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   9.5f, TEXT_DARK);
    private static final Font F_SIGN_SUB   = FontFactory.getFont(FontFactory.HELVETICA,         8f, TEXT_MED);
    private static final Font F_ALLERGY    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    8.5f, ALLERGY_RED);
    private static final Font F_ALLERGY_LBL= FontFactory.getFont(FontFactory.HELVETICA,         8f, ALLERGY_RED);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");
    private static final DateTimeFormatter D_FMT    = DateTimeFormatter.ofPattern("dd MMM yyyy");

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generate a prescription PDF.
     *
     * @param medicines  full Medicine objects keyed by medicine ID (replaces the old
     *                   {@code Map<Long,String>} so we have access to genericName + form)
     */
    public byte[] generate(Clinic clinic, Patient patient, Visit visit, Doctor doctor,
                           List<PrescriptionItem> rxItems,
                           Map<Long, Medicine> medicines,
                           List<InvestigationItem> investigations) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 42, 42, 36, 42);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new PageBorderHelper());
        doc.open();

        header(doc, clinic, doctor);
        patientStrip(doc, patient, visit);

        // Allergy + Vitals
        boolean hasAllergy = notBlank(patient.getAllergies());
        boolean hasVitals  = hasAnyVital(visit);
        if (hasAllergy || hasVitals) {
            allergyVitalsSection(doc, patient, visit, hasAllergy, hasVitals);
        }

        // Chief Complaints
        if (notBlank(visit.getNotes())) {
            sectionBlock(doc, "Chief Complaints", visit.getNotes());
        }

        // Diagnosis
        if (notBlank(visit.getDiagnosis())) {
            sectionBlock(doc, "Diagnosis / Provisional Diagnosis", visit.getDiagnosis());
        }

        // Medications
        boolean hasRx   = rxItems   != null && !rxItems.isEmpty();
        boolean hasText = notBlank(visit.getPrescriptionText());
        if (hasRx || hasText) {
            medicationSection(doc, rxItems, medicines, visit.getPrescriptionText());
        }

        // Investigations
        if (investigations != null && !investigations.isEmpty()) {
            investigationsSection(doc, investigations);
        }

        // Follow-up
        if (visit.getFollowUpDate() != null) {
            followUpSection(doc, visit);
        }

        // Signature + disclaimer
        bottomSection(doc, doctor, clinic);

        doc.close();
        return out.toByteArray();
    }

    // ── Page decorator ────────────────────────────────────────────────────────

    private static class PageBorderHelper extends PdfPageEventHelper {

        private static final BaseFont BF;
        static {
            BaseFont tmp = null;
            try { tmp = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false); }
            catch (Exception ignored) {}
            BF = tmp;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContentUnder();
            Rectangle pg = document.getPageSize();

            // Thin navy top rule
            cb.setColorFill(BLUE_DARK);
            cb.rectangle(0, pg.getHeight() - 6f, pg.getWidth(), 6f);
            cb.fill();

            // Thin navy bottom rule
            cb.rectangle(0, 0, pg.getWidth(), 4f);
            cb.fill();

            // Page number — bottom right
            if (BF != null) {
                String label = "Page " + writer.getPageNumber();
                cb.beginText();
                cb.setFontAndSize(BF, 7f);
                cb.setColorFill(TEXT_GRAY);
                float tw = cb.getEffectiveStringWidth(label, false);
                cb.setTextMatrix(pg.getWidth() - 48 - tw, 8f);
                cb.showText(label);
                cb.endText();
            }
        }
    }

    // ── Header: Doctor (left) | Clinic details (right) ───────────────────────

    private void header(Document doc, Clinic clinic, Doctor doctor) throws DocumentException {
        PdfPTable table = fw(2);
        table.setWidths(new float[]{50f, 50f});
        table.setSpacingAfter(0f);

        // ── Left: Logo + Doctor info ──────────────────────────────────────
        PdfPCell left = noBorder();
        left.setPaddingTop(10f);
        left.setPaddingBottom(8f);
        left.setPaddingLeft(4f);
        left.setPaddingRight(8f);

        boolean hasLogo = clinic != null && notBlank(clinic.getLogoPath());
        if (hasLogo) {
            try {
                Path p = fileStorage.resolve(clinic.getLogoPath());
                Image logo = Image.getInstance(p.toUri().toURL());
                logo.scaleToFit(48f, 48f);
                logo.setSpacingAfter(4f);
                left.addElement(logo);
            } catch (Exception ignored) {}
        }

        if (doctor != null) {
            left.addElement(new Paragraph("Dr. " + doctor.getFullName(), F_DOC_NAME));
            if (notBlank(doctor.getQualification())) {
                Paragraph q = new Paragraph(doctor.getQualification(), F_DOC_QUAL);
                q.setSpacingBefore(1f);
                left.addElement(q);
            }
            StringBuilder role = new StringBuilder();
            if (notBlank(doctor.getSpecialization())) role.append(doctor.getSpecialization());
            else role.append("General Practitioner");
            if (notBlank(doctor.getRegistrationNo()))
                role.append("\nReg. No. ").append(doctor.getRegistrationNo());
            Paragraph rp = new Paragraph(role.toString(), F_DOC_REG);
            rp.setSpacingBefore(2f);
            left.addElement(rp);
        }
        table.addCell(left);

        // ── Right: Clinic details ─────────────────────────────────────────
        PdfPCell right = noBorder();
        right.setPaddingTop(10f);
        right.setPaddingBottom(8f);
        right.setPaddingLeft(8f);
        right.setPaddingRight(4f);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);

        if (clinic != null) {
            Paragraph cn = new Paragraph(or(clinic.getName(), "CLINIC"), F_CLINIC_NM);
            cn.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(cn);

            if (notBlank(clinic.getTagline())) {
                Paragraph tg = new Paragraph(clinic.getTagline(), F_DOC_REG);
                tg.setAlignment(Element.ALIGN_RIGHT);
                tg.setSpacingBefore(1f);
                right.addElement(tg);
            }
            if (notBlank(clinic.getAddress())) {
                Paragraph addr = new Paragraph(clinic.getAddress(), F_CLINIC_SUB);
                addr.setAlignment(Element.ALIGN_RIGHT);
                addr.setSpacingBefore(3f);
                right.addElement(addr);
            }
            StringBuilder contact = new StringBuilder();
            if (notBlank(clinic.getPhone()))   contact.append("☎ ").append(clinic.getPhone());
            if (notBlank(clinic.getEmail()))   { if (!contact.isEmpty()) contact.append("  •  "); contact.append(clinic.getEmail()); }
            if (!contact.isEmpty()) {
                Paragraph cp = new Paragraph(contact.toString(), F_CLINIC_CNT);
                cp.setAlignment(Element.ALIGN_RIGHT);
                cp.setSpacingBefore(1f);
                right.addElement(cp);
            }
            if (notBlank(clinic.getLicenseNo())) {
                Paragraph lr = new Paragraph("Lic: " + clinic.getLicenseNo(), F_CLINIC_CNT);
                lr.setAlignment(Element.ALIGN_RIGHT);
                right.addElement(lr);
            }
        }
        table.addCell(right);
        doc.add(table);

        // Solid navy rule
        doc.add(hRule(BLUE_DARK, 1.5f, 0f));
    }

    // ── Patient strip: Name/MRN (left) | Date/OPD (right) ────────────────────

    private static void patientStrip(Document doc, Patient patient, Visit visit)
            throws DocumentException {
        // Light blue tint background
        PdfPTable bg = fw(2);
        bg.setWidths(new float[]{55f, 45f});
        bg.setSpacingBefore(0f);
        bg.setSpacingAfter(0f);

        // Left — patient details
        PdfPCell left = new PdfPCell();
        left.setBackgroundColor(BLUE_SOFT);
        left.setBorder(Rectangle.NO_BORDER);
        left.setPaddingTop(8f); left.setPaddingBottom(8f);
        left.setPaddingLeft(8f); left.setPaddingRight(6f);

        String nameStr = or(patient.getName(), "—");
        StringBuilder patLine = new StringBuilder(nameStr);
        if (patient.getGender() != null) patLine.append(", ").append(patient.getGender());
        if (patient.getAge()    != null) patLine.append(", ").append(patient.getAge()).append(" Yrs");
        left.addElement(new Paragraph(patLine.toString(), F_PAT_NAME));

        if (notBlank(patient.getPhone())) {
            Paragraph ph = new Paragraph("Mobile: " + patient.getPhone(), F_PAT_DETAIL);
            ph.setSpacingBefore(2f);
            left.addElement(ph);
        }
        if (notBlank(patient.getMrn())) {
            Paragraph mrn = new Paragraph("MRN: " + patient.getMrn(), F_PAT_DETAIL);
            mrn.setSpacingBefore(1f);
            left.addElement(mrn);
        }
        bg.addCell(left);

        // Right — visit date / OPD
        PdfPCell right = new PdfPCell();
        right.setBackgroundColor(BLUE_SOFT);
        right.setBorder(Rectangle.NO_BORDER);
        right.setBorderWidthLeft(0.5f);
        right.setBorderColorLeft(BORDER);
        right.setPaddingTop(8f); right.setPaddingBottom(8f);
        right.setPaddingLeft(10f); right.setPaddingRight(8f);

        String dateStr = visit.getVisitDate()
                .atZone(ZoneId.systemDefault()).format(DATE_FMT);
        String timeStr = visit.getVisitDate()
                .atZone(ZoneId.systemDefault()).format(TIME_FMT);

        addLabelValue(right, "Date:", dateStr);
        addLabelValue(right, "Time:", timeStr);
        if (notBlank(visit.getEncounterType())) {
            addLabelValue(right, "Consult Type:", visit.getEncounterType());
        }
        if (notBlank(visit.getEncounterNo())) {
            addLabelValue(right, "OPD No.:", visit.getEncounterNo());
        }
        bg.addCell(right);

        doc.add(bg);
        doc.add(hRule(BORDER, 0.5f, 0f));
    }

    private static void addLabelValue(PdfPCell cell, String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", F_VISIT_LBL));
        p.add(new Chunk(value, F_VISIT_VAL));
        p.setSpacingBefore(2f);
        cell.addElement(p);
    }

    // ── Allergy + Vitals bar ──────────────────────────────────────────────────

    private static void allergyVitalsSection(Document doc, Patient patient, Visit visit,
                                             boolean hasAllergy, boolean hasVitals)
            throws DocumentException {
        if (hasAllergy) {
            PdfPTable bar = fw(1);
            bar.setSpacingBefore(4f);
            bar.setSpacingAfter(0f);
            PdfPCell cell = new PdfPCell();
            cell.setBackgroundColor(ALLERGY_BG);
            cell.setBorder(Rectangle.BOX);
            cell.setBorderColor(new Color(0xE5, 0xA0, 0xA0));
            cell.setBorderWidth(0.5f);
            cell.setPaddingTop(6f); cell.setPaddingBottom(6f);
            cell.setPaddingLeft(10f); cell.setPaddingRight(10f);

            Paragraph p = new Paragraph();
            p.add(new Chunk("⚠  Drug Allergies: ", F_ALLERGY));
            p.add(new Chunk(patient.getAllergies(), F_ALLERGY_LBL));
            cell.addElement(p);
            bar.addCell(cell);
            doc.add(bar);
        }

        if (hasVitals) {
            // Compact single-line vitals row
            StringBuilder vb = new StringBuilder("Vitals: ");
            Visit v = visit;
            if (v.getVitalBpSystolic()   != null) append(vb, "BP " + v.getVitalBpSystolic() + "/" + v.getVitalBpDiastolic() + " mmHg");
            if (v.getVitalPulse()        != null) append(vb, "Pulse " + v.getVitalPulse() + " bpm");
            if (v.getVitalTemperatureF() != null) append(vb, "Temp " + v.getVitalTemperatureF() + "°F");
            if (v.getVitalSpo2()         != null) append(vb, "SpO₂ " + v.getVitalSpo2() + "%");
            if (v.getVitalWeightKg()     != null) append(vb, "Wt " + v.getVitalWeightKg() + " kg");
            if (v.getVitalHeightCm()     != null) append(vb, "Ht " + v.getVitalHeightCm() + " cm");
            if (v.getVitalBloodGlucose() != null) {
                String t = v.getVitalBloodGlucoseType() == null ? "" : "(" + v.getVitalBloodGlucoseType() + ") ";
                append(vb, "Glucose " + t + v.getVitalBloodGlucose() + " mg/dL");
            }

            Paragraph vp = new Paragraph(vb.toString(),
                    FontFactory.getFont(FontFactory.HELVETICA, 8.5f, TEXT_MED));
            vp.setSpacingBefore(4f);
            doc.add(vp);
        }

        doc.add(hRule(BORDER, 0.5f, 3f));
    }

    // ── Generic section: heading + bullet text ────────────────────────────────

    private static void sectionBlock(Document doc, String heading, String content)
            throws DocumentException {
        Paragraph h = new Paragraph(heading, F_SEC_HDR);
        h.setSpacingBefore(8f);
        h.setSpacingAfter(3f);
        doc.add(h);

        // Split on newlines / semicolons for bullet points
        String[] lines = content.split("[\\n;]+");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Paragraph bp = new Paragraph("•  " + line, F_BULLET);
            bp.setIndentationLeft(10f);
            bp.setSpacingBefore(2f);
            doc.add(bp);
        }

        doc.add(hRule(BORDER, 0.3f, 4f));
    }

    // ── Medication table ──────────────────────────────────────────────────────

    private static void medicationSection(Document doc, List<PrescriptionItem> rxItems,
                                          Map<Long, Medicine> medicines, String fallbackText)
            throws DocumentException {
        Paragraph h = new Paragraph("Medication Prescribed", F_SEC_HDR);
        h.setSpacingBefore(8f);
        h.setSpacingAfter(4f);
        doc.add(h);

        boolean hasStructured = rxItems != null && !rxItems.isEmpty();

        if (hasStructured) {
            // Columns: # | Medicine Name | Dosage | Medicine Details | Duration
            PdfPTable t = fw(5);
            t.setWidths(new float[]{5f, 34f, 13f, 33f, 15f});
            t.setHeaderRows(1);

            t.addCell(thCell("#"));
            t.addCell(thCell("Medicine Name"));
            t.addCell(thCell("Dosage"));
            t.addCell(thCell("Medicine Details"));
            t.addCell(thCell("Duration"));

            int idx = 1;
            for (PrescriptionItem item : rxItems) {
                boolean zebra = idx % 2 == 0;
                Color bg = zebra ? ZEBRA : Color.WHITE;

                // #
                t.addCell(dataCell(String.valueOf(idx++), bg, Element.ALIGN_CENTER, false));

                // Medicine Name + "Contains: generic (strength)"
                PdfPCell nameCell = new PdfPCell();
                nameCell.setBackgroundColor(bg);
                nameCell.setBorder(Rectangle.BOX);
                nameCell.setBorderColor(BORDER);
                nameCell.setBorderWidth(0.3f);
                nameCell.setPaddingTop(6f); nameCell.setPaddingBottom(6f);
                nameCell.setPaddingLeft(7f); nameCell.setPaddingRight(6f);

                String displayName;
                if (item.getMedicineId() != null && medicines.containsKey(item.getMedicineId())) {
                    Medicine m = medicines.get(item.getMedicineId());
                    displayName = m.getName() != null ? m.getName() : "";
                    if (notBlank(m.getStrength())) displayName += " " + m.getStrength();
                    nameCell.addElement(new Paragraph(displayName, F_MED_NAME));
                    if (notBlank(m.getGenericName())) {
                        String generic = "Contains: " + m.getGenericName().toUpperCase()
                                + (notBlank(m.getStrength()) ? " (" + m.getStrength().toUpperCase() + ")" : "");
                        Paragraph gp = new Paragraph(generic, F_MED_GEN);
                        gp.setSpacingBefore(2f);
                        nameCell.addElement(gp);
                    }
                } else {
                    displayName = or(item.getCustomName(), "—");
                    nameCell.addElement(new Paragraph(displayName, F_MED_NAME));
                }
                t.addCell(nameCell);

                // Dosage — M-N-E-N pattern
                PdfPCell doseCell = new PdfPCell();
                doseCell.setBackgroundColor(bg);
                doseCell.setBorder(Rectangle.BOX);
                doseCell.setBorderColor(BORDER);
                doseCell.setBorderWidth(0.3f);
                doseCell.setPaddingTop(6f); doseCell.setPaddingBottom(6f);
                doseCell.setPaddingLeft(5f); doseCell.setPaddingRight(5f);
                doseCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                if (notBlank(item.getDosage())) {
                    Paragraph dp = new Paragraph(item.getDosage(), F_DOSE);
                    dp.setAlignment(Element.ALIGN_CENTER);
                    doseCell.addElement(dp);
                    Paragraph legend = new Paragraph("M-N-E-N", F_DOSE_LEGEND);
                    legend.setAlignment(Element.ALIGN_CENTER);
                    legend.setSpacingBefore(2f);
                    doseCell.addElement(legend);
                } else {
                    doseCell.addElement(new Paragraph("—", F_CELL));
                }
                t.addCell(doseCell);

                // Medicine Details: FORM | Frequency\nRoute. Instructions
                PdfPCell detailCell = new PdfPCell();
                detailCell.setBackgroundColor(bg);
                detailCell.setBorder(Rectangle.BOX);
                detailCell.setBorderColor(BORDER);
                detailCell.setBorderWidth(0.3f);
                detailCell.setPaddingTop(6f); detailCell.setPaddingBottom(6f);
                detailCell.setPaddingLeft(7f); detailCell.setPaddingRight(6f);

                StringBuilder details = new StringBuilder();
                String form = "";
                if (item.getMedicineId() != null && medicines.containsKey(item.getMedicineId())) {
                    Medicine m = medicines.get(item.getMedicineId());
                    if (notBlank(m.getForm())) form = m.getForm().toUpperCase();
                }
                if (!form.isEmpty()) details.append(form);
                if (notBlank(item.getFrequency())) {
                    if (!details.isEmpty()) details.append(" | ");
                    details.append(item.getFrequency());
                }
                String line2 = "";
                if (notBlank(item.getInstructions())) line2 = "Orally. " + item.getInstructions();

                if (!details.isEmpty()) {
                    detailCell.addElement(new Paragraph(details.toString(), F_CELL));
                }
                if (!line2.isEmpty()) {
                    Paragraph l2 = new Paragraph(line2, F_MED_GEN);
                    l2.setSpacingBefore(2f);
                    detailCell.addElement(l2);
                }
                if (details.isEmpty() && line2.isEmpty()) {
                    detailCell.addElement(new Paragraph("—", F_CELL));
                }
                t.addCell(detailCell);

                // Duration
                t.addCell(dataCell(or(item.getDuration(), "—"), bg, Element.ALIGN_CENTER, false));
            }
            doc.add(t);

            // Legend chips
            legendBar(doc);

        } else if (notBlank(fallbackText)) {
            Paragraph fp = new Paragraph(fallbackText,
                    FontFactory.getFont(FontFactory.HELVETICA, 9.5f, TEXT_DARK));
            fp.setSpacingBefore(2f);
            doc.add(fp);
        }

        doc.add(hRule(BORDER, 0.3f, 4f));
    }

    private static void legendBar(Document doc) throws DocumentException {
        PdfPTable bar = fw(3);
        bar.setWidths(new float[]{40f, 20f, 40f});
        bar.setSpacingBefore(4f);
        bar.setSpacingAfter(0f);

        // M-N-E-N chip
        PdfPCell mnen = chipCell("M-N-E-N  Morning – Noon – Evening – Night");
        mnen.setHorizontalAlignment(Element.ALIGN_LEFT);
        bar.addCell(mnen);

        // Instruction chip
        PdfPCell inst = chipCell("⊙ Instruction");
        inst.setHorizontalAlignment(Element.ALIGN_CENTER);
        bar.addCell(inst);

        // Note
        PdfPCell note = new PdfPCell();
        note.setBorder(Rectangle.NO_BORDER);
        note.setBackgroundColor(NOTE_BG);
        note.setBorder(Rectangle.BOX);
        note.setBorderColor(NOTE_BORDER);
        note.setBorderWidth(0.4f);
        note.setPaddingTop(4f); note.setPaddingBottom(4f);
        note.setPaddingLeft(8f); note.setPaddingRight(6f);
        Paragraph np = new Paragraph("NOTE: Medicine Substitution Allowed Wherever Applicable.", F_NOTE_CHIP);
        note.addElement(np);
        bar.addCell(note);

        doc.add(bar);
    }

    private static PdfPCell chipCell(String text) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(new Color(0xA0, 0xB8, 0xD5));
        c.setBorderWidth(0.4f);
        c.setBackgroundColor(CHIP_BG);
        c.setPaddingTop(4f); c.setPaddingBottom(4f);
        c.setPaddingLeft(8f); c.setPaddingRight(6f);
        c.addElement(new Paragraph(text, F_CHIP));
        return c;
    }

    // ── Investigations ────────────────────────────────────────────────────────

    private static void investigationsSection(Document doc, List<InvestigationItem> items)
            throws DocumentException {
        Paragraph h = new Paragraph("Diagnostic Tests", F_SEC_HDR);
        h.setSpacingBefore(8f);
        h.setSpacingAfter(3f);
        doc.add(h);

        int idx = 1;
        for (InvestigationItem item : items) {
            StringBuilder line = new StringBuilder(idx++ + ". ");
            line.append(item.getName() != null ? item.getName().toUpperCase() : "—");
            if (notBlank(item.getInstructions())) line.append(" – ").append(item.getInstructions());
            Paragraph p = new Paragraph(line.toString(), F_BULLET);
            p.setIndentationLeft(10f);
            p.setSpacingBefore(2f);
            doc.add(p);
        }

        doc.add(hRule(BORDER, 0.3f, 4f));
    }

    // ── Follow-up / Advice ────────────────────────────────────────────────────

    private static void followUpSection(Document doc, Visit visit) throws DocumentException {
        Paragraph h = new Paragraph("Advice & Instructions", F_SEC_HDR);
        h.setSpacingBefore(8f);
        h.setSpacingAfter(3f);
        doc.add(h);

        if (visit.getFollowUpDate() != null) {
            Paragraph fp = new Paragraph(
                    "•  Follow up on " + visit.getFollowUpDate().format(D_FMT), F_BULLET);
            fp.setIndentationLeft(10f);
            doc.add(fp);
        }

        doc.add(hRule(BORDER, 0.3f, 4f));
    }

    // ── Bottom: Signature + Disclaimer ───────────────────────────────────────

    private static void bottomSection(Document doc, Doctor doctor, Clinic clinic)
            throws DocumentException {
        PdfPTable row = fw(2);
        row.setWidths(new float[]{55f, 45f});
        row.setSpacingBefore(12f);

        // Left: disclaimer text
        PdfPCell left = noBorder();
        left.setPaddingTop(4f);
        Paragraph disc = new Paragraph(
                "Disclaimer:\nThis prescription was generated digitally"
                + (doctor != null ? " by Dr. " + doctor.getFullName() : "")
                + ". It is valid from the date of issue until the specific "
                + "period / dosage of each medicine as advised.",
                F_DISCLAIMER);
        left.addElement(disc);
        if (clinic != null && notBlank(clinic.getFooterNote())) {
            Paragraph fn = new Paragraph(clinic.getFooterNote(), F_DISCLAIMER);
            fn.setSpacingBefore(3f);
            left.addElement(fn);
        }
        row.addCell(left);

        // Right: Doctor signature block
        PdfPCell right = noBorder();
        right.setPaddingTop(4f);

        PdfPTable sig = new PdfPTable(1);
        sig.setWidthPercentage(85f);
        sig.setHorizontalAlignment(Element.ALIGN_RIGHT);

        PdfPCell space = new PdfPCell(new Phrase(" "));
        space.setBorder(Rectangle.NO_BORDER);
        space.setFixedHeight(26f);
        sig.addCell(space);

        PdfPCell sigLine = new PdfPCell(new Phrase(" "));
        sigLine.setBorder(Rectangle.TOP);
        sigLine.setBorderColor(TEXT_MED);
        sigLine.setBorderWidth(0.7f);
        sigLine.setFixedHeight(3f);
        sig.addCell(sigLine);

        if (doctor != null) {
            PdfPCell nm = new PdfPCell(new Paragraph("Dr. " + doctor.getFullName(), F_SIGN_NAME));
            nm.setBorder(Rectangle.NO_BORDER);
            nm.setHorizontalAlignment(Element.ALIGN_RIGHT);
            nm.setPaddingTop(2f);
            sig.addCell(nm);

            if (notBlank(doctor.getQualification())) {
                PdfPCell qc = new PdfPCell(new Paragraph(doctor.getQualification(), F_SIGN_SUB));
                qc.setBorder(Rectangle.NO_BORDER);
                qc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                sig.addCell(qc);
            }
            if (notBlank(doctor.getRegistrationNo())) {
                PdfPCell rc = new PdfPCell(new Paragraph("Reg: " + doctor.getRegistrationNo(), F_SIGN_SUB));
                rc.setBorder(Rectangle.NO_BORDER);
                rc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                sig.addCell(rc);
            }
            PdfPCell auth = new PdfPCell(new Paragraph("Authorised Signatory",
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 7.5f, TEXT_GRAY)));
            auth.setBorder(Rectangle.NO_BORDER);
            auth.setHorizontalAlignment(Element.ALIGN_RIGHT);
            sig.addCell(auth);
        }
        right.addElement(sig);
        row.addCell(right);
        doc.add(row);
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private static PdfPTable fw(int cols) {
        PdfPTable t = new PdfPTable(cols);
        t.setWidthPercentage(100f);
        t.setSpacingBefore(0f);
        t.setSpacingAfter(0f);
        return t;
    }

    private static PdfPCell noBorder() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(2f);
        return c;
    }

    private static PdfPTable hRule(Color color, float width, float spacingBefore)
            throws DocumentException {
        PdfPTable t = fw(1);
        t.setSpacingBefore(spacingBefore);
        t.setSpacingAfter(0f);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBackgroundColor(color);
        c.setFixedHeight(width);
        c.setBorder(Rectangle.NO_BORDER);
        t.addCell(c);
        return t;
    }

    private static PdfPCell thCell(String text) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(BLUE_SOFT);
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(new Color(0xA0, 0xB8, 0xD5));
        c.setBorderWidth(0.5f);
        c.setPaddingTop(6f); c.setPaddingBottom(6f);
        c.setPaddingLeft(7f); c.setPaddingRight(7f);
        c.addElement(new Paragraph(text, F_TH));
        return c;
    }

    private static PdfPCell dataCell(String text, Color bg, int align, boolean bold) {
        Font f = bold ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f, TEXT_DARK) : F_CELL;
        PdfPCell c = new PdfPCell(new Paragraph(text, f));
        c.setBackgroundColor(bg);
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(BORDER);
        c.setBorderWidth(0.3f);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPaddingTop(6f); c.setPaddingBottom(6f);
        c.setPaddingLeft(7f); c.setPaddingRight(7f);
        return c;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String  or(String s, String fallback) { return notBlank(s) ? s : fallback; }

    private static void append(StringBuilder sb, String s) {
        if (!sb.isEmpty()) sb.append("  |  ");
        sb.append(s);
    }

    private static boolean hasAnyVital(Visit v) {
        return v.getVitalBpSystolic()   != null || v.getVitalPulse()    != null
            || v.getVitalTemperatureF() != null || v.getVitalSpo2()     != null
            || v.getVitalWeightKg()     != null || v.getVitalHeightCm() != null
            || v.getVitalBloodGlucose() != null;
    }
}
