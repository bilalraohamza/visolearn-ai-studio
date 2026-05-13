package com.visolearn;

import com.visolearn.utils.SettingsModal;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;

import java.util.HashSet;
import java.util.Set;

public class MainController {

    private static final String[][] DARK_TO_LIGHT = {
            {"#1A1A24", "#F4F7FB"},
            {"#1E1E2A", "#EEF4F8"},
            {"#252533", "#FFFFFF"},          // FIX: was missing — history headers, session summary box
            {"#F8F9FA", "#0F172A"},
            {"#f0f0fa", "#0F172A"},
            {"#D1D5DB", "#334155"},
            {"#9CA3AF", "#64748B"},
            {"#6B7280", "#475569"},
            {"#4B5563", "#94A3B8"},
            {"#374151", "#CBD5E1"},
            {"white",   "#0F172A"},
            {"rgba(255,255,255,0.05)",  "rgba(15,23,42,0.08)"},
            {"rgba(255,255,255,0.10)",  "rgba(15,23,42,0.10)"},
            {"rgba(255,255,255,0.15)",  "rgba(15,23,42,0.16)"},
            {"rgba(255,255,255,0.03)",  "rgba(15,23,42,0.04)"},
    };

    // Text node fill colors that need to be swapped (Text.fill is not a CSS style property)
    private static final String[][] TEXT_FILL_DARK_TO_LIGHT = {
            {"#F8F9FA", "#0F172A"},
            {"#9CA3AF", "#64748B"},
            {"#6B7280", "#475569"},
            {"#D1D5DB", "#334155"},
    };

    private static final String[][] LIGHT_TO_DARK = reverse(DARK_TO_LIGHT);

    @FXML
    private TabPane mainTabPane;

    /** Tracks which tab indices have already had the theme applied after first selection. */
    private final Set<Integer> themedTabIndices = new HashSet<>();

    @FXML
    public void initialize() {
        if (mainTabPane == null) return;
        // Re-apply theme whenever the user switches to a tab for the first time.
        // Tab content is not fully rendered until first shown, so the startup
        // applyTheme call may miss nodes that only enter the scene graph on first display.
        mainTabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) -> {
            int idx = newIdx.intValue();
            if (!themedTabIndices.contains(idx)) {
                themedTabIndices.add(idx);
                Scene scene = mainTabPane.getScene();
                if (scene != null) {
                    // Small delay to allow the tab content to finish rendering
                    javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(
                            javafx.util.Duration.millis(60));
                    pt.setOnFinished(e -> applyTheme(scene, com.visolearn.utils.SettingsManager.isDarkMode()));
                    pt.play();
                }
            }
        });
        // Mark tab 0 (Classify) as already themed since it's visible at startup
        themedTabIndices.add(0);
    }

    @FXML
    private void handleOpenSettings(ActionEvent event) {
        StackPane root = (StackPane) ((Node) event.getSource()).getScene().getRoot();
        SettingsModal.show(root);
    }

    public static void applyTheme(Scene scene, boolean darkMode) {
        if (scene == null) return;

        String css = darkMode ? "/styles.css" : "/styles-light.css";
        java.net.URL cssUrl = MainController.class.getResource(css);

        if (cssUrl == null) {
            System.err.println("[Theme] Could not find: " + css + " - keeping current stylesheet.");
            return;
        }

        scene.getStylesheets().clear();
        scene.getStylesheets().add(cssUrl.toExternalForm());
        applyInlineTheme(scene.getRoot(), darkMode);
    }

    public static void applyTheme(Scene scene, String themeName) {
        if (themeName == null) return;
        applyTheme(scene, !themeName.contains("Light"));
    }

    private static void applyInlineTheme(Node node, boolean darkMode) {
        if (node == null) return;

        String style = node.getStyle();
        if (style != null && !style.isEmpty()) {
            String themed = replaceTokens(style, darkMode ? LIGHT_TO_DARK : DARK_TO_LIGHT);
            if (!style.equals(themed)) {
                node.setStyle(themed);
            }
        }

        // FIX: javafx.scene.text.Text nodes use a `fill` Paint property, not CSS -fx-fill.
        // They are set via fill="..." in FXML and must be updated programmatically.
        // IMPORTANT: Skip nodes whose fill is CSS-bound (e.g. LabeledText inside Label controls).
        // Calling setFill() on a bound property throws RuntimeException: "A bound value cannot be set."
        if (node instanceof javafx.scene.text.Text textNode) {
            javafx.beans.property.ObjectProperty<javafx.scene.paint.Paint> fillProp =
                    textNode.fillProperty();
            // Only touch standalone Text nodes — skip internal LabeledText (bound by CSS)
            if (!fillProp.isBound()) {
                javafx.scene.paint.Paint fill = fillProp.get();
                if (fill instanceof javafx.scene.paint.Color color) {
                    String hex = toHex(color);
                    String[][] fillMap = darkMode ? reverse(TEXT_FILL_DARK_TO_LIGHT) : TEXT_FILL_DARK_TO_LIGHT;
                    for (String[] pair : fillMap) {
                        if (pair[0].equalsIgnoreCase(hex)) {
                            textNode.setFill(javafx.scene.paint.Color.web(pair[1]));
                            break;
                        }
                    }
                }
            }
        }

        // FIX: TabPane.getChildrenUnmodifiable() only returns header/skin nodes —
        // NOT the content inside each tab. Without this, every inline style in
        // classify_tab, history_tab, batch_tab is never visited and stays stuck
        // on its original color regardless of theme switches.
        if (node instanceof javafx.scene.control.TabPane tabPane) {
            for (javafx.scene.control.Tab tab : tabPane.getTabs()) {
                if (tab.getContent() != null) {
                    applyInlineTheme(tab.getContent(), darkMode);
                }
            }
        }

        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                applyInlineTheme(child, darkMode);
            }
        }
    }

    /** Convert a JavaFX Color to a 6-digit lowercase hex string like #f8f9fa */
    private static String toHex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed()   * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue()  * 255));
    }

    private static String replaceTokens(String style, String[][] replacements) {
        String themed = style;
        for (String[] replacement : replacements) {
            themed = themed.replace(replacement[0], replacement[1]);
        }
        return themed;
    }

    private static String[][] reverse(String[][] replacements) {
        String[][] reversed = new String[replacements.length][2];
        for (int i = 0; i < replacements.length; i++) {
            reversed[i][0] = replacements[i][1];
            reversed[i][1] = replacements[i][0];
        }
        return reversed;
    }
}