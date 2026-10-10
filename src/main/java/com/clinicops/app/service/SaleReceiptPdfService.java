package com.clinicops.app.service;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.domain.Patient;
import com.clinicops.app.domain.Sale;
import com.clinicops.app.domain.SaleItem;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Apollo-style A4 pharmacy bill / sale receipt PDF.
 *
 * <p>Visual language matches {@link PrescriptionPdfService}:
 * thin navy top + bottom bars, clinic header (left) vs bill-number block (right),
 * soft-blue patient strip, blue section headings as text, clean white item table,
 * right-aligned totals with a navy grand-total highlight row.
 */
@Service
@RequiredArgsConstructor
public class SaleReceiptPdfService {

    private final FileStorageService fileStorage;

    // ── Palette (identical to PrescriptionPdfService) ─────────────────────────
    private static final Color APOLLO_BLUE  = new Color(0x1A, 0x4F, 0x8A);
    private static final Color BLUE_SOFT    = new Color(0xE8, 0xF0, 0xF8);
    private static final Color BLUE_DARK    = new Color(0x0D, 0x2B, 0x55);
    private static final Color TEXT_DARK    = new Color(0x11, 0x11, 0x11);
    private static final Color TEXT_MED     = new Color(0x44, 0x44, 0x44);
    private static final Color TEXT_GRAY    = new Color(0x77, 0x77, 0x77);
    private static final Color BORDER       = new Color(0xCC, 0xCC, 0xCC);
    private static final Color ZEBRA        = new Color(0xF8, 0xF8, 0xF8);
    private static final Color DISCOUNT_CLR = new Color(0x9A, 0x34, 0x12); // rust for discounts

