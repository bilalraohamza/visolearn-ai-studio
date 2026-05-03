package com.visolearn;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * VisoLearn AI Studio — Main Application Entry Point.
 * Launches the JavaFX desktop application for real-time
 * skin lesion classification using EfficientNet-B4.
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
     * JavaFX start method — called automatically when the app launches.
     * Loads the main FXML layout and sets up the primary stage.
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

        // Center the window on screen
        primaryStage.centerOnScreen();

        // Show the window
        primaryStage.show();

        System.out.println("VisoLearn AI Studio started successfully.");
    }

    /**
     * Application entry point.
     * JavaFX requires launch() to be called from a separate
     * main method — do not call Application.launch() directly.
     *
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        launch(args);
    }
}