package com.visolearn.utils;

import com.visolearn.SkinClassifier;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * VisoLearn AI Studio — Clinical PDF Report Exporter
 *
 * Generates a professional A4 PDF with:
 * - Header: app name, subtitle, risk level badge, date, page number
 * - Body: the full report snapshot (same as PNG export)
 * - Footer: disclaimer box and page number
 *
 * Method signature is IDENTICAL to v1 — no call site changes needed.
 * Uses only PDFBox 2.0.x (already in pom.xml) — no new dependencies.
 *
 * @author Rao Hamza Bilal
 * @version 2.0
 */
public class PdfReportExporter {

    // ── Layout constants (points — 1 pt = 1/72 inch) ─────────────────────────
    private static final float MARGIN = 28f;
    private static final float HEADER_HEIGHT = 50f; // space reserved at top
    private static final float FOOTER_HEIGHT = 58f; // space reserved at bottom

    // ── Risk level colors (RGB 0-1 range) ────────────────────────────────────
    private static final float[] COLOR_URGENT = { 0.80f, 0.07f, 0.07f };
    private static final float[] COLOR_MODERATE = { 0.72f, 0.40f, 0.02f };
    private static final float[] COLOR_LOW = { 0.06f, 0.53f, 0.35f };

    // ── Text colors ───────────────────────────────────────────────────────────
    private static final float[] COLOR_DARK = { 0.05f, 0.05f, 0.05f };
    private static final float[] COLOR_MUTED = { 0.45f, 0.45f, 0.45f };
    private static final float[] COLOR_FAINT = { 0.60f, 0.60f, 0.60f };
    private static final float[] COLOR_DISCLMR = { 0.38f, 0.38f, 0.38f };
    private static final float[] COLOR_BOX_FILL = { 0.96f, 0.96f, 0.96f };
    private static final float[] COLOR_BOX_BRDR = { 0.84f, 0.84f, 0.84f };
    private static final float[] COLOR_DIVIDER = { 0.82f, 0.82f, 0.82f };

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — signature unchanged from v1
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exports a clinical PDF report.
     *
     * <p>
     * Signature is identical to v1 — no changes required in
     * {@code ClassifyController.handleExportPdfInternal()}.
     * </p>
     *
     * @param file          destination PDF file chosen by the user
     * @param result        inference result from the ensemble classifier
     * @param originalImage the uploaded dermoscopy image
     * @param heatmapImage  the occlusion-sensitivity overlay (may be null)
     * @param notes         doctor notes typed in the Classify tab
     * @throws IOException if the PDF cannot be written
     */
    public static void exportClinicalReport(
            File file,
            SkinClassifier.PredictionResult result,
            Image originalImage,
            Image heatmapImage,
            String notes) throws IOException {

        // Generate the report snapshot — same layout as PNG export
        WritableImage snapshot = ReportExportUtil.generateReportSnapshot(
                originalImage, heatmapImage, result, notes);

        BufferedImage awtImage = SwingFXUtils.fromFXImage(snapshot, null);

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            float pageWidth = page.getMediaBox().getWidth(); // 595.28 pt
            float pageHeight = page.getMediaBox().getHeight(); // 841.89 pt

            PDImageXObject pdImage = LosslessFactory.createFromImage(document, awtImage);

            // ── Calculate image placement ─────────────────────────────────
            // Available area is between the header divider and the footer box
            float contentW = pageWidth - 2 * MARGIN;
            float contentH = pageHeight - 2 * MARGIN - HEADER_HEIGHT - FOOTER_HEIGHT;

            float scale = Math.min(contentW / pdImage.getWidth(),
                    contentH / pdImage.getHeight());
            float scaledWidth = pdImage.getWidth() * scale;
            float scaledHeight = pdImage.getHeight() * scale;

            // Center horizontally; sit directly above the footer zone
            float imageX = MARGIN + (contentW - scaledWidth) / 2f;
            float imageY = MARGIN + FOOTER_HEIGHT;

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                drawHeader(cs, pageWidth, pageHeight, result);
                cs.drawImage(pdImage, imageX, imageY, scaledWidth, scaledHeight);
                drawFooter(cs, pageWidth);
            }

            document.save(file);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Header
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Draws the page header containing the app name, subtitle, risk badge,
     * report date, page indicator, and a horizontal divider line.
     */
    private static void drawHeader(PDPageContentStream cs,
            float pageWidth,
            float pageHeight,
            SkinClassifier.PredictionResult result) throws IOException {

        // y-coordinate of the top of the usable area
        float top = pageHeight - MARGIN;

        // ── App name (bold 13pt, near-black) ─────────────────────────────
        setColor(cs, COLOR_DARK, false);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_BOLD, 13f);
        cs.newLineAtOffset(MARGIN, top - 16f);
        cs.showText("VisoLearn AI Studio");
        cs.endText();