    // ── Fonts ─────────────────────────────────────────────────────────────────
    private static final Font F_CLINIC_NM  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  12f, APOLLO_BLUE);
    private static final Font F_CLINIC_TAG = FontFactory.getFont(FontFactory.HELVETICA,         8.5f, TEXT_MED);
    private static final Font F_CLINIC_SUB = FontFactory.getFont(FontFactory.HELVETICA,         8f, TEXT_GRAY);
    private static final Font F_BILL_LBL   = FontFactory.getFont(FontFactory.HELVETICA,         8f, TEXT_GRAY);
    private static final Font F_BILL_NO    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   18f, APOLLO_BLUE);
    private static final Font F_BILL_DATE  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9f, TEXT_DARK);
    private static final Font F_PAT_NAME   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   10f, TEXT_DARK);
    private static final Font F_PAT_DETAIL = FontFactory.getFont(FontFactory.HELVETICA,         9f, TEXT_MED);
    private static final Font F_LABEL      = FontFactory.getFont(FontFactory.HELVETICA,         8f, TEXT_GRAY);
    private static final Font F_VALUE      = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9f, TEXT_DARK);
    private static final Font F_SEC_HDR    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   10f, APOLLO_BLUE);
    private static final Font F_TH         = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    8.5f, APOLLO_BLUE);
    private static final Font F_CELL       = FontFactory.getFont(FontFactory.HELVETICA,          9f, TEXT_DARK);
    private static final Font F_CELL_BOLD  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9f, TEXT_DARK);
    private static final Font F_SUM_LBL    = FontFactory.getFont(FontFactory.HELVETICA,          9f, TEXT_MED);
    private static final Font F_SUM_VAL    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9f, TEXT_DARK);
    private static final Font F_DISC_VAL   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9f, DISCOUNT_CLR);
    private static final Font F_TOT_LBL    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   10f, Color.WHITE);
    private static final Font F_TOT_VAL    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,   13f, Color.WHITE);
    private static final Font F_PAY_LBL    = FontFactory.getFont(FontFactory.HELVETICA,         8.5f, TEXT_GRAY);
    private static final Font F_PAY_VAL    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9f, TEXT_DARK);
    private static final Font F_FOOTER     = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE,  7.5f, TEXT_GRAY);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] generate(Clinic clinic, Sale sale, Patient patient,
                           List<SaleItem> items, Map<Long, String> medicineNames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 42, 42, 36, 42);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new PageBarHelper());
        doc.open();

        header(doc, clinic, sale);
        patientStrip(doc, sale, patient);
        itemsSection(doc, items, medicineNames);
        totalsSection(doc, sale);
        paymentAndFooter(doc, sale, clinic);

        doc.close();
        return out.toByteArray();
    }

    // ── Page decorator ────────────────────────────────────────────────────────

    private static class PageBarHelper extends PdfPageEventHelper {

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
            // Top navy bar
            cb.setColorFill(BLUE_DARK);
            cb.rectangle(0, pg.getHeight() - 6f, pg.getWidth(), 6f);
            cb.fill();
            // Bottom navy bar
            cb.rectangle(0, 0, pg.getWidth(), 4f);
            cb.fill();
            // Page number
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

    // ── Header: Clinic (left) | Bill No + Date (right) ───────────────────────

    private void header(Document doc, Clinic clinic, Sale sale) throws DocumentException {
        PdfPTable table = fw(2);
        table.setWidths(new float[]{55f, 45f});

        // ── Left: logo + clinic info ──────────────────────────────────────
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

        Paragraph cn = new Paragraph(clinic == null ? "CLINIC" : clinic.getName(), F_CLINIC_NM);
        left.addElement(cn);

        if (clinic != null && notBlank(clinic.getTagline())) {
            Paragraph tg = new Paragraph(clinic.getTagline(), F_CLINIC_TAG);
            tg.setSpacingBefore(1f);
            left.addElement(tg);
        }
        if (clinic != null) {
            if (notBlank(clinic.getAddress())) {
                Paragraph addr = new Paragraph(clinic.getAddress(), F_CLINIC_SUB);
                addr.setSpacingBefore(3f);
                left.addElement(addr);
            }
            StringBuilder contact = new StringBuilder();
            if (notBlank(clinic.getPhone())) contact.append("☎ ").append(clinic.getPhone());
            if (notBlank(clinic.getEmail())) {
                if (!contact.isEmpty()) contact.append("  •  ");
                contact.append(clinic.getEmail());
            }
            if (!contact.isEmpty()) {
                left.addElement(new Paragraph(contact.toString(), F_CLINIC_SUB));
            }
            StringBuilder reg = new StringBuilder();
            if (notBlank(clinic.getGstin()))     reg.append("GSTIN: ").append(clinic.getGstin());
            if (notBlank(clinic.getLicenseNo())) {
                if (!reg.isEmpty()) reg.append("  •  ");
                reg.append("Lic: ").append(clinic.getLicenseNo());
            }
            if (!reg.isEmpty()) {
                left.addElement(new Paragraph(reg.toString(), F_CLINIC_SUB));
            }
        }
        table.addCell(left);

        // ── Right: bill number + date ─────────────────────────────────────
        PdfPCell right = noBorder();
        right.setPaddingTop(10f);
        right.setPaddingBottom(8f);
        right.setPaddingLeft(10f);
        right.setPaddingRight(4f);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);

        Paragraph billLbl = new Paragraph("PHARMACY BILL", F_BILL_LBL);
        billLbl.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(billLbl);

        Paragraph billNo = new Paragraph(sale.getBillNo(), F_BILL_NO);
        billNo.setAlignment(Element.ALIGN_RIGHT);
        billNo.setSpacingBefore(2f);
        right.addElement(billNo);

        if (sale.getCreatedAt() != null) {
            String dateStr = sale.getCreatedAt().atZone(ZoneId.systemDefault()).format(DATE_FMT);
            String timeStr = sale.getCreatedAt().atZone(ZoneId.systemDefault()).format(TIME_FMT);
            Paragraph dateLbl = new Paragraph("Date: " + dateStr + "   Time: " + timeStr, F_BILL_LBL);
            dateLbl.setAlignment(Element.ALIGN_RIGHT);
            dateLbl.setSpacingBefore(4f);
            right.addElement(dateLbl);
        }
        table.addCell(right);
        doc.add(table);

        doc.add(hRule(BLUE_DARK, 1.5f, 0f));
    }

    // ── Patient strip ─────────────────────────────────────────────────────────

    private static void patientStrip(Document doc, Sale sale, Patient patient)
            throws DocumentException {
        PdfPTable bg = fw(2);
        bg.setWidths(new float[]{55f, 45f});

        // Left — billed to
        PdfPCell left = new PdfPCell();
        left.setBackgroundColor(BLUE_SOFT);
        left.setBorder(Rectangle.NO_BORDER);
        left.setPaddingTop(8f); left.setPaddingBottom(8f);
        left.setPaddingLeft(8f); left.setPaddingRight(6f);

        Paragraph lbl = new Paragraph("BILLED TO", F_BILL_LBL);
        left.addElement(lbl);

        if (patient != null) {
            Paragraph name = new Paragraph(patient.getName(), F_PAT_NAME);
            name.setSpacingBefore(2f);
            left.addElement(name);

            StringBuilder sub = new StringBuilder();
            if (notBlank(patient.getMrn()))     sub.append("MRN: ").append(patient.getMrn());
            if (notBlank(patient.getPhone()))   append(sub, "☎ " + patient.getPhone());
            if (patient.getAge() != null)       append(sub, patient.getAge() + " Yrs");
            if (notBlank(patient.getGender()))  append(sub, patient.getGender());
            if (!sub.isEmpty()) {
                Paragraph sp = new Paragraph(sub.toString(), F_PAT_DETAIL);
                sp.setSpacingBefore(2f);
                left.addElement(sp);
            }
        } else {
            String name = notBlank(sale.getWalkInName()) ? sale.getWalkInName() : "Walk-in Customer";
            Paragraph np = new Paragraph(name, F_PAT_NAME);
            np.setSpacingBefore(2f);
            left.addElement(np);
            if (notBlank(sale.getWalkInPhone())) {
                left.addElement(new Paragraph("☎ " + sale.getWalkInPhone(), F_PAT_DETAIL));
            }
        }
        bg.addCell(left);

        // Right — receipt label
        PdfPCell right = new PdfPCell();
        right.setBackgroundColor(BLUE_SOFT);
        right.setBorder(Rectangle.NO_BORDER);
        right.setBorderWidthLeft(0.5f);
        right.setBorderColorLeft(BORDER);
        right.setPaddingTop(8f); right.setPaddingBottom(8f);
        right.setPaddingLeft(10f); right.setPaddingRight(8f);

        Paragraph rLbl = new Paragraph("RECEIPT TYPE", F_BILL_LBL);
        right.addElement(rLbl);
        Paragraph rVal = new Paragraph("Pharmacy Bill", F_VALUE);
        rVal.setSpacingBefore(2f);
        right.addElement(rVal);

        bg.addCell(right);
        doc.add(bg);
        doc.add(hRule(BORDER, 0.5f, 0f));
    }

    // ── Items table ───────────────────────────────────────────────────────────

    private static void itemsSection(Document doc, List<SaleItem> items,
                                     Map<Long, String> medicineNames)
            throws DocumentException {
        Paragraph h = new Paragraph("Medicines Dispensed", F_SEC_HDR);
        h.setSpacingBefore(10f);
        h.setSpacingAfter(4f);
        doc.add(h);

        boolean hasLineDisc = items.stream()
                .anyMatch(i -> i.getDiscountAmount() != null && i.getDiscountAmount().signum() > 0);

        int cols = hasLineDisc ? 7 : 5;
        PdfPTable t = fw(cols);
        try {
            t.setWidths(hasLineDisc
                    ? new float[]{5f, 38f, 9f, 13f, 13f, 10f, 12f}
                    : new float[]{5f, 50f, 10f, 17f, 18f});
        } catch (DocumentException ignored) {}
        t.setHeaderRows(1);

        t.addCell(thCell("#"));
        t.addCell(thCell("Medicine"));
        t.addCell(thCell("Qty"));
        t.addCell(thCell("Unit ₹"));
        if (hasLineDisc) {
            t.addCell(thCell("Gross ₹"));
            t.addCell(thCell("Disc ₹"));
        }
        t.addCell(thCell("Total ₹"));

        int idx = 1;
        for (SaleItem item : items) {
            String name = medicineNames.getOrDefault(item.getMedicineId(),
                    "Medicine " + item.getMedicineId());
            boolean zebra = idx % 2 == 0;
            Color bg = zebra ? ZEBRA : Color.WHITE;

            t.addCell(dataCell(String.valueOf(idx++), bg, Element.ALIGN_CENTER, false));
            t.addCell(dataCell(name,                   bg, Element.ALIGN_LEFT,   true));
            t.addCell(dataCell(String.valueOf(item.getQuantity()), bg, Element.ALIGN_CENTER, false));
            t.addCell(dataCell("₹ " + money(item.getUnitPrice()),  bg, Element.ALIGN_RIGHT,  false));
            if (hasLineDisc) {
                t.addCell(dataCell("₹ " + money(item.getGrossAmount()), bg, Element.ALIGN_RIGHT, false));
                String disc = item.getDiscountAmount() != null && item.getDiscountAmount().signum() > 0
                        ? "− ₹ " + money(item.getDiscountAmount()) : "—";
                t.addCell(dataCell(disc, bg, Element.ALIGN_RIGHT, false));
            }
            t.addCell(dataCell("₹ " + money(item.getLineTotal()), bg, Element.ALIGN_RIGHT, true));
        }
        doc.add(t);
        doc.add(hRule(BORDER, 0.3f, 3f));
    }

    // ── Totals block ──────────────────────────────────────────────────────────

    private static void totalsSection(Document doc, Sale sale) throws DocumentException {
        // Right-aligned 2-column summary table
        PdfPTable wrapper = fw(2);
        wrapper.setWidths(new float[]{55f, 45f});
        wrapper.setSpacingBefore(6f);

        // Left — spacer
        wrapper.addCell(noBorder());

        // Right — summary rows
        PdfPCell right = noBorder();
        right.setPadding(0f);

        PdfPTable summary = fw(2);
        summary.setWidths(new float[]{58f, 42f});

        boolean hasDiscount = sale.getBillDiscountAmount() != null
                && sale.getBillDiscountAmount().signum() > 0;

        if (hasDiscount) {
            summaryRow(summary, "Subtotal", "₹ " + money(sale.getSubtotalAmount()), false);
            String discLabel = "Bill Discount";
            if ("PERCENT".equals(sale.getBillDiscountType()) && sale.getBillDiscountValue() != null) {
                discLabel += " (" + sale.getBillDiscountValue().stripTrailingZeros().toPlainString() + "%)";
            }
            summaryRow(summary, discLabel, "− ₹ " + money(sale.getBillDiscountAmount()), true);
        }

        // Grand total — navy highlight row, consistent with the document's primary colour
        PdfPCell totalLbl = new PdfPCell(new Paragraph("GRAND TOTAL", F_TOT_LBL));
        totalLbl.setBackgroundColor(APOLLO_BLUE);
        totalLbl.setBorder(Rectangle.NO_BORDER);
        totalLbl.setPaddingTop(9f); totalLbl.setPaddingBottom(9f);
        totalLbl.setPaddingLeft(10f);
        totalLbl.setVerticalAlignment(Element.ALIGN_MIDDLE);
        summary.addCell(totalLbl);

        PdfPCell totalVal = new PdfPCell(new Paragraph("₹ " + money(sale.getTotalAmount()), F_TOT_VAL));
        totalVal.setBackgroundColor(APOLLO_BLUE);
        totalVal.setBorder(Rectangle.NO_BORDER);
        totalVal.setPaddingTop(9f); totalVal.setPaddingBottom(9f);
        totalVal.setPaddingRight(10f);
        totalVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalVal.setVerticalAlignment(Element.ALIGN_MIDDLE);
        summary.addCell(totalVal);

        right.addElement(summary);
        wrapper.addCell(right);
        doc.add(wrapper);
    }

    private static void summaryRow(PdfPTable t, String label, String value, boolean isDiscount) {
        PdfPCell lc = new PdfPCell(new Paragraph(label, F_SUM_LBL));
        lc.setBorder(Rectangle.BOTTOM);
        lc.setBorderColor(BORDER);
        lc.setBorderWidth(0.3f);
        lc.setPaddingTop(5f); lc.setPaddingBottom(5f);
        lc.setPaddingLeft(10f);
        t.addCell(lc);

        PdfPCell vc = new PdfPCell(new Paragraph(value, isDiscount ? F_DISC_VAL : F_SUM_VAL));
        vc.setBorder(Rectangle.BOTTOM);
        vc.setBorderColor(BORDER);
        vc.setBorderWidth(0.3f);
        vc.setPaddingTop(5f); vc.setPaddingBottom(5f);
        vc.setPaddingRight(10f);
        vc.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(vc);
    }

    // ── Payment + footer ──────────────────────────────────────────────────────

    private static void paymentAndFooter(Document doc, Sale sale, Clinic clinic)
            throws DocumentException {
        // Payment mode chip
        PdfPTable pay = fw(2);
        pay.setWidths(new float[]{55f, 45f});
        pay.setSpacingBefore(8f);

        PdfPCell payLeft = noBorder();
        Paragraph pm = new Paragraph();
        pm.add(new Chunk("Payment Mode: ", F_PAY_LBL));
        pm.add(new Chunk(sale.getPaymentMode() != null ? sale.getPaymentMode() : "CASH", F_PAY_VAL));
        payLeft.addElement(pm);
        pay.addCell(payLeft);
        pay.addCell(noBorder());
        doc.add(pay);

        doc.add(hRule(BORDER, 0.3f, 10f));

        // Footer
        if (clinic != null && notBlank(clinic.getFooterNote())) {
            Paragraph fn = new Paragraph(clinic.getFooterNote(), F_FOOTER);
            fn.setAlignment(Element.ALIGN_CENTER);
            fn.setSpacingBefore(4f);
            doc.add(fn);
        }
        Paragraph thanks = new Paragraph("Thank you for your visit. Get well soon.", F_FOOTER);
        thanks.setAlignment(Element.ALIGN_CENTER);
        thanks.setSpacingBefore(3f);
        doc.add(thanks);
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
        Font f = bold ? F_CELL_BOLD : F_CELL;
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

    private static String money(BigDecimal v) {
        if (v == null) return "0.00";
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void append(StringBuilder sb, String s) {
        if (!sb.isEmpty()) sb.append("  •  ");
        sb.append(s);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
