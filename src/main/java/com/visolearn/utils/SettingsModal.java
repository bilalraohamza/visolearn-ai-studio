package com.visolearn.utils;

import com.visolearn.MainController;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.prefs.Preferences;

/**
 * VisoLearn AI Studio — Application Settings Modal
 *
 * <p>Renders a full-screen semi-transparent overlay containing a centered
 * glass-card modal with animated entrance and exit transitions. The modal
 * is appended to—and removed from—the supplied root {@link StackPane}
 * without affecting any existing scene structure.</p>
 *
 * <p>When the user clicks "Save Changes", all preferences are persisted
 * via {@link java.util.prefs.Preferences}, and the selected color theme
 * is applied immediately to the current scene via
 * {@link com.visolearn.MainController#applyTheme(javafx.scene.Scene, String)}
 * without requiring an application restart.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * SettingsModal.show(rootStackPane);
 * }</pre>
 *
 * <p><b>No external libraries required.</b> Pure JavaFX with inline CSS.</p>
 */
public final class SettingsModal {

    // ─────────────────────────────────────────────────────────────────────────
    // Design Tokens — Deep Slate Theme
    // ─────────────────────────────────────────────────────────────────────────

    private static final String COLOR_OVERLAY_BG        = "rgba(0,0,0,0.60)";
    private static final String COLOR_MODAL_BG          = "#252533";
    private static final String COLOR_MODAL_BORDER      = "rgba(255,255,255,0.10)";
    private static final String COLOR_ACCENT_EMERALD    = "#10B981";
    private static final String COLOR_ACCENT_HOVER      = "#059669";
    private static final String COLOR_TEXT_PRIMARY      = "#F8F9FA";
    private static final String COLOR_TEXT_SECONDARY    = "#9CA3AF";
    private static final String COLOR_TEXT_MUTED        = "#6B7280";
    private static final String COLOR_DIVIDER           = "rgba(255,255,255,0.08)";
    private static final String COLOR_CONTROL_BG        = "#1E1E2A";
    private static final String COLOR_CONTROL_BORDER    = "rgba(255,255,255,0.12)";
    private static final String COLOR_SLIDER_TRACK      = "#374151";
    private static final String COLOR_CLOSE_BTN_HOVER   = "rgba(255,255,255,0.10)";

    // Animation
    private static final double ANIM_FADE_IN_MS         = 220;
    private static final double ANIM_SLIDE_IN_MS        = 280;
    private static final double ANIM_FADE_OUT_MS        = 180;
    private static final double MODAL_SLIDE_OFFSET_PX   = -24;

    // Layout
    private static final double MODAL_MAX_WIDTH         = 450;
    private static final double MODAL_MAX_HEIGHT        = 580;

    // ─────────────────────────────────────────────────────────────────────────
    // Control References — set by builder methods, read by saveBtn handler
    // ─────────────────────────────────────────────────────────────────────────

    private static Slider           opacitySlider;
    private static CheckBox         animCheckBox;
    private static ComboBox<String> resolutionCombo;
    private static ComboBox<String> themeCombo;

    // ─────────────────────────────────────────────────────────────────────────
    // Private Constructor
    // ─────────────────────────────────────────────────────────────────────────

