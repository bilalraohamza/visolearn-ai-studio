package com.visolearn;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

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
    private static final double MIN_WIDTH = 1100;

    /** Minimum window height in pixels. */
    private static final double MIN_HEIGHT = 700;

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

        // Load the main FXML layout file from resources
        FXMLLoader loader = new FXMLLoader(
                Objects.requireNonNull(
                        getClass().getResource("/main.fxml"),
                        "main.fxml not found in resources"
                )
        );

        // Create the scene with the loaded layout
        Scene scene = new Scene(loader.load(), MIN_WIDTH, MIN_HEIGHT);

        // Configure the primary stage (main window)
        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);
        primaryStage.centerOnScreen();

        // Release classifier resources when window closes
        primaryStage.setOnCloseRequest(e -> {
            if (sharedClassifier != null) {
                sharedClassifier.close();
                System.out.println("MainApp: classifier released.");
            }
        });

        primaryStage.show();

        // Initialize shared classifier on background thread
        // Both ClassifyController and BatchController will use this
        Task<Void> initTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                sharedClassifier = new SkinClassifier();
                sharedClassifier.initialize();
                return null;
            }
        };

        initTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                System.out.println("MainApp: shared classifier ready.");
                // Notify controllers that classifier is ready
                notifyControllersReady(loader);
            });
        });

        initTask.setOnFailed(e -> {
            System.err.println("MainApp: classifier failed to load — " +
                    initTask.getException().getMessage());
        });

        Thread initThread = new Thread(initTask);
        initThread.setDaemon(true);
        initThread.start();

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