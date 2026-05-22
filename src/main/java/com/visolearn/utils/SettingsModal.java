package com.visolearn.utils;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import com.visolearn.MainController;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Application settings modal with live preview and rollback on cancel.
 */
public final class SettingsModal {

    private static final String COLOR_ACCENT = "#10B981";
    private static final String COLOR_ACCENT_HOVER = "#059669";

    private static final double MODAL_WIDTH = 420;
    private static final double MODAL_SLIDE_OFFSET = -24;

    // Returns colors based on current theme
    private static String modalBg()           { return SettingsManager.isDarkMode() ? "#252533" : "#FFFFFF"; }
    private static String modalBorder()       { return SettingsManager.isDarkMode() ? "rgba(255,255,255,0.10)" : "rgba(15,23,42,0.10)"; }
    private static String textPrimary()       { return SettingsManager.isDarkMode() ? "#F8F9FA" : "#0F172A"; }
    private static String textSecondary()     { return SettingsManager.isDarkMode() ? "#9CA3AF" : "#64748B"; }
    private static String textMuted()         { return SettingsManager.isDarkMode() ? "#6B7280" : "#475569"; }
    private static String dividerColor()      { return SettingsManager.isDarkMode() ? "rgba(255,255,255,0.05)" : "rgba(15,23,42,0.08)"; }
    private static String controlBorder()     { return SettingsManager.isDarkMode() ? "rgba(255,255,255,0.15)" : "rgba(15,23,42,0.16)"; }
    private static String settingPanelBg()    { return SettingsManager.isDarkMode() ? "rgba(255,255,255,0.03)" : "rgba(15,23,42,0.04)"; }
    private static String sliderTrackBg()     { return SettingsManager.isDarkMode() ? "#374151" : "#CBD5E1"; }
    private static String toggleTrackOff()    { return SettingsManager.isDarkMode() ? "#4B5563" : "#94A3B8"; }
    private static String cancelBtnHoverBg()  { return SettingsManager.isDarkMode() ? "rgba(255,255,255,0.10)" : "rgba(15,23,42,0.10)"; }

    private SettingsModal() {
        throw new UnsupportedOperationException(
                "SettingsModal is a static utility class.");
    }

    public static void show(StackPane rootPane) {
        SettingsManager.Snapshot originalSettings = SettingsManager.snapshot();

        StackPane overlay = new StackPane();
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.60);");
        overlay.setPickOnBounds(true);

        Runnable cancelAction = () -> {
            SettingsManager.restore(originalSettings);
            playExitAnimation(rootPane, overlay);
        };
        Runnable saveAction = () -> {
            SettingsManager.persist();
            System.out.println("[SettingsModal] Settings saved to Preferences.");
            // Apply theme immediately so the user sees the change without restart
            if (rootPane.getScene() != null) {
                MainController.applyTheme(rootPane.getScene(), SettingsManager.isDarkMode());
            }
            playExitAnimation(rootPane, overlay);
        };

        VBox modalCard = buildModalCard(cancelAction, saveAction);
        modalCard.setOnMouseClicked(e -> e.consume());

        // FIX: JavaFX ComboBox popups render in a separate PopupWindow outside the scene.
        // When the user clicks a dropdown item the popup closes first, then a synthetic
        // mouse-released event lands on the overlay — triggering cancelAction by mistake.
        // We suppress the cancel for 300ms after any popup closes to prevent this.
        final boolean[] suppressCancel = {false};
        overlay.setOnMouseClicked(e -> {
            if (!suppressCancel[0]) {
                cancelAction.run();
            }
        });

