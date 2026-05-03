package com.visolearn;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * DashboardController controls Tab 2 of VisoLearn AI Studio.
 * Loads training history from training_log.json and displays
 * loss and accuracy curves across all 25 training epochs.
 *
 * Data is loaded on a background thread to keep the UI
 * responsive during file reading and chart population.
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class DashboardController implements Initializable {

    // ===== FXML UI Elements =====

    @FXML private LineChart<Number, Number> lossChart;
    @FXML private LineChart<Number, Number> accuracyChart;
    @FXML private Label bestAccLabel;
    @FXML private Label bestEpochLabel;
    @FXML private Button reloadButton;

    /** Loader for training_log.json. */
    private final TrainingLogLoader logLoader = new TrainingLogLoader();

    /**
     * Called automatically by JavaFX after FXML loads.
     * Immediately loads training data and populates charts.
     *
     * @param url not used
     * @param rb  not used
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Style the charts
        lossChart.setCreateSymbols(false);
        accuracyChart.setCreateSymbols(false);
        lossChart.setLegendVisible(true);
        accuracyChart.setLegendVisible(true);

        // Load training data on background thread
        loadTrainingData();
    }

    /**
     * Handles the Reload Log button click.
     * Clears charts and reloads training_log.json.
     */
    @FXML
    private void handleReload() {
        lossChart.getData().clear();
        accuracyChart.getData().clear();
        loadTrainingData();
    }

    /**
     * Loads training history from training_log.json on a
     * background thread and populates both charts when done.
     * Uses Task to avoid blocking the JavaFX Application Thread.
     */
    private void loadTrainingData() {
        Task<List<TrainingLogLoader.EpochData>> loadTask =
                new Task<>() {
                    @Override
                    protected List<TrainingLogLoader.EpochData> call()
                            throws Exception {
                        return logLoader.load();
                    }
                };

        loadTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                List<TrainingLogLoader.EpochData> epochs =
                        loadTask.getValue();
                populateCharts(epochs);
                updateMetricCards(epochs);
                System.out.println("DashboardController: " +
                        "charts populated with " +
                        epochs.size() + " epochs.");
            });
        });

        loadTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                bestAccLabel.setText("Error");
                bestEpochLabel.setText(
                        "Could not load training_log.json");
                System.err.println("Dashboard load error: " +
                        loadTask.getException().getMessage());
            });
        });

        Thread loadThread = new Thread(loadTask);
        loadThread.setDaemon(true);
        loadThread.start();
    }

    /**
     * Populates the loss and accuracy LineCharts with epoch data.
     * Creates two series per chart: training and validation.
     * Phase boundary at epoch 5/6 is visible from the data jump.
     *
     * @param epochs list of epoch data loaded from training_log.json
     */
    private void populateCharts(
            List<TrainingLogLoader.EpochData> epochs) {

        // ===== Loss Chart =====
        XYChart.Series<Number, Number> trainLossSeries =
                new XYChart.Series<>();
        trainLossSeries.setName("Train Loss");

        XYChart.Series<Number, Number> valLossSeries =
                new XYChart.Series<>();
        valLossSeries.setName("Val Loss");

        // ===== Accuracy Chart =====
        XYChart.Series<Number, Number> trainAccSeries =
                new XYChart.Series<>();
        trainAccSeries.setName("Train Accuracy");

        XYChart.Series<Number, Number> valAccSeries =
                new XYChart.Series<>();
        valAccSeries.setName("Val Accuracy");

        // Populate all 4 series from epoch data
        for (TrainingLogLoader.EpochData ep : epochs) {
            trainLossSeries.getData().add(
                    new XYChart.Data<>(ep.epoch, ep.trainLoss));
            valLossSeries.getData().add(
                    new XYChart.Data<>(ep.epoch, ep.valLoss));
            trainAccSeries.getData().add(
                    new XYChart.Data<>(ep.epoch, ep.trainAcc));
            valAccSeries.getData().add(
                    new XYChart.Data<>(ep.epoch, ep.valAcc));
        }

        // Add series to charts
        lossChart.getData().addAll(trainLossSeries, valLossSeries);
        accuracyChart.getData().addAll(trainAccSeries, valAccSeries);

        // Style train series blue
        trainLossSeries.getNode().setStyle(
                "-fx-stroke: #378ADD; -fx-stroke-width: 2;");
        valLossSeries.getNode().setStyle(
                "-fx-stroke: #E24B4A; -fx-stroke-width: 2;" +
                        "-fx-stroke-dash-array: 6 4;");
        trainAccSeries.getNode().setStyle(
                "-fx-stroke: #378ADD; -fx-stroke-width: 2;");
        valAccSeries.getNode().setStyle(
                "-fx-stroke: #E24B4A; -fx-stroke-width: 2;" +
                        "-fx-stroke-dash-array: 6 4;");
    }

    /**
     * Updates the best accuracy metric card with the epoch
     * that achieved the highest validation accuracy.
     *
     * @param epochs list of all epoch data
     */
    private void updateMetricCards(
            List<TrainingLogLoader.EpochData> epochs) {

        TrainingLogLoader.EpochData best =
                logLoader.getBestEpoch(epochs);

        bestAccLabel.setText(
                String.format("%.2f%%", best.valAcc));
        bestEpochLabel.setText(
                String.format("Epoch %d of %d",
                        best.epoch, epochs.size()));
    }
}