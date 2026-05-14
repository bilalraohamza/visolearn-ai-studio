package com.visolearn.utils;

import javafx.animation.*;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.util.Duration;

/**
 * Centralized animation utility for VisoLearn AI Studio.
 *
 * <p>All public methods are guard-gated by {@link #animationsEnabled()}. When
 * animations are disabled, each method applies its final visual state
 * immediately with no transition.</p>
 *
 * <p>All methods are safe to call on the JavaFX Application Thread only.</p>
 */
public final class AnimationUtil {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    /** Easing curve used for slide and fade entrance transitions. */
    private static final Interpolator EASE_OUT_CUBIC =
            Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0);

    /** Scale factor applied by {@link #pulse(Node)}. */
    private static final double PULSE_SCALE = 1.08;

    /** Scale factor applied on button hover-enter. */
    private static final double HOVER_SCALE = 1.03;

    /** Number of key frames used by {@link #animateCounter}. */
    private static final int COUNTER_FRAMES = 60;

    /** Vertical translation offset (px) used by {@link #slideUp}. */
    private static final double SLIDE_OFFSET_PX = 20.0;

    // ─────────────────────────────────────────────────────────────────────────
    // Private Constructor
    // ─────────────────────────────────────────────────────────────────────────

    private AnimationUtil() {
        throw new UnsupportedOperationException(
                "AnimationUtil is a static utility class.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fades a node from fully transparent to fully opaque.
     *
     * <p>If animations are disabled, the node is set to {@code opacity = 1}
     * immediately with no transition.</p>
     *
     * @param node     The target {@link Node}.
     * @param duration Transition duration in milliseconds.
     */
    public static void fadeIn(Node node, int duration) {
        if (!animationsEnabled()) {
            node.setOpacity(1.0);
            return;
        }

        node.setOpacity(0.0);

        FadeTransition fade = new FadeTransition(Duration.millis(duration), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setInterpolator(Interpolator.EASE_OUT);
        fade.play();
    }

    /**
     * Fades a node from fully opaque to fully transparent.
     *
     * <p>If animations are disabled, the node is set to {@code opacity = 0}
     * immediately with no transition.</p>
     *
     * @param node     The target {@link Node}.
     * @param duration Transition duration in milliseconds.
     */
    public static void fadeOut(Node node, int duration) {
        if (!animationsEnabled()) {
            node.setOpacity(0.0);
            return;
        }

        node.setOpacity(1.0);

        FadeTransition fade = new FadeTransition(Duration.millis(duration), node);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setInterpolator(Interpolator.EASE_IN);
        fade.play();
    }

    /**
     * Slides a node upward from a vertical offset while simultaneously fading it in.
     *
     * <p>If animations are disabled, the node is snapped to its final position
     * ({@code translateY = 0}, {@code opacity = 1}) immediately.</p>
     *
     * @param node     The target {@link Node}.
     * @param duration Transition duration in milliseconds.
     */
    public static void slideUp(Node node, int duration) {
        if (!animationsEnabled()) {
            node.setTranslateY(0.0);
            node.setOpacity(1.0);
            return;
        }

        node.setOpacity(0.0);
        node.setTranslateY(SLIDE_OFFSET_PX);

        TranslateTransition slide = new TranslateTransition(Duration.millis(duration), node);
        slide.setFromY(SLIDE_OFFSET_PX);
        slide.setToY(0.0);
        slide.setInterpolator(EASE_OUT_CUBIC);

        FadeTransition fade = new FadeTransition(Duration.millis(duration), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(slide, fade).play();
    }

    /**
     * Attaches mouse-enter and mouse-exit handlers to a node that produce a
     * subtle scale-up effect on hover, reinforcing interactivity for buttons
     * and clickable cards.
     *
     * <p>If animations are disabled, no handlers are attached and the node
     * scale remains at its default {@code 1.0}.</p>
     *
     * @param node The target {@link Node} (typically a {@code Button} or {@code HBox}).
     */
    public static void applyButtonHover(Node node) {
        ScaleTransition scaleIn = new ScaleTransition(Duration.millis(150), node);
        scaleIn.setToX(HOVER_SCALE);
        scaleIn.setToY(HOVER_SCALE);
        scaleIn.setInterpolator(Interpolator.EASE_OUT);

        ScaleTransition scaleOut = new ScaleTransition(Duration.millis(150), node);
        scaleOut.setToX(1.0);
        scaleOut.setToY(1.0);
        scaleOut.setInterpolator(Interpolator.EASE_OUT);

        node.setOnMouseEntered(e -> {
            if (!animationsEnabled()) {
                node.setScaleX(1.0);
                node.setScaleY(1.0);
                return;
            }
            scaleOut.stop();
            scaleIn.play();
        });

        node.setOnMouseExited(e -> {
            if (!animationsEnabled()) {
                node.setScaleX(1.0);
                node.setScaleY(1.0);
                return;
            }
            scaleIn.stop();
            scaleOut.play();
        });
    }

    /**
     * Starts an indefinite, auto-reversing scale pulse on a node — used to
     * draw attention to status indicators or live-inference badges.
     *
     * <p>If animations are disabled, this method is a no-op; the node remains
     * at its natural scale.</p>
     *
     * <p><b>Note:</b> The returned {@link ScaleTransition} should be stored by
     * the caller and stopped via {@code .stop()} when the node is removed from
     * the scene, to prevent the animation from holding a reference to a
     * detached node.</p>
     *
     * @param node The target {@link Node}.
     * @return The running {@link ScaleTransition}, or {@code null} if animations
     *         are disabled (so the caller can safely null-check before stopping).
     */
    public static ScaleTransition pulse(Node node) {
        if (!animationsEnabled()) {
            return null;
        }

        ScaleTransition pulse = new ScaleTransition(Duration.seconds(1.2), node);
        pulse.setFromX(1.0);
        pulse.setToX(PULSE_SCALE);
        pulse.setFromY(1.0);
        pulse.setToY(PULSE_SCALE);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.setInterpolator(Interpolator.EASE_BOTH);
        pulse.play();

        return pulse;
    }

    /**
     * Animates a {@link Label} to count from {@code start} to {@code end}
     * over the given duration, appending {@code suffix} after each value.
     *
     * <p>Uses {@link #COUNTER_FRAMES} evenly spaced {@link KeyFrame} instances
     * to produce a smooth linear count. If animations are disabled, the label
     * text is set to the final value immediately.</p>
     *
     * @param label    The {@link Label} whose text will be updated each frame.
     * @param start    The numeric value at the beginning of the animation.
     * @param end      The numeric value at the end of the animation.
     * @param duration Total animation duration in milliseconds.
     * @param suffix   String appended to each formatted value (e.g., {@code "%"} or {@code " ms"}).
     */
    public static void animateCounter(Label label, double start, double end,
                                      int duration, String suffix) {
        if (!animationsEnabled()) {
            label.setText(String.format("%.2f%s", end, suffix));
            return;
        }

        Timeline timeline = new Timeline();
        double frameInterval = (double) duration / COUNTER_FRAMES;

        for (int i = 0; i <= COUNTER_FRAMES; i++) {
            double progress = (double) i / COUNTER_FRAMES;
            double value    = start + (end - start) * progress;

            KeyFrame keyFrame = new KeyFrame(
                    Duration.millis(frameInterval * i),
                    e -> label.setText(String.format("%.2f%s", value, suffix))
            );

            timeline.getKeyFrames().add(keyFrame);
        }

        timeline.play();
    }

    /**
     * Animates a {@link ProgressBar}'s progress property from its current
     * value to {@code targetValue} over the given duration.
     *
     * <p>If animations are disabled, the progress is set directly with no
     * transition.</p>
     *
     * @param bar          The {@link ProgressBar} to animate.
     * @param targetValue  Target progress value in the range {@code [0.0, 1.0]}.
     * @param duration     Transition duration in milliseconds.
     */
    public static void animateProgressBar(ProgressBar bar, double targetValue, int duration) {
        if (!animationsEnabled()) {
            bar.setProgress(targetValue);
            return;
        }

        Timeline timeline = new Timeline(
                new KeyFrame(
                        Duration.millis(duration),
                        new KeyValue(bar.progressProperty(), targetValue, Interpolator.EASE_BOTH)
                )
        );
        timeline.play();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preferences Gate
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if fluid UI animations are enabled in the user's
     * application preferences, as configured via {@link SettingsModal}.
     *
     * <p>Defaults to {@code true} if no preference has been saved yet.</p>
     *
     * @return {@code true} if animations should run; {@code false} to skip them.
     */
    public static boolean animationsEnabled() {
        return SettingsManager.isAnimationsEnabled();
    }
}
