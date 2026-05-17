package com.visolearn;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import com.visolearn.utils.SettingsManager;

import java.io.IOException;
import java.util.Objects;

/**
 * VisoLearn AI Studio — Main Application Entry Point.
 * Launches the JavaFX desktop application for real-time
 * skin lesion classification using EfficientNet-B4.
 *
 * Single shared SkinClassifier instance is created here
 * and passed to all controllers to prevent ONNX Runtime
 * from loading the same model file twice simultaneously.
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class MainApp extends Application {

    /** Application title shown in the window title bar. */
    private static final String APP_TITLE = "VisoLearn AI Studio";

    /** Minimum window width in pixels. */
    private static final double MIN_WIDTH = 900;

    /** Minimum window height in pixels. */
    private static final double MIN_HEIGHT = 600;

    /**
     * Single shared classifier instance used by all controllers.
     * Static so it can be accessed by ClassifyController
     * and BatchController without creating separate instances.
     */
    private static SkinClassifier sharedClassifier;

    /**
     * Returns the shared SkinClassifier instance.
     * Called by ClassifyController and BatchController
     * instead of creating their own instances.
     *
     * @return the single shared SkinClassifier
     */
    public static SkinClassifier getSharedClassifier() {
        return sharedClassifier;
    }

    /**
     * JavaFX start method — called automatically when app launches.
     * Loads the main FXML layout, initializes the shared classifier,
     * and sets up the primary stage.
     *
     * @param primaryStage the main window provided by JavaFX
     * @throws IOException if the FXML file cannot be loaded
     */
    @Override
    public void start(Stage primaryStage) throws IOException {

        // ── Step 1: Show splash immediately — user sees feedback at once ───────
        SplashScreen splash = new SplashScreen();
        splash.show();
        splash.setStatus("Starting VisoLearn AI Studio…");

        // ── Step 2: Defer heavy loading so start() returns instantly ──────────
        // This ensures the splash screen renders immediately without being blocked
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        Objects.requireNonNull(
                                getClass().getResource("/main.fxml"),
                                "main.fxml not found in resources"
                        )
                );

                Scene scene = new Scene(loader.load(), MIN_WIDTH, MIN_HEIGHT);
                MainController.applyTheme(scene, SettingsManager.isDarkMode());
                SettingsManager.darkModeProperty().addListener((obs, oldValue, isDark) -> {
                    if (!oldValue.equals(isDark)) {
                        MainController.applyTheme(scene, isDark);
                    }
                });

                // Configure primary stage but do NOT show it yet
                primaryStage.setTitle(APP_TITLE);
                primaryStage.setScene(scene);
                primaryStage.setMinWidth(MIN_WIDTH);
                primaryStage.setMinHeight(MIN_HEIGHT);
                primaryStage.centerOnScreen();

                primaryStage.setOnCloseRequest(e -> {
                    if (sharedClassifier != null) {
                        sharedClassifier.close();
                        System.out.println("MainApp: classifier released.");
                    }
                });

                // ── Step 3: Initialize classifier on background thread ────────────────
                Task<Void> initTask = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        splash.setStatus("Preparing inference engine");
                        sharedClassifier = new SkinClassifier();

                        splash.setStatus("Loading EfficientNet-B4 + DenseNet-169 models");
                        sharedClassifier.initialize();

                        splash.setStatus("Models ready — launching studio");
                        return null;
                    }
                };

                initTask.setOnSucceeded(e -> Platform.runLater(() -> {
                    System.out.println("MainApp: shared classifier ready.");
                    notifyControllersReady(loader);

                    javafx.animation.PauseTransition delay =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
                    delay.setOnFinished(ev -> {
                        splash.dismiss();
                        primaryStage.show();
                        primaryStage.centerOnScreen();

                        if (!SettingsManager.isDarkMode()) {
                            javafx.animation.PauseTransition reapply =
                                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
                            reapply.setOnFinished(re -> MainController.applyTheme(scene, SettingsManager.isDarkMode()));
                            reapply.play();
                        }
                    });
                    delay.play();
                }));

                initTask.setOnFailed(e -> Platform.runLater(() -> {
                    splash.setStatus("⚠ Failed to load models — see console for details.");
                    System.err.println("MainApp: classifier failed to load — " +
                            initTask.getException().getMessage());

                    javafx.animation.PauseTransition errDelay =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(2500));
                    errDelay.setOnFinished(ev -> {
                        splash.dismiss();
                        primaryStage.show();
                    });
                    errDelay.play();
                }));

                Thread initThread = new Thread(initTask, "ModelInit");
                initThread.setDaemon(true);
                initThread.start();

            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        System.out.println("VisoLearn AI Studio started successfully.");
    }

    /**
     * Notifies all controllers that the shared classifier is ready.
     * Called after the shared classifier finishes initializing.
     *
     * @param loader the FXMLLoader that loaded main.fxml
     */
    private void notifyControllersReady(FXMLLoader loader) {
        System.out.println("MainApp: shared classifier initialized " +
                "and ready for all tabs.");
    }

    /**
     * Application entry point.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        launch(args);
    }
}