        // ── Subtitle (8.5pt, muted gray) ─────────────────────────────────
        setColor(cs, COLOR_MUTED, false);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 8.5f);
        cs.newLineAtOffset(MARGIN, top - 28f);
        cs.showText("Skin Lesion Classification Report");
        cs.endText();

        // ── Risk level badge (centered, 9pt bold, colored) ───────────────
        String riskText = buildRiskText(result);
        float[] riskColor = buildRiskColor(result);
        float riskW = PDType1Font.HELVETICA_BOLD.getStringWidth(riskText) / 1000f * 9f;
        setColor(cs, riskColor, false);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_BOLD, 9f);
        cs.newLineAtOffset((pageWidth - riskW) / 2f, top - 22f);
        cs.showText(riskText);
        cs.endText();

        // ── Date right-aligned (8.5pt, muted gray) ───────────────────────
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy"));
        float dateW = PDType1Font.HELVETICA.getStringWidth(date) / 1000f * 8.5f;
        setColor(cs, COLOR_MUTED, false);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 8.5f);
        cs.newLineAtOffset(pageWidth - MARGIN - dateW, top - 16f);
        cs.showText(date);
        cs.endText();

        // ── "Page 1 of 1" right-aligned (8pt, faint gray) ────────────────
        String pageStr = "Page 1 of 1";
        float pageStrW = PDType1Font.HELVETICA.getStringWidth(pageStr) / 1000f * 8f;
        setColor(cs, COLOR_FAINT, false);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 8f);
        cs.newLineAtOffset(pageWidth - MARGIN - pageStrW, top - 28f);
        cs.showText(pageStr);
        cs.endText();

        // ── Horizontal divider line ───────────────────────────────────────
        float lineY = top - HEADER_HEIGHT + 6f;
        cs.saveGraphicsState();
        setColor(cs, COLOR_DIVIDER, true);
        cs.setLineWidth(0.5f);
        cs.moveTo(MARGIN, lineY);
        cs.lineTo(pageWidth - MARGIN, lineY);
        cs.stroke();
        cs.restoreGraphicsState();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Footer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Draws the disclaimer box and page number at the bottom of the page.
     */
    private static void drawFooter(PDPageContentStream cs,
            float pageWidth) throws IOException {

        float boxY = MARGIN + 16f;
        float boxH = FOOTER_HEIGHT - 24f;
        float boxW = pageWidth - 2 * MARGIN;

        // ── Disclaimer box (light gray fill + border) ─────────────────────
        cs.saveGraphicsState();
        setColor(cs, COLOR_BOX_FILL, false);
        setColor(cs, COLOR_BOX_BRDR, true);
        cs.setLineWidth(0.5f);
        cs.addRect(MARGIN, boxY, boxW, boxH);
        cs.fillAndStroke();
        cs.restoreGraphicsState();

        // ── Disclaimer text (3 lines, 7pt italic gray) ────────────────────
        float textX = MARGIN + 8f;
        float textTopY = boxY + boxH - 11f;
        float lineGap = 10f;

        setColor(cs, COLOR_DISCLMR, false);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 7f);
        cs.newLineAtOffset(textX, textTopY);
        cs.showText(
                "DISCLAIMER: This report is generated by an AI classification system for research and educational purposes only.");
        cs.newLineAtOffset(0, -lineGap);
        cs.showText(
                "It does not constitute a medical diagnosis. All findings must be reviewed by a licensed dermatologist");
        cs.newLineAtOffset(0, -lineGap);
        cs.showText(
                "before any clinical decision is made.  |  VisoLearn AI Studio v2.0  |  github.com/bilalraohamza/visolearn-ai-studio");
        cs.endText();

        // ── Page number centered at very bottom ───────────────────────────
        String pn = "- 1 -";
        float pnW = PDType1Font.HELVETICA.getStringWidth(pn) / 1000f * 8f;
        setColor(cs, COLOR_FAINT, false);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 8f);
        cs.newLineAtOffset((pageWidth - pnW) / 2f, MARGIN);
        cs.showText(pn);
        cs.endText();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets either non-stroking (fill) or stroking (line) color from an RGB float[3]
     * array.
     */
    private static void setColor(PDPageContentStream cs,
            float[] rgb,
            boolean stroking) throws IOException {
        if (stroking) {
            cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        } else {
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        }
    }

    /**
     * Builds a short ASCII risk label for the header badge.
     * Uses ASCII brackets instead of Unicode to guarantee rendering
     * with PDType1Font (WinAnsi encoding).
     */
    private static String buildRiskText(SkinClassifier.PredictionResult result) {
        int idx = result.classIndex;
        float conf = result.confidence;
        if ((idx == 4 || idx == 1) && conf > 60f)
            return "[ URGENT RISK ]";
        if ((idx == 4 || idx == 1) || (idx == 0 && conf > 60f))
            return "[ MODERATE RISK ]";
        return "[ LOW RISK ]";
    }

    /** Returns the RGB color array that matches the risk level. */
    private static float[] buildRiskColor(SkinClassifier.PredictionResult result) {
        String t = buildRiskText(result);
        if (t.contains("URGENT"))
            return COLOR_URGENT;
        if (t.contains("MODERATE"))
            return COLOR_MODERATE;
        return COLOR_LOW;
    }
}