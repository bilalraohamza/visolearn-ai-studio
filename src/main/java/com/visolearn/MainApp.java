package com.visolearn;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import com.visolearn.data.DatabaseUtil;
import com.visolearn.utils.SettingsManager;

import java.io.IOException;
import java.util.Objects;

/**
 * VisoLearn AI Studio — Main Application Entry Point.
 * Launches the JavaFX desktop application for real-time
 * skin lesion classification using EfficientNet-B4 + DenseNet-169.
 *
 * <h3>Dependency injection model</h3>
 * <p>A single {@link AppContext} is constructed here and threaded to every
 * controller that needs it via {@link FXMLLoader#setControllerFactory}.
 * No static accessor methods expose the shared classifier — only controllers
 * that explicitly receive an {@code AppContext} can reach it.</p>
 *
 * @author Rao Hamza Bilal
 * @version 2.0
 */
public class MainApp extends Application {

    /** Application title shown in the window title bar. */
    private static final String APP_TITLE = "VisoLearn AI Studio";

    /** Minimum window width in pixels. */
    private static final double MIN_WIDTH = 900;

    /** Minimum window height in pixels. */
    private static final double MIN_HEIGHT = 600;

    /**
     * JavaFX start method — called automatically when the app launches.
     * Loads the main FXML layout, initialises the shared classifier via
     * a background task, and sets up the primary stage.
     *
     * @param primaryStage the main window provided by JavaFX
     * @throws IOException if the FXML file cannot be loaded
     */
    @Override
    public void start(Stage primaryStage) throws IOException {

        // ── Step 1: Construct application context — owns the classifier future ─
        final AppContext ctx = new AppContext();

        // ── Step 2: Show splash immediately — user sees feedback at once ───────
        SplashScreen splash = new SplashScreen();
        splash.show();
        splash.setStatus("Starting VisoLearn AI Studio…");

        // ── Step 3: Defer heavy loading so start() returns instantly ──────────
        // This ensures the splash screen renders immediately without being blocked
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        Objects.requireNonNull(
                                getClass().getResource("/main.fxml"),
                                "main.fxml not found in resources"
                        )
                );

                // ── Controller factory — explicit dependency injection ─────────
                // Controllers that need the AppContext receive it here at
                // construction time. All other controllers use their default
                // no-arg constructor via reflection.
                loader.setControllerFactory(type -> {
                    try {
                        if (type == ClassifyController.class) {
                            return new ClassifyController(ctx);
                        }
                        if (type == BatchController.class) {
                            return new BatchController(ctx);
                        }
                        // HistoryController, DashboardController, MainController
                        // do not need the classifier — use default constructor.
                        return type.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Failed to create controller: " + type.getName(), e);
                    }
                });

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

                // ── Classifier lifecycle — box reference so lambda can read it ─
                final SkinClassifier[] classifierHolder = {null};

                primaryStage.setOnCloseRequest(e -> {
                    if (classifierHolder[0] != null) {
                        classifierHolder[0].close();
                        System.out.println("MainApp: classifier released.");
                    }
                    DatabaseUtil.shutdown();
                });

                // ── Step 4: Initialise classifier on background thread ─────────
                Task<SkinClassifier> initTask = new Task<>() {
                    @Override
                    protected SkinClassifier call() throws Exception {
                        splash.setStatus("Preparing inference engine");
                        SkinClassifier classifier = new SkinClassifier();

                        splash.setStatus("Loading EfficientNet-B4 + DenseNet-169 models");
                        classifier.initialize();

                        splash.setStatus("Models ready — launching studio");
                        return classifier;
                    }
                };

                initTask.setOnSucceeded(e -> Platform.runLater(() -> {
                    SkinClassifier classifier = initTask.getValue();
                    classifierHolder[0] = classifier;

                    System.out.println("MainApp: shared classifier ready.");
                    ctx.completeClassifier(classifier);  // notifies all subscribers

                    System.out.println("MainApp: shared classifier initialized " +
                            "and ready for all tabs.");

                    javafx.animation.PauseTransition delay =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
                    delay.setOnFinished(ev -> {
                        splash.dismiss();
                        primaryStage.show();
                        primaryStage.centerOnScreen();

                        if (!SettingsManager.isDarkMode()) {
                            javafx.animation.PauseTransition reapply =
                                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
                            reapply.setOnFinished(re ->
                                    MainController.applyTheme(scene, SettingsManager.isDarkMode()));
                            reapply.play();
                        }
                    });
                    delay.play();
                }));

                initTask.setOnFailed(e -> Platform.runLater(() -> {
                    Throwable cause = initTask.getException();
                    ctx.failClassifier(cause);  // notifies all subscribers with error

                    splash.setStatus("⚠ Failed to load models — see console for details.");
                    System.err.println("MainApp: classifier failed to load — "
                            + (cause != null ? cause.getMessage() : "unknown error"));

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
     * Application entry point.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        launch(args);
    }
}