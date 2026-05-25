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
    public void start(Stage primaryStage)
            throws IOException {

        SplashScreen splash = new SplashScreen();
        splash.show();
        splash.setStatus(
            "Starting VisoLearn AI Studio...");

        Platform.runLater(() -> {
            try {
                AppContext ctx = new AppContext();
                initializeClassifier(ctx, splash);

                FXMLLoader loginLoader = new FXMLLoader(
                    getClass().getResource("/login.fxml"));
                Scene loginScene = new Scene(
                    loginLoader.load(), 420, 560);

                Stage loginStage = new Stage();
                loginStage.setTitle(
                    "VisoLearn AI Studio");
                loginStage.setScene(loginScene);
                loginStage.setResizable(false);
                loginStage.setUserData(ctx);
                loginStage.centerOnScreen();

                splash.dismiss();
                loginStage.show();

            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void initializeClassifier(
            AppContext ctx, SplashScreen splash) {

        Task<SkinClassifier> initTask = new Task<>() {
            @Override
            protected SkinClassifier call()
                    throws Exception {
                splash.setStatus(
                    "Loading EfficientNet-B4 " +
                    "+ DenseNet-169...");
                SkinClassifier classifier =
                    new SkinClassifier();
                classifier.initialize();
                splash.setStatus("Models ready");
                return classifier;
            }
        };

        initTask.setOnSucceeded(e ->
            ctx.completeClassifier(
                initTask.getValue()));
        initTask.setOnFailed(e ->
            ctx.failClassifier(
                initTask.getException()));

        Thread t = new Thread(initTask, "ModelInit");
        t.setDaemon(true);
        t.start();
    }

    public static Object createController(
            Class<?> clazz, AppContext ctx) {
        try {
            try {
                return clazz
                    .getConstructor(AppContext.class)
                    .newInstance(ctx);
            } catch (NoSuchMethodException e) {
                return clazz
                    .getDeclaredConstructor()
                    .newInstance();
            }
        } catch (Exception e) {
            throw new RuntimeException(
                "Cannot create controller: " +
                clazz.getName(), e);
        }
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