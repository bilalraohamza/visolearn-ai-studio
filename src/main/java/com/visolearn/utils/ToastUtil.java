package com.visolearn.utils;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * VisoLearn AI Studio — Reusable Toast Notification System
 *
 * Usage:
 *   ToastUtil.showToast(rootStackPane, "Analysis complete!", ToastUtil.ToastType.SUCCESS);
 *   ToastUtil.showToast(rootStackPane, "Connection failed.", ToastUtil.ToastType.ERROR);
 *   ToastUtil.showToast(rootStackPane, "Model is loading...",  ToastUtil.ToastType.INFO);
 *
 * Requirements:
 *   - The root node of your scene must be a StackPane.
 *   - All calls are safe to make from any thread (Platform.runLater is handled internally).
 */
public final class ToastUtil {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    /** How long the toast is fully visible before it begins to disappear. */
    private static final double VISIBLE_DURATION_MS   = 3000;

    /** Duration of the slide-in / slide-up transition. */
    private static final double SLIDE_IN_DURATION_MS  = 380;

    /** Duration of the combined fade-out + slide-down transition. */
    private static final double SLIDE_OUT_DURATION_MS = 320;

    /** Vertical distance (px) the toast travels during the slide animation. */
    private static final double SLIDE_DISTANCE_PX     = 60;

    /** Bottom margin between the toast and the edge of the root pane. */
    private static final double BOTTOM_MARGIN_PX       = 32;

    // ─────────────────────────────────────────────────────────────────────────
    // Toast Type Enum
    // ─────────────────────────────────────────────────────────────────────────

    public enum ToastType {

        /** Emerald green — confirms a successful operation. */
        SUCCESS(
                "#10B981",   // border / icon accent
                "✔",         // Unicode icon
                "Success"    // accessible label prefix
        ),

        /** Red — signals an error or failure. */
        ERROR(
                "#EF4444",
                "✖",
                "Error"
        ),

        /** Cyan — provides neutral, informational feedback. */
        INFO(
                "#00B4D8",
                "ℹ",
                "Info"
        );

        final String accentHex;
        final String icon;
        final String label;