    private SettingsModal() {
        throw new UnsupportedOperationException(
                "SettingsModal is a static utility class.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Displays the settings modal overlay on top of the given root pane.
     *
     * @param rootPane The scene's root {@link StackPane}. The overlay is added
     *                 as the top-most child and removed on close/save.
     */
    public static void show(StackPane rootPane) {

        // ── 1. Full-screen overlay backdrop ──────────────────────────────────
        StackPane overlay = buildOverlay();

        // ── 2. Glass-card modal ───────────────────────────────────────────────
        VBox modalCard = buildModalCard(rootPane, overlay);

        // ── 3. Centre the card inside the overlay ─────────────────────────────
        overlay.getChildren().add(modalCard);
        StackPane.setAlignment(modalCard, Pos.CENTER);

        // ── 4. Add overlay to scene root ──────────────────────────────────────
        rootPane.getChildren().add(overlay);

        // ── 5. Play entrance animation ────────────────────────────────────────
        playEntranceAnimation(overlay, modalCard);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overlay
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the semi-transparent full-screen backdrop.
     * Clicking the backdrop itself dismisses the modal (light-dismiss).
     */
    private static StackPane buildOverlay() {
        StackPane overlay = new StackPane();
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.60);");
        overlay.setPickOnBounds(true);
        return overlay;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modal Card
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs the fully styled modal card VBox containing all sections.
     */
    private static VBox buildModalCard(StackPane rootPane, StackPane overlay) {

        VBox card = new VBox(0);
        card.setMaxWidth(MODAL_MAX_WIDTH);
        card.setMinWidth(MODAL_MAX_WIDTH);
        card.setMaxHeight(MODAL_MAX_HEIGHT);
        card.setAlignment(Pos.TOP_CENTER);

        card.setStyle(
                "-fx-background-color: " + COLOR_MODAL_BG + ";"    +
                        "-fx-background-radius: 14px;"                      +
                        "-fx-border-radius: 14px;"                          +
                        "-fx-border-color: " + COLOR_MODAL_BORDER + ";"    +
                        "-fx-border-width: 1px;"
        );

        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.rgb(0, 0, 0, 0.65));
        shadow.setRadius(40);
        shadow.setOffsetY(16);
        shadow.setOffsetX(0);
        shadow.setSpread(0.02);
        card.setEffect(shadow);

        card.setOnMouseClicked(e -> e.consume());

        Runnable closeAction = () -> playExitAnimation(rootPane, overlay);

        VBox   header = buildHeader(closeAction);
        Region div1   = buildDivider();
        VBox   body   = buildBody();
        Region div2   = buildDivider();
        HBox   footer = buildFooter(closeAction, rootPane);

        VBox.setVgrow(body, Priority.ALWAYS);

        card.getChildren().addAll(header, div1, body, div2, footer);

        card.setOpacity(0);
        card.setTranslateY(MODAL_SLIDE_OFFSET_PX);

        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Header
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the modal header row: icon + title on the left, X button on the right.
     */
    private static VBox buildHeader(Runnable closeAction) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(20, 20, 20, 24));
        row.setSpacing(12);

        SVGPath gearIcon = new SVGPath();
        gearIcon.setContent(
                "M12 15.5A3.5 3.5 0 0 1 8.5 12 3.5 3.5 0 0 1 12 8.5a3.5 3.5 0 0 1 3.5 " +
                        "3.5 3.5 3.5 0 0 1-3.5 3.5m7.43-2.92c.04-.33.07-.67.07-1.08s-.03-.76-" +
                        ".07-1.08l2.32-1.84c.2-.16.25-.45.12-.68l-2.2-3.82c-.13-.22-.4-.3-.64-" +
                        ".22l-2.74 1.12c-.58-.44-1.2-.82-1.88-1.1L14 2.42C13.95 2.18 13.73 2 " +
                        "13.5 2h-4.4c-.23 0-.43.18-.47.42L8.3 5.38c-.68.28-1.3.66-1.88 1.1L3.68" +
                        " 5.36c-.24-.09-.5 0-.64.22l-2.2 3.82c-.14.23-.08.52.13.68l2.32 1.84c-" +
                        ".04.32-.07.66-.07 1.08s.03.76.07 1.08L.97 15.88c-.2.16-.26.45-.12.68l" +
                        "2.2 3.82c.13.22.4.3.64.22l2.74-1.12c.58.44 1.2.82 1.88 1.1l.37 2.96c" +
                        ".04.24.24.42.47.42h4.4c.24 0 .44-.18.47-.42l.38-2.96c.68-.28 1.3-.66 " +
                        "1.88-1.1l2.74 1.12c.24.08.5 0 .64-.22l2.2-3.82c.13-.23.07-.52-.13-.68" +
                        "l-2.32-1.84Z"
        );
        gearIcon.setFill(Color.web(COLOR_ACCENT_EMERALD));
        gearIcon.setScaleX(0.9);
        gearIcon.setScaleY(0.9);

        VBox titleBlock = new VBox(2);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        Label title = new Label("Application Settings");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        title.setStyle("-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";");

        Label subtitle = new Label("Customize your VisoLearn AI Studio experience");
        subtitle.setFont(Font.font("System", FontWeight.NORMAL, 11));
        subtitle.setStyle("-fx-text-fill: " + COLOR_TEXT_MUTED + ";");

        titleBlock.getChildren().addAll(title, subtitle);

        Button closeBtn = buildCloseButton(closeAction);

        row.getChildren().addAll(gearIcon, titleBlock, closeBtn);

        VBox wrapper = new VBox(row);
        wrapper.setPadding(Insets.EMPTY);
        return wrapper;
    }

    /**
     * Builds the circular close button with hover state.
     */
    private static Button buildCloseButton(Runnable closeAction) {
        Button btn = new Button("✕");
        btn.setFont(Font.font("System", FontWeight.BOLD, 13));
        btn.setCursor(javafx.scene.Cursor.HAND);
        btn.setPrefSize(32, 32);
        btn.setMinSize(32, 32);
        btn.setMaxSize(32, 32);

        String baseStyle =
                "-fx-background-color: transparent;"                +
                        "-fx-background-radius: 50%;"                       +
                        "-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";"     +
                        "-fx-border-color: transparent;"                    +
                        "-fx-cursor: hand;";

        String hoverStyle =
                "-fx-background-color: " + COLOR_CLOSE_BTN_HOVER + ";" +
                        "-fx-background-radius: 50%;"                           +
                        "-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";"           +
                        "-fx-border-color: transparent;"                        +
                        "-fx-cursor: hand;";

        btn.setStyle(baseStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(hoverStyle));
        btn.setOnMouseExited(e -> btn.setStyle(baseStyle));
        btn.setOnAction(e -> closeAction.run());

        return btn;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Body (Settings Controls)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the scrollable body containing all settings rows.
     */
    private static VBox buildBody() {
        VBox body = new VBox(6);
        body.setPadding(new Insets(20, 24, 20, 24));
        body.setSpacing(6);

        VBox opacityRow = buildSliderSetting(
                "Grad-CAM Overlay Opacity",
                "Controls the intensity of the saliency heatmap overlay",
                0.0, 1.0, 0.6
        );

        Region spacer1 = new Region();
        spacer1.setPrefHeight(8);

        HBox animRow = buildCheckBoxSetting(
                "Enable Fluid UI Animations",
                "Smooth transitions, toasts, and motion effects throughout the app",
                true
        );

        Region spacer2 = new Region();
        spacer2.setPrefHeight(8);

        HBox resolutionRow = buildComboBoxSetting(
                "Report Export Resolution",
                "Sets the pixel density of exported clinical report images",
                new String[]{"Standard (1×)", "High / Retina (2×)", "Ultra (3×)"},
                "High / Retina (2×)"
        );

        Region spacer3 = new Region();
        spacer3.setPrefHeight(8);

        HBox themeRow = buildComboBoxSetting(
                "Color Theme",
                "Visual theme applied across the entire application",
                new String[]{"Deep Slate (Dark)", "Midnight Blue (Dark)", "Clinical (Light)"},
                "Deep Slate (Dark)"
        );

        body.getChildren().addAll(
                opacityRow, spacer1,
                animRow,    spacer2,
                resolutionRow, spacer3,
                themeRow
        );

        return body;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setting Row Builders
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a labeled Slider setting row inside a styled card panel.
     */
    private static VBox buildSliderSetting(
            String title, String description,
            double min, double max, double defaultVal) {

        VBox panel = buildSettingPanel();

        HBox labelRow = new HBox();
        labelRow.setAlignment(Pos.CENTER_LEFT);

        VBox textBlock = new VBox(2);
        HBox.setHgrow(textBlock, Priority.ALWAYS);

        Label nameLabel = buildSettingNameLabel(title);
        Label descLabel = buildSettingDescLabel(description);
        textBlock.getChildren().addAll(nameLabel, descLabel);

        Label valueReadout = new Label(String.format("%.0f%%", defaultVal * 100));
        valueReadout.setFont(Font.font("System", FontWeight.BOLD, 12));
        valueReadout.setStyle(
                "-fx-text-fill: " + COLOR_ACCENT_EMERALD + ";"   +
                        "-fx-background-color: rgba(16,185,129,0.12);"   +
                        "-fx-background-radius: 5px;"                     +
                        "-fx-padding: 2 7 2 7;"
        );
        valueReadout.setMinWidth(46);
        valueReadout.setAlignment(Pos.CENTER);

        labelRow.getChildren().addAll(textBlock, valueReadout);

        Slider slider = new Slider(min, max, defaultVal);
        opacitySlider = slider;
        slider.setShowTickMarks(false);
        slider.setShowTickLabels(false);
        slider.setMajorTickUnit(0.25);
        slider.setBlockIncrement(0.05);
        slider.setMaxWidth(Double.MAX_VALUE);

        slider.setStyle(
                "-fx-control-inner-background: " + COLOR_SLIDER_TRACK + ";" +
                        "-fx-accent: " + COLOR_ACCENT_EMERALD + ";"
        );

        slider.valueProperty().addListener((obs, oldVal, newVal) ->
                valueReadout.setText(String.format("%.0f%%", newVal.doubleValue() * 100))
        );

        panel.getChildren().addAll(labelRow, slider);
        return panel;
    }

    /**
     * Builds a labeled CheckBox setting row inside a styled card panel.
     */
    private static HBox buildCheckBoxSetting(
            String title, String description, boolean defaultSelected) {

        VBox panel = buildSettingPanel();
        HBox.setHgrow(panel, Priority.ALWAYS);

        VBox textBlock = new VBox(2);
        HBox.setHgrow(textBlock, Priority.ALWAYS);

        Label nameLabel = buildSettingNameLabel(title);
        Label descLabel = buildSettingDescLabel(description);
        textBlock.getChildren().addAll(nameLabel, descLabel);

        CheckBox checkBox = new CheckBox();
        animCheckBox = checkBox;
        checkBox.setSelected(defaultSelected);
        checkBox.setStyle(
                "-fx-mark-color: white;"            +
                        "-fx-focus-color: transparent;"     +
                        "-fx-faint-focus-color: transparent;"
        );

        StackPane togglePill = buildTogglePill(checkBox);

        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(textBlock, togglePill);

        panel.getChildren().add(row);

        HBox outer = new HBox(panel);
        return outer;
    }

    /**
     * Builds a labeled ComboBox setting row inside a styled card panel.
     */
    private static HBox buildComboBoxSetting(
            String title, String description,
            String[] options, String defaultOption) {

        VBox panel = buildSettingPanel();
        HBox.setHgrow(panel, Priority.ALWAYS);

        Label nameLabel = buildSettingNameLabel(title);
        Label descLabel = buildSettingDescLabel(description);

        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(options);
        combo.setValue(defaultOption);

        if (title.equals("Report Export Resolution")) resolutionCombo = combo;
        else if (title.equals("Color Theme"))         themeCombo      = combo;

        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setStyle(
                "-fx-background-color: " + COLOR_CONTROL_BG + ";"    +
                        "-fx-border-color: " + COLOR_CONTROL_BORDER + ";"    +
                        "-fx-border-radius: 7px;"                             +
                        "-fx-background-radius: 7px;"                         +
                        "-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";"         +
                        "-fx-prompt-text-fill: " + COLOR_TEXT_MUTED + ";"    +
                        "-fx-font-size: 12px;"
        );

        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    setStyle(
                            "-fx-background-color: " + COLOR_CONTROL_BG + ";"  +
                                    "-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";"       +
                                    "-fx-font-size: 12px;"                              +
                                    "-fx-padding: 8 12 8 12;"
                    );
                }
            }
        });

        panel.getChildren().addAll(nameLabel, descLabel, combo);

        HBox outer = new HBox(panel);
        return outer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Toggle Pill Widget
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wraps a CheckBox in a custom animated on/off toggle pill.
     */
    private static StackPane buildTogglePill(CheckBox checkBox) {
        Rectangle track = new Rectangle(44, 24);
        track.setArcWidth(24);
        track.setArcHeight(24);
        track.setFill(checkBox.isSelected()
                ? Color.web(COLOR_ACCENT_EMERALD)
                : Color.web("#4B5563"));

        Rectangle thumb = new Rectangle(18, 18);
        thumb.setArcWidth(18);
        thumb.setArcHeight(18);
        thumb.setFill(Color.WHITE);
        thumb.setTranslateX(checkBox.isSelected() ? 10 : -10);

        DropShadow thumbShadow = new DropShadow();
        thumbShadow.setRadius(4);
        thumbShadow.setOffsetY(1);
        thumbShadow.setColor(Color.rgb(0, 0, 0, 0.3));
        thumb.setEffect(thumbShadow);

        StackPane pill = new StackPane(track, thumb);
        pill.setMinSize(44, 24);
        pill.setMaxSize(44, 24);
        pill.setCursor(javafx.scene.Cursor.HAND);

        pill.setOnMouseClicked(e -> {
            checkBox.setSelected(!checkBox.isSelected());
            boolean on = checkBox.isSelected();

            TranslateTransition slideThumb = new TranslateTransition(
                    Duration.millis(180), thumb);
            slideThumb.setToX(on ? 10 : -10);
            slideThumb.setInterpolator(Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0));

            Color fromColor = on ? Color.web("#4B5563") : Color.web(COLOR_ACCENT_EMERALD);
            Color toColor   = on ? Color.web(COLOR_ACCENT_EMERALD) : Color.web("#4B5563");

            Timeline colorAnim = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(track.fillProperty(), fromColor)),
                    new KeyFrame(Duration.millis(180),
                            new KeyValue(track.fillProperty(), toColor,
                                    Interpolator.EASE_BOTH))
            );

            new ParallelTransition(slideThumb, colorAnim).play();
        });

        checkBox.setVisible(false);
        checkBox.setManaged(false);

        return pill;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section: Footer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the modal footer with a "Cancel" ghost button and a styled
     * "Save Changes" primary button that persists all preferences and applies
     * the selected theme dynamically via {@link MainController#applyTheme}.
     */
    private static HBox buildFooter(Runnable closeAction, StackPane rootPane) {
        HBox footer = new HBox(12);
        footer.setPadding(new Insets(16, 24, 20, 24));
        footer.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setFont(Font.font("System", FontWeight.NORMAL, 13));
        cancelBtn.setPrefHeight(38);
        cancelBtn.setPrefWidth(100);
        cancelBtn.setCursor(javafx.scene.Cursor.HAND);

        String cancelBase =
                "-fx-background-color: transparent;"                       +
                        "-fx-border-color: " + COLOR_CONTROL_BORDER + ";"         +
                        "-fx-border-radius: 8px;"                                  +
                        "-fx-background-radius: 8px;"                              +
                        "-fx-text-fill: " + COLOR_TEXT_SECONDARY + ";"            +
                        "-fx-font-size: 13px;"                                     +
                        "-fx-cursor: hand;";

        String cancelHover =
                "-fx-background-color: rgba(255,255,255,0.06);"            +
                        "-fx-border-color: " + COLOR_CONTROL_BORDER + ";"         +
                        "-fx-border-radius: 8px;"                                  +
                        "-fx-background-radius: 8px;"                              +
                        "-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";"              +
                        "-fx-font-size: 13px;"                                     +
                        "-fx-cursor: hand;";

        cancelBtn.setStyle(cancelBase);
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle(cancelHover));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle(cancelBase));
        cancelBtn.setOnAction(e -> closeAction.run());

        Button saveBtn = new Button("Save Changes");
        saveBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        saveBtn.setPrefHeight(38);
        saveBtn.setPrefWidth(140);
        saveBtn.setCursor(javafx.scene.Cursor.HAND);

        String saveBase =
                "-fx-background-color: " + COLOR_ACCENT_EMERALD + ";"     +
                        "-fx-background-radius: 8px;"                              +
                        "-fx-border-color: transparent;"                           +
                        "-fx-border-radius: 8px;"                                  +
                        "-fx-text-fill: #FFFFFF;"                                  +
                        "-fx-font-size: 13px;"                                     +
                        "-fx-font-weight: bold;"                                   +
                        "-fx-cursor: hand;";

        String saveHover =
                "-fx-background-color: " + COLOR_ACCENT_HOVER + ";"       +
                        "-fx-background-radius: 8px;"                              +
                        "-fx-border-color: transparent;"                           +
                        "-fx-border-radius: 8px;"                                  +
                        "-fx-text-fill: #FFFFFF;"                                  +
                        "-fx-font-size: 13px;"                                     +
                        "-fx-font-weight: bold;"                                   +
                        "-fx-cursor: hand;";

        String savePressed =
                "-fx-background-color: #047857;"                           +
                        "-fx-background-radius: 8px;"                              +
                        "-fx-border-color: transparent;"                           +
                        "-fx-border-radius: 8px;"                                  +
                        "-fx-text-fill: #FFFFFF;"                                  +
                        "-fx-font-size: 13px;"                                     +
                        "-fx-font-weight: bold;"                                   +
                        "-fx-cursor: hand;";

        saveBtn.setStyle(saveBase);
        saveBtn.setOnMouseEntered(e -> saveBtn.setStyle(saveHover));
        saveBtn.setOnMouseExited(e -> saveBtn.setStyle(saveBase));
        saveBtn.setOnMousePressed(e -> saveBtn.setStyle(savePressed));
        saveBtn.setOnMouseReleased(e -> saveBtn.setStyle(saveHover));

        saveBtn.setOnAction(e -> {
            // ── Step 1: Persist all preferences ──────────────────────────────
            Preferences prefs = Preferences.userNodeForPackage(SettingsModal.class);

            if (opacitySlider   != null) prefs.putDouble("gradcam_opacity",     opacitySlider.getValue());
            if (animCheckBox    != null) prefs.putBoolean("animations_enabled", animCheckBox.isSelected());
            if (resolutionCombo != null) prefs.put("export_resolution",         resolutionCombo.getValue());
            if (themeCombo      != null) prefs.put("color_theme",               themeCombo.getValue());

            System.out.println("[SettingsModal] Settings saved to Preferences.");

            // ── Step 2: Apply theme immediately without restarting ───────────
            String theme = prefs.get("color_theme", "Deep Slate (Dark)");
            javafx.scene.Scene scene = rootPane.getScene();

            if (scene != null) {
                MainController.applyTheme(scene, theme);
            }

            // ── Step 3: Close the modal ──────────────────────────────────────
            closeAction.run();
        });

        footer.getChildren().addAll(cancelBtn, saveBtn);
        return footer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared Layout Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static VBox buildSettingPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(14, 16, 14, 16));
        panel.setMaxWidth(Double.MAX_VALUE);
        panel.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                        "-fx-background-radius: 9px;"                    +
                        "-fx-border-color: " + COLOR_DIVIDER + ";"      +
                        "-fx-border-radius: 9px;"                        +
                        "-fx-border-width: 1px;"
        );
        return panel;
    }

    private static Label buildSettingNameLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));
        l.setStyle("-fx-text-fill: " + COLOR_TEXT_PRIMARY + ";");
        return l;
    }

    private static Label buildSettingDescLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("System", FontWeight.NORMAL, 11));
        l.setStyle("-fx-text-fill: " + COLOR_TEXT_MUTED + ";");
        l.setWrapText(true);
        return l;
    }

    private static Region buildDivider() {
        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);
        divider.setStyle("-fx-background-color: " + COLOR_DIVIDER + ";");
        return divider;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animations
    // ─────────────────────────────────────────────────────────────────────────

    private static void playEntranceAnimation(StackPane overlay, VBox card) {

        FadeTransition overlayFade = new FadeTransition(
                Duration.millis(ANIM_FADE_IN_MS), overlay);
        overlayFade.setFromValue(0);
        overlayFade.setToValue(1);
        overlayFade.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition cardSlide = new TranslateTransition(
                Duration.millis(ANIM_SLIDE_IN_MS), card);
        cardSlide.setFromY(MODAL_SLIDE_OFFSET_PX);
        cardSlide.setToY(0);
        cardSlide.setInterpolator(Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0));

        FadeTransition cardFade = new FadeTransition(
                Duration.millis(ANIM_SLIDE_IN_MS), card);
        cardFade.setFromValue(0);
        cardFade.setToValue(1);
        cardFade.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition cardEntrance = new ParallelTransition(cardSlide, cardFade);
        new ParallelTransition(overlayFade, cardEntrance).play();
    }

    private static void playExitAnimation(StackPane rootPane, StackPane overlay) {

        javafx.scene.Node card = overlay.getChildren().isEmpty()
                ? overlay
                : overlay.getChildren().get(0);

        TranslateTransition cardSlide = new TranslateTransition(
                Duration.millis(ANIM_FADE_OUT_MS), card);
        cardSlide.setToY(MODAL_SLIDE_OFFSET_PX / 1.5);
        cardSlide.setInterpolator(Interpolator.EASE_IN);

        FadeTransition cardFade = new FadeTransition(
                Duration.millis(ANIM_FADE_OUT_MS), card);
        cardFade.setToValue(0);
        cardFade.setInterpolator(Interpolator.EASE_IN);

        FadeTransition overlayFade = new FadeTransition(
                Duration.millis(ANIM_FADE_OUT_MS), overlay);
        overlayFade.setToValue(0);
        overlayFade.setInterpolator(Interpolator.EASE_IN);

        ParallelTransition exit = new ParallelTransition(
                cardSlide, cardFade, overlayFade);

        exit.setOnFinished(e -> rootPane.getChildren().remove(overlay));
        exit.play();
    }
}