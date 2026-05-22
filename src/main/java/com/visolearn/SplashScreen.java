package com.visolearn;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * VisoLearn AI Studio — Premium Startup Splash Screen.
 *
 * <p>Uses a fully custom progress bar (two {@link Region} objects animated
 * via {@link Timeline}) so the fill colour and animation are fully reliable,
 * unlike the standard JavaFX {@link javafx.scene.control.ProgressBar} whose
 * {@code -fx-accent} inline style is silently ignored.</p>
 *
 * @author Rao Hamza Bilal
 * @version 2.0
 */
public class SplashScreen {

    // ─────────────────────────────────────────────────────────────────────────
    // Layout constants
    // ─────────────────────────────────────────────────────────────────────────

    private static final double CARD_W = 700;
    private static final double CARD_H = 380;
    private static final double BAR_W  = CARD_W - 120;  // fill region max width

    // ─────────────────────────────────────────────────────────────────────────
    // Internal state
    // ─────────────────────────────────────────────────────────────────────────

    private final Stage     stage;
    private       Label     statusLabel;
    private       Region    fillBar;         // actual coloured fill
    private       Label     percentLabel;

    private Timeline        fillTimeline;    // animates fillBar.prefWidth
    private Timeline        crawlTimeline;   // slow background crawl
    private Timeline        dotTimeline;     // "…" cycling animation
    private Timeline        topBarTimeline;  // top accent gradient shift

    private double          currentProgress = 0.0;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public SplashScreen() {
        stage = new Stage(StageStyle.UNDECORATED);
        stage.setAlwaysOnTop(true);

        // ── Outer transparent root ───────────────────────────────────────────
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: transparent;");
        root.setPadding(new Insets(24));   // space for drop shadow

        // ── Card ─────────────────────────────────────────────────────────────
        VBox card = new VBox(0);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPrefSize(CARD_W, CARD_H);
        card.setStyle(
            "-fx-background-radius: 18;" +
            "-fx-background-color: #252533;" +
            "-fx-border-color: rgba(255,255,255,0.07);" +
            "-fx-border-radius: 18;" +
            "-fx-border-width: 1;"
        );

        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.rgb(0, 0, 0, 0.60));
        shadow.setRadius(40);
        shadow.setOffsetY(10);
        shadow.setSpread(0.03);
        card.setEffect(shadow);

