package com.visolearn;

import com.visolearn.utils.SettingsModal;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

public class MainController {

    @FXML
    private void handleOpenSettings(ActionEvent event) {
        // Retrieve the root StackPane from the button's scene and show the modal
        StackPane root = (StackPane) ((Node) event.getSource()).getScene().getRoot();
        SettingsModal.show(root);
    }

    /**
     * Applies a named theme to the given {@link javafx.scene.Scene} by clearing
     * all existing stylesheets and loading the appropriate CSS resource.
     *
     * <p>Theme resolution rules:</p>
     * <ul>
     *   <li>If {@code themeName} contains {@code "Light"} → loads {@code /styles-light.css}</li>
     *   <li>All other values → loads {@code /styles.css} (the default Deep Slate dark theme)</li>
     * </ul>
     *
     * <p>A {@code null} URL check is performed before adding the stylesheet to prevent
     * a {@link NullPointerException} if the CSS file is temporarily missing from the
     * classpath (e.g., during development before the resource is created).</p>
     *
     * <h3>Usage:</h3>
     * <pre>{@code
     * // From a controller or settings save handler:
     * MainController.applyTheme(primaryStage.getScene(), "Clinical (Light)");
     * MainController.applyTheme(primaryStage.getScene(), "Deep Slate (Dark)");
     * }</pre>
     *
     * @param scene     The {@link javafx.scene.Scene} whose stylesheets will be replaced.
     *                  If {@code null}, the method returns silently.
     * @param themeName The display name of the theme to apply (as used in
     *                  {@link com.visolearn.utils.SettingsModal}'s Color Theme ComboBox).
     *                  If {@code null}, the method returns silently.
     */
    public static void applyTheme(javafx.scene.Scene scene, String themeName) {
        if (scene == null || themeName == null) return;

        scene.getStylesheets().clear();
        String css = themeName.contains("Light") ? "/styles-light.css" : "/styles.css";

        java.net.URL cssUrl = MainController.class.getResource(css);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        } else {
            System.err.println("Could not find stylesheet: " + css);
        }
    }
}