        ToastType(String accentHex, String icon, String label) {
            this.accentHex = accentHex;
            this.icon      = icon;
            this.label     = label;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private Constructor — utility class, not instantiable
    // ─────────────────────────────────────────────────────────────────────────

    private ToastUtil() {
        throw new UnsupportedOperationException("ToastUtil is a static utility class.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Displays a self-dismissing toast notification anchored to the bottom-center
     * of the supplied {@code root} StackPane.
     *
     * <p>Thread-safe: may be called from background threads.</p>
     *
     * @param root    The {@link StackPane} that acts as the scene root.
     *                The toast is added as a top-most child and removed automatically.
     * @param message The text to display inside the toast.
     * @param type    One of {@link ToastType#SUCCESS}, {@link ToastType#ERROR},
     *                or {@link ToastType#INFO}.
     */
    public static void showToast(StackPane root, String message, ToastType type) {
        // Always manipulate the scene graph on the FX Application Thread.
        Platform.runLater(() -> buildAndAnimate(root, message, type));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs the toast node, attaches it to the root, and runs the full
     * entrance → pause → exit animation sequence.
     * Must be called on the FX Application Thread.
     */
    private static void buildAndAnimate(StackPane root, String message, ToastType type) {

        // ── 1. Build the toast HBox ──────────────────────────────────────────

        HBox toast = buildToastNode(message, type);

        // ── 2. Position: bottom-center, initially off-screen below ──────────

        // StackPane.setAlignment pins the toast to the bottom-center anchor.
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, BOTTOM_MARGIN_PX, 0));

        // Start the toast below its final position so it slides UP into view.
        toast.setTranslateY(SLIDE_DISTANCE_PX);
        toast.setOpacity(0);

        // ── 3. Add to scene graph ────────────────────────────────────────────

        root.getChildren().add(toast);

        // ── 4. Compose the animation sequence ───────────────────────────────

        SequentialTransition sequence = buildAnimationSequence(root, toast);
        sequence.play();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds and returns the fully styled toast {@link HBox}.
     */
    private static HBox buildToastNode(String message, ToastType type) {

        // ── Icon Label ───────────────────────────────────────────────────────

        Label iconLabel = new Label(type.icon);
        iconLabel.setStyle(
                "-fx-text-fill: " + type.accentHex + ";" +
                        "-fx-font-size: 14px;"                    +
                        "-fx-font-weight: bold;"
        );
        iconLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        // ── Message Label ────────────────────────────────────────────────────

        Label messageLabel = new Label(message);
        messageLabel.setStyle(
                "-fx-text-fill: #F8F9FA;"   +
                        "-fx-font-size: 13px;"      +
                        "-fx-font-weight: normal;"  +
                        "-fx-wrap-text: false;"
        );
        messageLabel.setFont(Font.font("System", FontWeight.NORMAL, 13));

        // Ensure the label is read by screen readers with a meaningful prefix.
        messageLabel.setAccessibleText(type.label + ": " + message);

        // ── Container HBox ───────────────────────────────────────────────────

        HBox toast = new HBox(10, iconLabel, messageLabel);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setPadding(new Insets(12, 20, 12, 16));
        toast.setMaxWidth(420);
        toast.setMinWidth(220);
        toast.setMaxHeight(44);

        /*
         * Inline CSS breakdown:
         *
         *  -fx-background-color     : Deep Slate surface tone (#252533)
         *  -fx-background-radius    : "pill" rounded corners
         *  -fx-border-color         : transparent on all sides except the left
         *                             accent stripe matching the ToastType
         *  -fx-border-width         : 0 on R/T/B, 4px on L
         *  -fx-border-radius        : mirrors background radius for clean join
         */
        toast.setStyle(
                "-fx-background-color: #252533;"                                      +
                        "-fx-background-radius: 8px;"                                         +
                        "-fx-border-color: transparent transparent transparent "
                        + type.accentHex + ";"                               +
                        "-fx-border-width: 0 0 0 4;"                                          +
                        "-fx-border-radius: 8px;"
        );

        // ── Drop Shadow Effect ───────────────────────────────────────────────

        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.rgb(0, 0, 0, 0.55));
        shadow.setRadius(18);
        shadow.setOffsetY(6);
        shadow.setOffsetX(0);
        shadow.setSpread(0.05);
        toast.setEffect(shadow);

        // ── Mouse passthrough guard ──────────────────────────────────────────
        // Prevent the toast from accidentally blocking clicks on content below
        // when it is fully transparent (during fade-out).
        toast.setMouseTransparent(false);

        return toast;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs the full three-phase {@link SequentialTransition}:
     * <ol>
     *   <li><b>Entrance</b> — slide up + fade in simultaneously.</li>
     *   <li><b>Hold</b>     — pause so the user can read the message.</li>
     *   <li><b>Exit</b>     — fade out + slide down simultaneously,
     *                         then remove the node from the root.</li>
     * </ol>
     */
    private static SequentialTransition buildAnimationSequence(StackPane root, HBox toast) {

        // ── Phase 1 : Entrance (parallel slide-up + fade-in) ─────────────────

        TranslateTransition slideIn = new TranslateTransition(
                Duration.millis(SLIDE_IN_DURATION_MS), toast
        );
        slideIn.setFromY(SLIDE_DISTANCE_PX);
        slideIn.setToY(0);
        slideIn.setInterpolator(Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0)); // ease-out curve

        FadeTransition fadeIn = new FadeTransition(
                Duration.millis(SLIDE_IN_DURATION_MS), toast
        );
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.setInterpolator(Interpolator.EASE_OUT);

        ParallelTransition entrance = new ParallelTransition(slideIn, fadeIn);

        // ── Phase 2 : Hold (the toast stays fully visible) ───────────────────

        PauseTransition hold = new PauseTransition(Duration.millis(VISIBLE_DURATION_MS));

        // ── Phase 3 : Exit (parallel slide-down + fade-out) ──────────────────

        TranslateTransition slideOut = new TranslateTransition(
                Duration.millis(SLIDE_OUT_DURATION_MS), toast
        );
        slideOut.setFromY(0);
        slideOut.setToY(SLIDE_DISTANCE_PX);
        slideOut.setInterpolator(Interpolator.SPLINE(0.4, 0.0, 0.8, 0.2)); // ease-in curve

        FadeTransition fadeOut = new FadeTransition(
                Duration.millis(SLIDE_OUT_DURATION_MS), toast
        );
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setInterpolator(Interpolator.EASE_IN);

        ParallelTransition exit = new ParallelTransition(slideOut, fadeOut);

        // ── Compose & wire cleanup ────────────────────────────────────────────

        SequentialTransition sequence = new SequentialTransition(entrance, hold, exit);

        /*
         * After the exit transition completes, safely remove the toast node
         * from the scene graph. This prevents an invisible node from lingering
         * and consuming memory or blocking mouse events.
         */
        sequence.setOnFinished(event -> root.getChildren().remove(toast));

        return sequence;
    }
}