package com.visolearn.utils;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * VisoLearn AI Studio — Clinical Report Export Utility
 *
 * <p>Generates a high-resolution PNG clinical report by constructing an
 * off-screen JavaFX layout, snapshotting it at a user-configured scale,
 * and persisting it to a user-selected file via {@link javax.imageio.ImageIO}.</p>
 *
 * <p>The snapshot scale is read dynamically from {@link java.util.prefs.Preferences}
 * as set by the user in {@link SettingsModal}, supporting Standard (1×),
 * High / Retina (2×), and Ultra (3×) export resolutions.</p>
 *
 * <p><b>No external libraries required.</b> Uses only standard JavaFX
 * and {@code javax.imageio} APIs.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * ReportExportUtil.saveReportAsImage(
 *     primaryStage,
 *     originalImage,
 *     heatmapImage,
 *     "Diabetic Retinopathy",
 *     0.9734,
 *     "142 ms"
 * );
 * }</pre>
 */
public final class ReportExportUtil {

    // ─────────────────────────────────────────────────────────────────────────
    // Design Constants — Light clinical theme for print/export readability
    // ─────────────────────────────────────────────────────────────────────────

    /** Overall report background — pure clinical white. */
    private static final String COLOR_BACKGROUND    = "#FFFFFF";

    /** Primary text color — near-black for maximum contrast. */
    private static final String COLOR_TEXT_PRIMARY  = "#111827";

    /** Secondary text — muted gray for labels and meta information. */
    private static final String COLOR_TEXT_SECONDARY = "#6B7280";

    /** Brand accent — VisoLearn emerald. */
    private static final String COLOR_ACCENT        = "#10B981";

    /** Darker emerald used for confidence indicator bar fill. */
    private static final String COLOR_ACCENT_DARK   = "#059669";

    /** Subtle divider and card border color. */
    private static final String COLOR_BORDER        = "#E5E7EB";

    /** Light surface for data cards (stats section). */
    private static final String COLOR_CARD_BG       = "#F9FAFB";

    /** Header gradient start. */
    private static final String COLOR_HEADER_START  = "#064E3B";

    /** Header gradient end. */
    private static final String COLOR_HEADER_END    = "#065F46";

    /** Width of the entire report canvas in logical pixels. */
    private static final double REPORT_WIDTH        = 860;

    /** Each medical image display width inside the report. */
    private static final double IMAGE_FIT_WIDTH     = 300;

    /** Each medical image display height inside the report. */
    private static final double IMAGE_FIT_HEIGHT    = 260;

    // ─────────────────────────────────────────────────────────────────────────
    // Private Constructor — static utility class
    // ─────────────────────────────────────────────────────────────────────────

