package com.visolearn.utils;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.prefs.Preferences;

/**
 * Central live settings state for the JavaFX application.
 *
 * Preferences are only the persistence layer. Controllers should read or bind
 * to these properties so settings can change while the app is running.
 */
public final class SettingsManager {

    private static final String KEY_GRADCAM_OPACITY = "gradcam_opacity";
    private static final String KEY_ANIMATIONS_ENABLED = "animations_enabled";
    private static final String KEY_EXPORT_RESOLUTION = "export_resolution";
    private static final String KEY_IS_DARK_MODE = "is_dark_mode";
    private static final String KEY_COLOR_THEME = "color_theme";

    private static final double DEFAULT_GRADCAM_OPACITY = 0.60;
    private static final boolean DEFAULT_ANIMATIONS_ENABLED = true;
    private static final boolean DEFAULT_DARK_MODE = true;
    private static final String DEFAULT_EXPORT_RESOLUTION = "High / Retina (2x)";

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(SettingsModal.class);

    private static final DoubleProperty gradCamOpacity =
            new SimpleDoubleProperty(readGradCamOpacity());
    private static final BooleanProperty animationsEnabled =
            new SimpleBooleanProperty(readAnimationsEnabled());
    private static final StringProperty exportResolution =
            new SimpleStringProperty(readExportResolution());
    private static final BooleanProperty darkMode =
            new SimpleBooleanProperty(readDarkMode());

    private SettingsManager() {
        throw new UnsupportedOperationException(
                "SettingsManager is a static utility class.");
    }

    public static DoubleProperty gradCamOpacityProperty() {
        return gradCamOpacity;
    }

    public static double getGradCamOpacity() {
        return gradCamOpacity.get();
    }

    public static void setGradCamOpacity(double value) {
        gradCamOpacity.set(clamp(value, 0.0, 1.0));
    }

    public static BooleanProperty animationsEnabledProperty() {
        return animationsEnabled;
    }

    public static boolean isAnimationsEnabled() {
        return animationsEnabled.get();
    }

    public static void setAnimationsEnabled(boolean value) {
        animationsEnabled.set(value);
    }

    public static StringProperty exportResolutionProperty() {
        return exportResolution;
    }

    public static String getExportResolution() {
        return exportResolution.get();
    }

    public static void setExportResolution(String value) {
        exportResolution.set(value == null || value.isBlank()
                ? DEFAULT_EXPORT_RESOLUTION
                : value);
    }

    public static BooleanProperty darkModeProperty() {
        return darkMode;
    }

    public static boolean isDarkMode() {
        return darkMode.get();
    }

    public static void setDarkMode(boolean value) {
        darkMode.set(value);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                getGradCamOpacity(),
                isAnimationsEnabled(),
                getExportResolution(),
                isDarkMode()
        );
    }

    public static void restore(Snapshot snapshot) {
        if (snapshot == null) return;

        setGradCamOpacity(snapshot.gradCamOpacity());
        setAnimationsEnabled(snapshot.animationsEnabled());
        setExportResolution(snapshot.exportResolution());
        setDarkMode(snapshot.darkMode());
    }

    public static void persist() {
        PREFS.putDouble(KEY_GRADCAM_OPACITY, getGradCamOpacity());
        PREFS.putBoolean(KEY_ANIMATIONS_ENABLED, isAnimationsEnabled());
        PREFS.put(KEY_EXPORT_RESOLUTION, getExportResolution());
        PREFS.putBoolean(KEY_IS_DARK_MODE, isDarkMode());
        PREFS.put(KEY_COLOR_THEME, isDarkMode()
                ? "Deep Slate (Dark)"
                : "Clinical (Light)");
    }

    private static double readGradCamOpacity() {
        return clamp(PREFS.getDouble(
                KEY_GRADCAM_OPACITY, DEFAULT_GRADCAM_OPACITY), 0.0, 1.0);
    }

    private static boolean readAnimationsEnabled() {
        return PREFS.getBoolean(
                KEY_ANIMATIONS_ENABLED, DEFAULT_ANIMATIONS_ENABLED);
    }

    private static String readExportResolution() {
        String saved = PREFS.get(KEY_EXPORT_RESOLUTION, DEFAULT_EXPORT_RESOLUTION);
        if (saved.startsWith("Standard")) return "Standard (1x)";
        if (saved.startsWith("Ultra")) return "Ultra (3x)";
        return DEFAULT_EXPORT_RESOLUTION;
    }

    private static boolean readDarkMode() {
        String legacyTheme = PREFS.get(KEY_COLOR_THEME, null);
        boolean legacyDarkMode = legacyTheme == null
                ? DEFAULT_DARK_MODE
                : !legacyTheme.contains("Light");
        return PREFS.getBoolean(KEY_IS_DARK_MODE, legacyDarkMode);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Snapshot(
            double gradCamOpacity,
            boolean animationsEnabled,
            String exportResolution,
            boolean darkMode
    ) {
    }
}
