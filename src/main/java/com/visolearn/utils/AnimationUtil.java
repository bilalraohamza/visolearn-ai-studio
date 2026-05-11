package com.visolearn.utils;

import javafx.animation.*;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.util.Duration;

public class AnimationUtil {

    /*
     * Simple Fade In
     */
    public static void fadeIn(Node node, int duration) {

        node.setOpacity(0);

        FadeTransition fade =
                new FadeTransition(Duration.millis(duration), node);

        fade.setFromValue(0);
        fade.setToValue(1);

        fade.play();
    }

    /*
     * Slide Up + Fade
     */
    public static void slideUp(Node node, int duration) {

        node.setOpacity(0);
        node.setTranslateY(20);

        TranslateTransition slide =
                new TranslateTransition(Duration.millis(duration), node);

        slide.setFromY(20);
        slide.setToY(0);

        FadeTransition fade =
                new FadeTransition(Duration.millis(duration), node);

        fade.setFromValue(0);
        fade.setToValue(1);

        ParallelTransition animation =
                new ParallelTransition(slide, fade);

        animation.play();
    }

    /*
     * Button Hover Animation
     */
    public static void applyButtonHover(Node node) {

        node.setOnMouseEntered(e -> {

            ScaleTransition scale =
                    new ScaleTransition(Duration.millis(150), node);

            scale.setToX(1.03);
            scale.setToY(1.03);

            scale.play();
        });

        node.setOnMouseExited(e -> {

            ScaleTransition scale =
                    new ScaleTransition(Duration.millis(150), node);

            scale.setToX(1.0);
            scale.setToY(1.0);

            scale.play();
        });
    }

    /*
     * Pulse Animation
     */
    public static void pulse(Node node) {

        ScaleTransition pulse =
                new ScaleTransition(Duration.seconds(1.2), node);

        pulse.setFromX(1.0);
        pulse.setToX(1.08);

        pulse.setFromY(1.0);
        pulse.setToY(1.08);

        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);

        pulse.play();
    }

    /*
     * Animated Counter
     */
    public static void animateCounter(Label label,
                                      double start,
                                      double end,
                                      int duration,
                                      String suffix) {

        Timeline timeline = new Timeline();

        int frames = 60;

        for (int i = 0; i <= frames; i++) {

            double progress = (double) i / frames;
            double value = start + (end - start) * progress;

            KeyFrame keyFrame = new KeyFrame(
                    Duration.millis((double) duration / frames * i),
                    e -> label.setText(
                            String.format("%.2f%s", value, suffix)
                    )
            );

            timeline.getKeyFrames().add(keyFrame);
        }

        timeline.play();
    }

    /*
     * Progress Bar Animation
     */
    public static void animateProgressBar(javafx.scene.control.ProgressBar bar,
                                          double value,
                                          int duration) {

        Timeline timeline = new Timeline(
                new KeyFrame(
                        Duration.millis(duration),
                        new KeyValue(bar.progressProperty(), value)
                )
        );

        timeline.play();
    }
    public static boolean animationsEnabled() {
        return java.util.prefs.Preferences
                .userNodeForPackage(com.visolearn.utils.SettingsModal.class)
                .getBoolean("animations_enabled", true);
    }
}