        // Wire suppressCancel into all ComboBoxes inside the card
        modalCard.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                hookComboBoxSuppression(modalCard, suppressCancel);
            }
        });

        overlay.getChildren().add(modalCard);
        StackPane.setAlignment(modalCard, Pos.CENTER);
        rootPane.getChildren().add(overlay);

        // Hook after the card is added to the scene
        hookComboBoxSuppression(modalCard, suppressCancel);

        playEntranceAnimation(overlay, modalCard);
    }

    /** Walk all ComboBoxes inside a node tree and add showing/hidden listeners that
     *  temporarily suppress the overlay cancel click so dropdown selection works. */
    private static void hookComboBoxSuppression(javafx.scene.Node root, boolean[] suppressCancel) {
        if (root instanceof ComboBox<?> cb) {
            cb.showingProperty().addListener((obs, wasShowing, isShowing) -> {
                if (!isShowing && wasShowing) {
                    // Popup just closed — suppress the next overlay click for 300ms
                    suppressCancel[0] = true;
                    javafx.animation.PauseTransition pt =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
                    pt.setOnFinished(e -> suppressCancel[0] = false);
                    pt.play();
                }
            });
        }
        if (root instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                hookComboBoxSuppression(child, suppressCancel);
            }
        }
    }

    private static VBox buildModalCard(Runnable cancelAction, Runnable saveAction) {
        VBox card = new VBox(0);
        card.setMaxWidth(MODAL_WIDTH);
        card.setMinWidth(MODAL_WIDTH);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setAlignment(Pos.TOP_CENTER);
        card.setStyle(
                "-fx-background-color: " + modalBg() + ";" +
                        "-fx-background-radius: 14px;" +
                        "-fx-border-radius: 14px;" +
                        "-fx-border-color: " + modalBorder() + ";" +
                        "-fx-border-width: 1px;"
        );

        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.rgb(0, 0, 0, 0.65));
        shadow.setRadius(40);
        shadow.setOffsetY(16);
        shadow.setSpread(0.02);
        card.setEffect(shadow);

        VBox header = buildHeader(cancelAction);
        Region divider1 = buildDivider();
        VBox body = buildBody();
        Region divider2 = buildDivider();
        HBox footer = buildFooter(cancelAction, saveAction);

        card.getChildren().addAll(header, divider1, body, divider2, footer);
        card.setOpacity(0);
        card.setTranslateY(MODAL_SLIDE_OFFSET);

        return card;
    }

    private static VBox buildHeader(Runnable closeAction) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(20, 20, 20, 24));

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
        gearIcon.setFill(Color.web(COLOR_ACCENT));
        gearIcon.setScaleX(0.9);
        gearIcon.setScaleY(0.9);

        VBox titleBlock = new VBox(2);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        Label title = new Label("Application Settings");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        title.setStyle("-fx-text-fill: " + textPrimary() + ";");

        Label subtitle = new Label("Changes preview immediately");
        subtitle.setFont(Font.font("System", FontWeight.NORMAL, 11));
        subtitle.setStyle("-fx-text-fill: " + textMuted() + ";");

        titleBlock.getChildren().addAll(title, subtitle);
        row.getChildren().addAll(gearIcon, titleBlock, buildCloseButton(closeAction));

        return new VBox(row);
    }

    private static VBox buildBody() {
        VBox body = new VBox(14);
        body.setPadding(new Insets(20, 24, 20, 24));

        body.getChildren().addAll(
                buildOpacitySetting(),
                buildToggleSetting(
                        "Enable Fluid UI Animations",
                        "Smooth transitions, toasts, and motion effects throughout the app",
                        SettingsManager.isAnimationsEnabled(),
                        SettingsManager::setAnimationsEnabled
                ),
                buildResolutionSetting(),
                buildToggleSetting(
                        "Dark Mode",
                        "Switches the application between light and dark appearance",
                        SettingsManager.isDarkMode(),
                        SettingsManager::setDarkMode
                )
        );

        return body;
    }

    private static VBox buildOpacitySetting() {
        VBox panel = buildSettingPanel();

        HBox labelRow = new HBox();
        labelRow.setAlignment(Pos.CENTER_LEFT);

        VBox textBlock = buildTextBlock(
                "Occlusion Map Overlay Opacity",
                "Controls the intensity of the saliency heatmap overlay");
        HBox.setHgrow(textBlock, Priority.ALWAYS);

        Label valueReadout = new Label(
                String.format("%.0f%%", SettingsManager.getHeatmapOpacity() * 100));
        valueReadout.setFont(Font.font("System", FontWeight.BOLD, 12));
        valueReadout.setMinWidth(46);
        valueReadout.setAlignment(Pos.CENTER);
        valueReadout.setStyle(
                "-fx-text-fill: " + COLOR_ACCENT + ";" +
                        "-fx-background-color: rgba(16,185,129,0.12);" +
                        "-fx-background-radius: 5px;" +
                        "-fx-padding: 2 7 2 7;"
        );

        Slider slider = new Slider(0.0, 1.0, SettingsManager.getHeatmapOpacity());
        slider.setShowTickMarks(false);
        slider.setShowTickLabels(false);
        slider.setMajorTickUnit(0.25);
        slider.setBlockIncrement(0.05);
        slider.setMaxWidth(Double.MAX_VALUE);
        slider.setStyle(
                "-fx-control-inner-background: " + sliderTrackBg() + ";" +
                        "-fx-accent: " + COLOR_ACCENT + ";"
        );
        slider.valueProperty().addListener((obs, oldValue, value) -> {
            double opacity = value.doubleValue();
            valueReadout.setText(String.format("%.0f%%", opacity * 100));
            SettingsManager.setHeatmapOpacity(opacity);
        });

        labelRow.getChildren().addAll(textBlock, valueReadout);
        panel.getChildren().addAll(labelRow, slider);
        return panel;
    }

    private static HBox buildToggleSetting(
            String title, String description, boolean selected,
            Consumer<Boolean> onChanged) {

        VBox panel = buildSettingPanel();
        HBox.setHgrow(panel, Priority.ALWAYS);

        VBox textBlock = buildTextBlock(title, description);
        HBox.setHgrow(textBlock, Priority.ALWAYS);

        CheckBox checkBox = new CheckBox();
        checkBox.setSelected(selected);
        checkBox.selectedProperty().addListener((obs, oldValue, value) ->
                onChanged.accept(value));

        HBox row = new HBox(14, textBlock, buildTogglePill(checkBox));
        row.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().add(row);

        return new HBox(panel);
    }

    private static HBox buildResolutionSetting() {
        VBox panel = buildSettingPanel();
        HBox.setHgrow(panel, Priority.ALWAYS);

        Label nameLabel = buildSettingNameLabel("Report Export Resolution");
        Label descLabel = buildSettingDescLabel(
                "Sets the pixel density of exported clinical report images");

        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll("Standard (1x)", "High / Retina (2x)", "Ultra (3x)");
        String saved = SettingsManager.getExportResolution();
        combo.setValue(combo.getItems().contains(saved) ? saved : "High / Retina (2x)");
        combo.setMaxWidth(Double.MAX_VALUE);
        // Removed inline setStyle so ComboBox correctly relies on styles.css / styles-light.css
        // which avoids conflicts when swapping themes.
        combo.valueProperty().addListener((obs, oldValue, value) ->
                SettingsManager.setExportResolution(value));

        panel.getChildren().addAll(nameLabel, descLabel, combo);
        return new HBox(panel);
    }

    private static StackPane buildTogglePill(CheckBox checkBox) {
        Rectangle track = new Rectangle(44, 24);
        track.setArcWidth(24);
        track.setArcHeight(24);
        track.setFill(checkBox.isSelected()
                ? Color.web(COLOR_ACCENT)
                : Color.web(toggleTrackOff()));

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
        pill.setCursor(Cursor.HAND);

        checkBox.selectedProperty().addListener((obs, oldValue, selected) ->
                updateTogglePill(track, thumb, selected));
        pill.setOnMouseClicked(e -> checkBox.setSelected(!checkBox.isSelected()));

        checkBox.setVisible(false);
        checkBox.setManaged(false);

        return pill;
    }

    private static void updateTogglePill(Rectangle track, Rectangle thumb, boolean selected) {
        Color toColor = selected ? Color.web(COLOR_ACCENT) : Color.web(toggleTrackOff());
        double toX = selected ? 10 : -10;

        if (!SettingsManager.isAnimationsEnabled()) {
            track.setFill(toColor);
            thumb.setTranslateX(toX);
            return;
        }

        TranslateTransition slideThumb = new TranslateTransition(
                Duration.millis(180), thumb);
        slideThumb.setToX(toX);
        slideThumb.setInterpolator(Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0));

        Timeline colorAnim = new Timeline(
                new KeyFrame(Duration.millis(180),
                        new KeyValue(track.fillProperty(), toColor, Interpolator.EASE_BOTH))
        );

        new ParallelTransition(slideThumb, colorAnim).play();
    }

    private static HBox buildFooter(Runnable cancelAction, Runnable saveAction) {
        HBox footer = new HBox(12);
        footer.setPadding(new Insets(16, 24, 20, 24));
        footer.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = buildFooterButton("Cancel", false);
        cancelBtn.setOnAction(e -> cancelAction.run());

        Button saveBtn = buildFooterButton("Save Changes", true);
        saveBtn.setOnAction(e -> saveAction.run());

        footer.getChildren().addAll(cancelBtn, saveBtn);
        return footer;
    }

    private static Button buildCloseButton(Runnable closeAction) {
        Button button = new Button("x");
        button.setFont(Font.font("System", FontWeight.BOLD, 13));
        button.setCursor(Cursor.HAND);
        button.setPrefSize(32, 32);
        button.setMinSize(32, 32);
        button.setMaxSize(32, 32);
        button.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-background-radius: 50%;" +
                        "-fx-text-fill: " + textSecondary() + ";" +
                        "-fx-border-color: transparent;"
        );
        button.setOnAction(e -> closeAction.run());
        button.setOnMouseEntered(e -> button.setStyle(
                "-fx-background-color: " + (SettingsManager.isDarkMode() ? "rgba(255,255,255,0.10)" : "rgba(15,23,42,0.08)") + ";" +
                        "-fx-background-radius: 50%;" +
                        "-fx-text-fill: " + textPrimary() + ";" +
                        "-fx-border-color: transparent;"
        ));
        button.setOnMouseExited(e -> button.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-background-radius: 50%;" +
                        "-fx-text-fill: " + textSecondary() + ";" +
                        "-fx-border-color: transparent;"
        ));
        return button;
    }

    private static Button buildFooterButton(String text, boolean primary) {
        Button button = new Button(text);
        button.setFont(Font.font("System", primary ? FontWeight.BOLD : FontWeight.NORMAL, 13));
        button.setPrefHeight(38);
        button.setPrefWidth(primary ? 140 : 100);
        button.setCursor(Cursor.HAND);

        String base = primary
                ? "-fx-background-color: " + COLOR_ACCENT + ";" +
                  "-fx-background-radius: 8px;" +
                  "-fx-text-fill: #FFFFFF;" +
                  "-fx-font-size: 13px;" +
                  "-fx-font-weight: bold;"
                : "-fx-background-color: transparent;" +
                  "-fx-border-color: " + controlBorder() + ";" +
                  "-fx-border-radius: 8px;" +
                  "-fx-background-radius: 8px;" +
                  "-fx-text-fill: " + textSecondary() + ";" +
                  "-fx-font-size: 13px;";

        String hover = primary
                ? "-fx-background-color: " + COLOR_ACCENT_HOVER + ";" +
                  "-fx-background-radius: 8px;" +
                  "-fx-text-fill: #FFFFFF;" +
                  "-fx-font-size: 13px;" +
                  "-fx-font-weight: bold;"
                : "-fx-background-color: " + cancelBtnHoverBg() + ";" +
                  "-fx-border-color: " + controlBorder() + ";" +
                  "-fx-border-radius: 8px;" +
                  "-fx-background-radius: 8px;" +
                  "-fx-text-fill: " + textPrimary() + ";" +
                  "-fx-font-size: 13px;";

        button.setStyle(base);
        button.setOnMouseEntered(e -> button.setStyle(hover));
        button.setOnMouseExited(e -> button.setStyle(base));
        return button;
    }

    private static VBox buildSettingPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(14, 16, 14, 16));
        panel.setMaxWidth(Double.MAX_VALUE);
        panel.setStyle(
                "-fx-background-color: " + settingPanelBg() + ";" +
                        "-fx-background-radius: 9px;" +
                        "-fx-border-color: " + dividerColor() + ";" +
                        "-fx-border-radius: 9px;" +
                        "-fx-border-width: 1px;"
        );
        return panel;
    }

    private static VBox buildTextBlock(String title, String description) {
        return new VBox(2, buildSettingNameLabel(title), buildSettingDescLabel(description));
    }

    private static Label buildSettingNameLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));
        label.setStyle("-fx-text-fill: " + textPrimary() + ";");
        return label;
    }

    private static Label buildSettingDescLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.NORMAL, 11));
        label.setStyle("-fx-text-fill: " + textMuted() + ";");
        label.setWrapText(true);
        return label;
    }

    private static Region buildDivider() {
        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);
        divider.setStyle("-fx-background-color: " + dividerColor() + ";");
        return divider;
    }

    private static void playEntranceAnimation(StackPane overlay, VBox card) {
        if (!SettingsManager.isAnimationsEnabled()) {
            overlay.setOpacity(1.0);
            card.setOpacity(1.0);
            card.setTranslateY(0);
            return;
        }

        FadeTransition overlayFade = new FadeTransition(Duration.millis(220), overlay);
        overlayFade.setFromValue(0);
        overlayFade.setToValue(1);
        overlayFade.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition cardSlide = new TranslateTransition(Duration.millis(280), card);
        cardSlide.setFromY(MODAL_SLIDE_OFFSET);
        cardSlide.setToY(0);
        cardSlide.setInterpolator(Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0));

        FadeTransition cardFade = new FadeTransition(Duration.millis(280), card);
        cardFade.setFromValue(0);
        cardFade.setToValue(1);
        cardFade.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(overlayFade, cardSlide, cardFade).play();
    }

    private static void playExitAnimation(StackPane rootPane, StackPane overlay) {
        Node card = overlay.getChildren().isEmpty()
                ? overlay
                : overlay.getChildren().get(0);

        if (!SettingsManager.isAnimationsEnabled()) {
            rootPane.getChildren().remove(overlay);
            return;
        }

        TranslateTransition cardSlide = new TranslateTransition(Duration.millis(180), card);
        cardSlide.setToY(MODAL_SLIDE_OFFSET / 1.5);
        cardSlide.setInterpolator(Interpolator.EASE_IN);

        FadeTransition cardFade = new FadeTransition(Duration.millis(180), card);
        cardFade.setToValue(0);
        cardFade.setInterpolator(Interpolator.EASE_IN);

        FadeTransition overlayFade = new FadeTransition(Duration.millis(180), overlay);
        overlayFade.setToValue(0);
        overlayFade.setInterpolator(Interpolator.EASE_IN);

        ParallelTransition exit = new ParallelTransition(cardSlide, cardFade, overlayFade);
        exit.setOnFinished(e -> rootPane.getChildren().remove(overlay));
        exit.play();
    }
}