    private ReportExportUtil() {
        throw new UnsupportedOperationException(
                "ReportExportUtil is a static utility class and cannot be instantiated.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds an off-screen clinical report layout, snapshots it at the
     * user-configured resolution scale, and writes it to a PNG file chosen
     * by the user via a {@link FileChooser} dialog.
     *
     * @param owner         The owning {@link Window} for the {@link FileChooser} dialog.
     * @param original      The raw input scan {@link Image}.
     * @param heatmap       The Grad-CAM saliency map {@link Image}.
     * @param topClass      The predicted diagnostic class label (e.g., {@code "Diabetic Retinopathy"}).
     * @param confidence    Prediction confidence as a fraction, 0.0–1.0 (e.g., {@code 0.9734}).
     * @param inferenceTime Human-readable inference duration string (e.g., {@code "142 ms"}).
     */
    public static void saveReportAsImage(
            Window owner,
            Image  original,
            Image  heatmap,
            String topClass,
            double confidence,
            String inferenceTime) {

        // ── Step 1: Let the user choose the output file ──────────────────────
        File outputFile = promptSaveLocation(owner);
        if (outputFile == null) {
            return;
        }

        // ── Step 2: Build the off-screen report VBox ─────────────────────────
        VBox reportLayout = buildReportLayout(
                original, heatmap, topClass, confidence, inferenceTime);
        new javafx.scene.Scene(reportLayout);

        // ── Step 3: Force layout pass so all node sizes are computed ─────────
        reportLayout.applyCss();
        reportLayout.layout();

        // ── Step 4: Configure SnapshotParameters ─────────────────────────────
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.WHITE);

        /*
         * Read the export scale dynamically from Preferences rather than using
         * a hardcoded constant. This reflects the user's choice from SettingsModal:
         *   Standard (1×)      → 1.0  — fast export, screen-resolution PNG
         *   High / Retina (2×) → 2.0  — default, crisp for most displays/print
         *   Ultra (3×)         → 3.0  — maximum fidelity for large-format printing
         */
        double scale = getSnapshotScale();
        params.setTransform(new javafx.scene.transform.Scale(scale, scale));

        // ── Step 5: Snapshot the node into a WritableImage ───────────────────
        WritableImage fxImage = reportLayout.snapshot(params, null);

        // ── Step 6: Convert to BufferedImage and write to disk ───────────────
        BufferedImage bufferedImage = SwingFXUtils.fromFXImage(fxImage, null);

        try {
            String path = outputFile.getAbsolutePath();
            if (!path.toLowerCase().endsWith(".png")) {
                outputFile = new File(path + ".png");
            }

            boolean written = ImageIO.write(bufferedImage, "PNG", outputFile);

            if (written) {
                System.out.println("[ReportExportUtil] Report saved ("
                        + scale + "×): " + outputFile.getAbsolutePath());
                ToastUtil.showToast(
                        findRootStackPane(owner),
                        "Report saved successfully!",
                        ToastUtil.ToastType.SUCCESS
                );
            } else {
                System.err.println("[ReportExportUtil] ImageIO could not find a PNG writer.");
            }

        } catch (IOException ex) {
            System.err.println("[ReportExportUtil] Failed to write report: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dynamic Snapshot Scale
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the user's preferred report export resolution from
     * {@link java.util.prefs.Preferences} as persisted by {@link SettingsModal},
     * and maps it to a numeric scale factor for {@link SnapshotParameters}.
     *
     * <table border="1">
     *   <caption>Resolution preference mapping</caption>
     *   <tr><th>Preference value (prefix)</th><th>Scale factor</th><th>Output width (px)</th></tr>
     *   <tr><td>{@code "Standard"}</td><td>1.0</td><td>860</td></tr>
     *   <tr><td>{@code "High / Retina (2×)"} (default)</td><td>2.0</td><td>1720</td></tr>
     *   <tr><td>{@code "Ultra"}</td><td>3.0</td><td>2580</td></tr>
     * </table>
     *
     * @return The scale factor to apply to {@link SnapshotParameters#setTransform}.
     */
    private static double getSnapshotScale() {
        String saved = SettingsManager.getExportResolution();

        if (saved.startsWith("Standard")) return 1.0;
        if (saved.startsWith("Ultra"))    return 3.0;
        return 2.0; // default: High / Retina (2×)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Report Layout Builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs and returns the complete off-screen report {@link VBox}.
     * This node is <em>never</em> added to any visible scene graph.
     */
    private static VBox buildReportLayout(
            Image  original,
            Image  heatmap,
            String topClass,
            double confidence,
            String inferenceTime) {

        VBox root = new VBox();
        root.setPrefWidth(REPORT_WIDTH);
        root.setMinWidth(REPORT_WIDTH);
        root.setMaxWidth(REPORT_WIDTH);
        root.setStyle("-fx-background-color: " + COLOR_BACKGROUND + ";");
        root.setSpacing(0);

        root.getChildren().addAll(
                buildHeader(),
                buildMetaInfoBar(),
                buildSectionSpacer(24),
                buildDiagnosisSummarySection(topClass, confidence, inferenceTime),
                buildSectionSpacer(24),
                buildDivider(),
                buildSectionSpacer(24),
                buildImageSection(original, heatmap),
                buildSectionSpacer(24),
                buildDivider(),
                buildSectionSpacer(16),
                buildInterpretationSection(topClass, confidence),
                buildSectionSpacer(24),
                buildFooter()
        );

        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Header
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the dark gradient header bar containing the application logo
     * text, report title, and a generated report ID.
     */
    private static HBox buildHeader() {
        HBox header = new HBox();
        header.setPrefWidth(REPORT_WIDTH);
        header.setPadding(new Insets(28, 36, 28, 36));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setSpacing(0);
        header.setStyle(
                "-fx-background-color: linear-gradient(to right, "
                        + COLOR_HEADER_START + ", " + COLOR_HEADER_END + ");"
        );

        VBox titleBlock = new VBox(4);
        titleBlock.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        HBox brandRow = new HBox(10);
        brandRow.setAlignment(Pos.CENTER_LEFT);

        Rectangle logoMark = new Rectangle(28, 28);
        logoMark.setFill(Color.web(COLOR_ACCENT));
        logoMark.setArcWidth(6);
        logoMark.setArcHeight(6);

        Label brandLabel = new Label("VisoLearn AI Studio");
        brandLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        brandLabel.setStyle("-fx-text-fill: #FFFFFF;");

        brandRow.getChildren().addAll(logoMark, brandLabel);

        Label reportTitle = new Label("Clinical AI Analysis Report");
        reportTitle.setFont(Font.font("System", FontWeight.NORMAL, 13));
        reportTitle.setStyle("-fx-text-fill: #A7F3D0;");

        titleBlock.getChildren().addAll(brandRow, reportTitle);

        VBox idBlock = new VBox(4);
        idBlock.setAlignment(Pos.CENTER_RIGHT);

        String reportId = "RPT-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        Label idLabel = new Label("REPORT ID");
        idLabel.setFont(Font.font("System", FontWeight.BOLD, 9));
        idLabel.setStyle("-fx-text-fill: #6EE7B7; -fx-letter-spacing: 1.5;");

        Label idValue = new Label(reportId);
        idValue.setFont(Font.font("System", FontWeight.BOLD, 12));
        idValue.setStyle("-fx-text-fill: #FFFFFF;");

        idBlock.getChildren().addAll(idLabel, idValue);
        header.getChildren().addAll(titleBlock, idBlock);
        return header;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Meta Info Bar
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a slim sub-header bar showing the report generation timestamp
     * and application version — useful for audit trails in clinical settings.
     */
    private static HBox buildMetaInfoBar() {
        HBox bar = new HBox();
        bar.setPrefWidth(REPORT_WIDTH);
        bar.setPadding(new Insets(10, 36, 10, 36));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setSpacing(24);
        bar.setStyle(
                "-fx-background-color: #F0FDF4;"                +
                        "-fx-border-color: " + COLOR_BORDER + ";"       +
                        "-fx-border-width: 0 0 1 0;"
        );

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MMMM dd, yyyy  |  HH:mm:ss 'UTC'"));

        Label tsIcon   = makeMetaChip("🕐", "Generated On:", timestamp);
        Label verIcon  = makeMetaChip("⚙",  "Engine:", "VisoLearn Inference Engine v2.1.0");
        Label modeIcon = makeMetaChip("🔬", "Mode:", "Diagnostic — Research Use Only");

        HBox.setHgrow(tsIcon, Priority.ALWAYS);
        bar.getChildren().addAll(tsIcon, verIcon, modeIcon);
        return bar;
    }

    /** Creates a single meta-info chip (icon + key + value). */
    private static Label makeMetaChip(String icon, String key, String value) {
        Label l = new Label(icon + "  " + key + "  " + value);
        l.setFont(Font.font("System", FontWeight.NORMAL, 10.5));
        l.setStyle("-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";");
        return l;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Diagnosis Summary
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the three-column stat card row:
     * AI Diagnosis | Confidence Score + bar | Inference Time.
     */
    private static HBox buildDiagnosisSummarySection(
            String topClass, double confidence, String inferenceTime) {

        HBox row = new HBox(20);
        row.setPadding(new Insets(0, 36, 0, 36));
        row.setAlignment(Pos.CENTER);

        VBox diagCard = buildStatCard(
                "AI DIAGNOSIS", topClass, "Primary classification result", COLOR_ACCENT);
        HBox.setHgrow(diagCard, Priority.ALWAYS);

        VBox confCard = buildConfidenceCard(confidence);
        HBox.setHgrow(confCard, Priority.ALWAYS);

        VBox timeCard = buildStatCard(
                "INFERENCE TIME", inferenceTime, "Model forward-pass latency", "#6366F1");
        HBox.setHgrow(timeCard, Priority.ALWAYS);

        row.getChildren().addAll(diagCard, confCard, timeCard);
        return row;
    }

    /**
     * Generic stat card: label on top, large value in center, subtitle at bottom.
     */
    private static VBox buildStatCard(
            String header, String value, String subtitle, String accentColor) {

        VBox card = new VBox(6);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle(
                "-fx-background-color: " + COLOR_CARD_BG + ";"  +
                        "-fx-background-radius: 10px;"                   +
                        "-fx-border-color: " + COLOR_BORDER + ";"        +
                        "-fx-border-radius: 10px;"                       +
                        "-fx-border-width: 1px;"
        );

        Rectangle topStripe = new Rectangle(36, 4);
        topStripe.setFill(Color.web(accentColor));
        topStripe.setArcWidth(4);
        topStripe.setArcHeight(4);

        Label headerLabel = new Label(header);
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 9));
        headerLabel.setStyle(
                "-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";" +
                        "-fx-letter-spacing: 1px;"
        );

        Label valueLabel = new Label(value);
        valueLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        valueLabel.setStyle("-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";");
        valueLabel.setWrapText(true);

        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.setFont(Font.font("System", FontWeight.NORMAL, 10));
        subtitleLabel.setStyle("-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";");

        card.getChildren().addAll(topStripe, headerLabel, valueLabel, subtitleLabel);
        return card;
    }

    /**
     * Specialised confidence card that includes a visual percentage bar.
     */
    private static VBox buildConfidenceCard(double confidence) {
        VBox card = new VBox(6);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle(
                "-fx-background-color: " + COLOR_CARD_BG + ";"  +
                        "-fx-background-radius: 10px;"                   +
                        "-fx-border-color: " + COLOR_BORDER + ";"        +
                        "-fx-border-radius: 10px;"                       +
                        "-fx-border-width: 1px;"
        );

        Rectangle topStripe = new Rectangle(36, 4);
        topStripe.setFill(Color.web(COLOR_ACCENT));
        topStripe.setArcWidth(4);
        topStripe.setArcHeight(4);

        Label headerLabel = new Label("CONFIDENCE SCORE");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 9));
        headerLabel.setStyle(
                "-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";" +
                        "-fx-letter-spacing: 1px;"
        );

        String pct = String.format("%.2f%%", confidence);
        Label valueLabel = new Label(pct);
        valueLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        valueLabel.setStyle("-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";");

        StackPane barTrack = new StackPane();
        barTrack.setPrefHeight(8);
        barTrack.setMaxWidth(Double.MAX_VALUE);
        barTrack.setStyle(
                "-fx-background-color: #D1FAE5;" +
                        "-fx-background-radius: 4px;"
        );

        double fillWidthPct = Math.min(confidence, 1.0);
        HBox barFill = new HBox();
        barFill.setPrefHeight(8);
        barFill.setStyle(
                "-fx-background-color: " + COLOR_ACCENT_DARK + ";" +
                        "-fx-background-radius: 4px;"
        );
        barFill.setPrefWidth(fillWidthPct);
        StackPane.setAlignment(barFill, Pos.CENTER_LEFT);
        barTrack.getChildren().add(barFill);

        Label subtitleLabel = new Label("Model posterior probability");
        subtitleLabel.setFont(Font.font("System", FontWeight.NORMAL, 10));
        subtitleLabel.setStyle("-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";");

        card.getChildren().addAll(topStripe, headerLabel, valueLabel, barTrack, subtitleLabel);
        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Medical Images Side-by-Side
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the side-by-side image display section showing the original scan
     * and the Grad-CAM saliency heatmap with descriptive labels.
     */
    private static VBox buildImageSection(Image original, Image heatmap) {
        VBox section = new VBox(16);
        section.setPadding(new Insets(0, 36, 0, 36));

        HBox imageRow = new HBox(24);
        imageRow.setAlignment(Pos.CENTER);

        VBox originalCard = buildImageCard(original, "Input Scan",
                "Raw diagnostic input image");
        VBox heatmapCard  = buildImageCard(heatmap, "Grad-CAM Saliency Map",
                "Highlighted regions of diagnostic interest");

        HBox.setHgrow(originalCard, Priority.ALWAYS);
        HBox.setHgrow(heatmapCard,  Priority.ALWAYS);

        imageRow.getChildren().addAll(originalCard, heatmapCard);
        section.getChildren().addAll(buildSectionTitle("Medical Image Analysis"), imageRow);
        return section;
    }

    /**
     * Builds a single framed image card with a title and subtitle beneath.
     */
    private static VBox buildImageCard(Image image, String title, String subtitle) {
        VBox card = new VBox(10);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(16));
        card.setStyle(
                "-fx-background-color: " + COLOR_CARD_BG + ";"  +
                        "-fx-background-radius: 10px;"                   +
                        "-fx-border-color: " + COLOR_BORDER + ";"        +
                        "-fx-border-radius: 10px;"                       +
                        "-fx-border-width: 1px;"
        );

        ImageView view = new ImageView(image);
        view.setFitWidth(IMAGE_FIT_WIDTH);
        view.setFitHeight(IMAGE_FIT_HEIGHT);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        StackPane imageFrame = new StackPane(view);
        imageFrame.setStyle(
                "-fx-background-color: #E5E7EB;" +
                        "-fx-background-radius: 6px;"    +
                        "-fx-padding: 4px;"
        );

        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        titleLabel.setStyle("-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";");

        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.setFont(Font.font("System", FontWeight.NORMAL, 10));
        subtitleLabel.setStyle("-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";");
        subtitleLabel.setWrapText(true);

        card.getChildren().addAll(imageFrame, titleLabel, subtitleLabel);
        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Clinical Interpretation Notes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a clinical interpretation block providing standardised disclaimer
     * text and a reading guide for the saliency map.
     */
    private static VBox buildInterpretationSection(String topClass, double confidence) {
        VBox section = new VBox(14);
        section.setPadding(new Insets(0, 36, 0, 36));

        VBox disclaimerPanel = new VBox(8);
        disclaimerPanel.setPadding(new Insets(16, 18, 16, 18));
        disclaimerPanel.setStyle(
                "-fx-background-color: #FFFBEB;"   +
                        "-fx-background-radius: 8px;"       +
                        "-fx-border-color: #FCD34D;"        +
                        "-fx-border-radius: 8px;"           +
                        "-fx-border-width: 0 0 0 4;"
        );

        Label disclaimerTitle = new Label("⚠  Important Clinical Notice");
        disclaimerTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        disclaimerTitle.setStyle("-fx-text-fill: #92400E;");

        Label disclaimerBody = new Label(
                "This report is generated by an AI inference model and is intended for " +
                        "research and decision-support purposes only. It does not constitute a " +
                        "definitive medical diagnosis. All findings must be reviewed, validated, " +
                        "and confirmed by a qualified and licensed medical professional before " +
                        "any clinical decision is made."
        );
        disclaimerBody.setFont(Font.font("System", FontWeight.NORMAL, 11));
        disclaimerBody.setStyle("-fx-text-fill: #78350F;");
        disclaimerBody.setWrapText(true);

        disclaimerPanel.getChildren().addAll(disclaimerTitle, disclaimerBody);

        String confidenceGrade = confidence >= 0.90 ? "High" :
                confidence >= 0.70 ? "Moderate" : "Low";

        Label findingsLabel = new Label(
                "Model Findings:  The AI model classified the input scan as \"" + topClass + "\" " +
                        "with a " + confidenceGrade + " confidence score of " +
                        String.format("%.2f%%", confidence) + ". " +
                        "The Grad-CAM saliency map highlights the image regions that most strongly " +
                        "influenced this classification. Warmer (red/yellow) regions indicate higher " +
                        "model attention. Cooler (blue) regions were less influential in the decision."
        );
        findingsLabel.setFont(Font.font("System", FontWeight.NORMAL, 11.5));
        findingsLabel.setStyle("-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";");
        findingsLabel.setWrapText(true);
        findingsLabel.setLineSpacing(3);

        section.getChildren().addAll(
                buildSectionTitle("Clinical Interpretation Notes"),
                findingsLabel,
                disclaimerPanel
        );
        return section;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Footer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the report footer containing the confidentiality notice,
     * page information, and company branding.
     */
    private static VBox buildFooter() {
        VBox footer = new VBox(6);
        footer.setPrefWidth(REPORT_WIDTH);
        footer.setPadding(new Insets(20, 36, 24, 36));
        footer.setAlignment(Pos.CENTER);
        footer.setStyle(
                "-fx-background-color: " + COLOR_CARD_BG + ";"  +
                        "-fx-border-color: " + COLOR_BORDER + ";"        +
                        "-fx-border-width: 1 0 0 0;"
        );

        Label confidentialLabel = new Label(
                "CONFIDENTIAL — This document contains proprietary AI analysis data. " +
                        "Unauthorized distribution is strictly prohibited."
        );
        confidentialLabel.setFont(Font.font("System", FontWeight.BOLD, 9.5));
        confidentialLabel.setStyle(
                "-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";" +
                        "-fx-letter-spacing: 0.5px;"
        );
        confidentialLabel.setAlignment(Pos.CENTER);
        confidentialLabel.setWrapText(true);

        Label copyrightLabel = new Label(
                "© " + LocalDateTime.now().getYear() +
                        " VisoLearn AI Studio  |  Powered by Deep Learning Inference Engine v2.1.0" +
                        "  |  Page 1 of 1"
        );
        copyrightLabel.setFont(Font.font("System", FontWeight.NORMAL, 9));
        copyrightLabel.setStyle("-fx-text-fill: #9CA3AF;");
        copyrightLabel.setAlignment(Pos.CENTER);

        footer.getChildren().addAll(confidentialLabel, copyrightLabel);
        return footer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a styled section title label with an emerald left accent bar.
     */
    private static HBox buildSectionTitle(String text) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Rectangle accent = new Rectangle(4, 20);
        accent.setFill(Color.web(COLOR_ACCENT));
        accent.setArcWidth(4);
        accent.setArcHeight(4);

        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, 15));
        label.setStyle("-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";");

        row.getChildren().addAll(accent, label);
        return row;
    }

    /**
     * Returns a thin horizontal divider line spanning the full report width.
     */
    private static Line buildDivider() {
        Line divider = new Line(0, 0, REPORT_WIDTH - 72, 0);
        divider.setStroke(Color.web(COLOR_BORDER));
        divider.setStrokeWidth(1);
        return divider;
    }

    /**
     * Returns a transparent spacer {@link Region} with the specified height.
     */
    private static Region buildSectionSpacer(double height) {
        Region spacer = new Region();
        spacer.setPrefHeight(height);
        spacer.setMinHeight(height);
        spacer.setMaxHeight(height);
        return spacer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File Dialog
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens a {@link FileChooser} save dialog and returns the selected
     * {@link File}, or {@code null} if the user cancelled.
     */
    private static File promptSaveLocation(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Clinical Report");
        chooser.setInitialFileName(
                "VisoLearn_Report_" +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) +
                        ".png"
        );
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG Image (*.png)", "*.png")
        );
        return chooser.showSaveDialog(owner);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility — find root StackPane for ToastUtil integration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to locate the root {@link StackPane} of the owner window's
     * scene for toast notification display. Falls back gracefully if not found.
     */
    private static javafx.scene.layout.StackPane findRootStackPane(Window owner) {
        if (owner != null
                && owner.getScene() != null
                && owner.getScene().getRoot() instanceof StackPane sp) {
            return sp;
        }
        return new StackPane();
    }
}