        // ── Animated top gradient bar ─────────────────────────────────────────
        Rectangle topBar = new Rectangle(CARD_W, 5);
        topBar.setArcWidth(36);
        topBar.setArcHeight(36);
        // Logo gradient: blue → green (matches .logo-icon in styles.css)
        topBar.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#4d9de0")),
                new Stop(0.5, Color.web("#10B981")),
                new Stop(1, Color.web("#22c55e"))));
        // Clip top corners
        Rectangle topClip = new Rectangle(CARD_W, 5);
        topClip.setArcWidth(36);
        topClip.setArcHeight(36);
        topBar.setClip(topClip);

        // Hue-cycle animation on the top bar (blue→green ↔ green→blue)
        topBarTimeline = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(topBar.fillProperty(),
                    new LinearGradient(0,0,1,0,true,CycleMethod.NO_CYCLE,
                        new Stop(0,Color.web("#4d9de0")),
                        new Stop(0.5,Color.web("#10B981")),
                        new Stop(1,Color.web("#22c55e"))))),
            new KeyFrame(Duration.seconds(2.5),
                new KeyValue(topBar.fillProperty(),
                    new LinearGradient(0,0,1,0,true,CycleMethod.NO_CYCLE,
                        new Stop(0,Color.web("#22c55e")),
                        new Stop(0.5,Color.web("#10B981")),
                        new Stop(1,Color.web("#4d9de0"))))),
            new KeyFrame(Duration.seconds(5),
                new KeyValue(topBar.fillProperty(),
                    new LinearGradient(0,0,1,0,true,CycleMethod.NO_CYCLE,
                        new Stop(0,Color.web("#4d9de0")),
                        new Stop(0.5,Color.web("#10B981")),
                        new Stop(1,Color.web("#22c55e")))))
        );
        topBarTimeline.setCycleCount(Timeline.INDEFINITE);

        // ── Glow orbs in background (decorative) ─────────────────────────────
        StackPane cardWithOrbs = new StackPane();
        cardWithOrbs.setMaxSize(CARD_W, CARD_H);
        cardWithOrbs.setMinSize(CARD_W, CARD_H);

        // Blue orb (top-left) — matches logo-icon blue #4d9de0
        Region orbA = new Region();
        orbA.setPrefSize(200, 200);
        orbA.setStyle("-fx-background-color: radial-gradient(focus-angle 0deg, " +
                "focus-distance 0%, center 50% 50%, radius 50%, #4d9de030, transparent);");
        GaussianBlur blurA = new GaussianBlur(55);
        orbA.setEffect(blurA);
        StackPane.setAlignment(orbA, Pos.TOP_LEFT);
        StackPane.setMargin(orbA, new Insets(-20, 0, 0, -20));

        // Emerald orb (bottom-right) — matches primary #10B981 / #22c55e
        Region orbB = new Region();
        orbB.setPrefSize(170, 170);
        orbB.setStyle("-fx-background-color: radial-gradient(focus-angle 0deg, " +
                "focus-distance 0%, center 50% 50%, radius 50%, #10B98128, transparent);");
        GaussianBlur blurB = new GaussianBlur(50);
        orbB.setEffect(blurB);
        StackPane.setAlignment(orbB, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(orbB, new Insets(0, -20, -20, 0));

        cardWithOrbs.getChildren().addAll(orbA, orbB, card);

        // ── Brand / logo area ─────────────────────────────────────────────────
        VBox brandBox = new VBox(12);
        brandBox.setAlignment(Pos.CENTER);
        VBox.setMargin(brandBox, new Insets(50, 40, 0, 40)); // extra top margin since logo is gone

        Label appName = new Label("VisoLearn AI Studio");
        appName.setStyle(
            "-fx-font-family: 'Segoe UI', 'Inter', Roboto, sans-serif;" +
            "-fx-font-size: 34px;" +
            "-fx-font-weight: 800;" +
            "-fx-text-fill: #FFFFFF;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0, 0, 4);"
        );

        // Separator dots
        HBox tagRow = new HBox(12);
        tagRow.setAlignment(Pos.CENTER);
        Label dot1 = styledDot();
        Label tag  = new Label("Dermoscopy AI  ·  EfficientNet-B4  +  DenseNet-169");
        tag.setStyle("-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-font-size: 13px; -fx-text-fill: #9CA3AF; -fx-font-weight: 500;");
        Label dot2 = styledDot();
        tagRow.getChildren().addAll(dot1, tag, dot2);

        brandBox.getChildren().addAll(appName, tagRow);

        // ── Progress section ──────────────────────────────────────────────────
        VBox progressBox = new VBox(16);
        progressBox.setAlignment(Pos.CENTER);
        VBox.setMargin(progressBox, new Insets(45, 60, 0, 60)); // extra top margin

        // Status row (label + dots)
        HBox statusRow = new HBox(0);
        statusRow.setAlignment(Pos.CENTER);
        statusLabel = new Label("Initializing");
        statusLabel.setStyle(
            "-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;" +
            "-fx-font-size: 13px;" +
            "-fx-text-fill: #D1D5DB;" +
            "-fx-font-weight: 600;"
        );
        statusRow.getChildren().add(statusLabel);

        // Custom progress bar — statically rendered at 100%
        StackPane barPane = buildProgressBar();

        progressBox.getChildren().addAll(statusRow, barPane);

        // ── Footer ────────────────────────────────────────────────────────────
        HBox footer = new HBox();
        footer.setAlignment(Pos.CENTER);
        VBox.setMargin(footer, new Insets(30, 40, 20, 40));
        Label footerLabel = new Label(
            "v1.2   ·   © 2026 Rao Hamza Bilal   ·   Trained on ISIC HAM10000 Dataset");
        footerLabel.setStyle(
            "-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;" +
            "-fx-font-size: 12px;" +
            "-fx-text-fill: #4B5563;"
        );
        footer.getChildren().add(footerLabel);

        card.getChildren().addAll(topBar, brandBox, progressBox, footer);

        // Add the assembled card to the root pane!
        root.getChildren().add(cardWithOrbs);

        // ── Scene ─────────────────────────────────────────────────────────────
        Scene scene = new Scene(root, CARD_W + 48, CARD_H + 48);
        scene.setFill(Color.web("#1A1A24"));
        stage.setScene(scene);
        stage.centerOnScreen();

        // Start the top bar animation
        topBarTimeline.play();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Shows the splash. Must be called on the FX thread. */
    public void show() {
        stage.getScene().getRoot().setOpacity(1);
        stage.show();
        startDotAnimation();
    }

    /**
     * Updates the status text. Thread-safe.
     */
    public void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    /** Fades out and closes the splash screen. Thread-safe. */
    public void dismiss() {
        Platform.runLater(() -> {
            stopAll();

            PauseTransition hold = new PauseTransition(Duration.millis(500));
            hold.setOnFinished(e -> {
                FadeTransition fo = new FadeTransition(
                        Duration.millis(480), stage.getScene().getRoot());
                fo.setToValue(0);
                fo.setOnFinished(ev -> {
                    topBarTimeline.stop();
                    stage.close();
                });
                fo.play();
            });
            hold.play();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Builds and returns the custom progress bar pane, fixed at 100% width. */
    private StackPane buildProgressBar() {
        // Track (dark background strip)
        Region track = new Region();
        track.setPrefWidth(BAR_W);
        track.setPrefHeight(6); // Thinner line
        track.setStyle(
            "-fx-background-color: #1E1E2A;" +
            "-fx-background-radius: 4;"
        );

        // Fill (coloured static bar) — matching the app's Emerald primary color
        fillBar = new Region();
        fillBar.setPrefHeight(6);
        fillBar.setPrefWidth(BAR_W); // 100% width
        fillBar.setMaxWidth(BAR_W);
        fillBar.setStyle(
            "-fx-background-color: linear-gradient(to right, #4d9de0, #10B981, #22c55e);" +
            "-fx-background-radius: 4;"
        );

        // Stronger glow effect on fill matching the app color
        DropShadow glow = new DropShadow();
        glow.setColor(Color.web("#10B981", 0.55));
        glow.setRadius(12);
        glow.setSpread(0.3);
        fillBar.setEffect(glow);

        StackPane bar = new StackPane();
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefWidth(BAR_W);
        bar.setPrefHeight(6);
        bar.getChildren().addAll(track, fillBar);

        return bar;
    }

    /** Small accent dot. */
    private Label styledDot() {
        Label d = new Label("•");
        d.setStyle("-fx-text-fill: rgba(77,157,224,0.6); -fx-font-size: 14px;");
        return d;
    }

    /** Cycles "…" → " …" → "  …" on the status label as a loading indicator. */
    private void startDotAnimation() {
        String[] dots = {"   ", ".  ", ".. ", "..."};
        final int[] idx = {0};
        dotTimeline = new Timeline(
            new KeyFrame(Duration.millis(450), e -> {
                String base = statusLabel.getText().replaceAll("[\\.\\s]+$", "");
                statusLabel.setText(base + dots[idx[0] % dots.length]);
                idx[0]++;
            })
        );
        dotTimeline.setCycleCount(Timeline.INDEFINITE);
        dotTimeline.play();
    }

    private void stopAll() {
        if (fillTimeline  != null) fillTimeline.stop();
        if (crawlTimeline != null) crawlTimeline.stop();
        if (dotTimeline   != null) dotTimeline.stop();
    }
}
