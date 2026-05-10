package com.visolearn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * DashboardController for the Ensemble model.
 * FULLY UPDATED to load both chart logs AND per-class ensemble metrics.
 */
public class DashboardController implements Initializable {

    @FXML
    private LineChart<Number, Number> lossChart;
    @FXML
    private LineChart<Number, Number> accuracyChart;
    @FXML
    private Label bestAccLabel;
    @FXML
    private Label bestEpochLabel;
    @FXML
    private Button reloadButton;

    // These MUST match the fx:id in your dashboard_tab.fxml
    @FXML
    private Label testAccLabel;
    @FXML
    private Label macroF1Label;

    // F1 Score Progress Bars
    @FXML
    private ProgressBar bar0, bar1, bar2, bar3, bar4, bar5, bar6;
    // F1 Score Percentage Labels
    @FXML
    private Label pct0, pct1, pct2, pct3, pct4, pct5, pct6;

    private final TrainingLogLoader logLoader = new TrainingLogLoader();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        lossChart.setCreateSymbols(false);
        accuracyChart.setCreateSymbols(false);
        loadEnsembleData();
    }

    @FXML
    private void handleReload() {
        lossChart.getData().clear();
        accuracyChart.getData().clear();
        loadEnsembleData();
    }

    private void loadEnsembleData() {
        Task<Void> loadTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // 1. LOAD CHART DATA
                List<TrainingLogLoader.EpochData> effNetData = logLoader.load("/training_log_b4v3.json");
                List<TrainingLogLoader.EpochData> denseNetData = logLoader.load("/training_log_densenet169v2.json");

                // 2. LOAD ENSEMBLE METRICS JSON
                InputStream metricStream = getClass().getResourceAsStream("/ensemble_metrics.json");
                if (metricStream == null) {
                    throw new IllegalStateException("ensemble_metrics.json not found in resources!");
                }

                ObjectMapper mapper = new ObjectMapper();
                JsonNode metricsRoot = mapper.readTree(metricStream);

                Platform.runLater(() -> {
                    // Update Charts
                    populateChartWithModel(effNetData, "EffNet-B4", "#00B4D8");
                    populateChartWithModel(denseNetData, "DenseNet-169", "#F43F5E");

                    // 3. APPLY DATA TO UI CARDS
                    // Update Best Val Acc (calculated from logs)
                    TrainingLogLoader.EpochData bestEff = logLoader.getBestEpoch(effNetData);
                    TrainingLogLoader.EpochData bestDense = logLoader.getBestEpoch(denseNetData);
                    double avgBestAcc = (bestEff.valAcc + bestDense.valAcc) / 2.0;
                    bestAccLabel.setText(String.format("%.2f%%", avgBestAcc));
                    bestEpochLabel.setText("Combined Ensemble Peak");

                    // Update Test Accuracy & Macro F1 from ensemble_metrics.json
                    if (testAccLabel != null)
                        testAccLabel.setText(String.format("%.2f%%", metricsRoot.get("test_accuracy").asDouble()));
                    if (macroF1Label != null)
                        macroF1Label.setText(String.format("%.3f", metricsRoot.get("macro_f1").asDouble()));

                    // 4. UPDATE PER-CLASS F1 BARS AND LABELS
                    JsonNode f1 = metricsRoot.get("per_class_f1");
                    ProgressBar[] bars = {bar0, bar1, bar2, bar3, bar4, bar5, bar6};
                    Label[] pcts = {pct0, pct1, pct2, pct3, pct4, pct5, pct6};
                    String[] keys = {"akiec", "bcc", "bkl", "df", "mel", "nv", "vasc"};

                    for (int i = 0; i < 7; i++) {
                        if (bars[i] != null && f1.has(keys[i])) {
                            double score = f1.get(keys[i]).asDouble();
                            bars[i].setProgress(score); // Set the bar length
                            pcts[i].setText(String.format("%.3f", score)); // Set the text label
                        }
                    }
                });
                return null;
            }
        };

        Thread loadThread = new Thread(loadTask);
        loadThread.setDaemon(true);
        loadThread.start();
    }

    private void populateChartWithModel(List<TrainingLogLoader.EpochData> data, String modelName, String color) {
        XYChart.Series<Number, Number> lossSeries = new XYChart.Series<>();
        lossSeries.setName(modelName + " Loss");

        XYChart.Series<Number, Number> accSeries = new XYChart.Series<>();
        accSeries.setName(modelName + " Acc");

        for (TrainingLogLoader.EpochData ep : data) {
            lossSeries.getData().add(new XYChart.Data<>(ep.epoch, ep.valLoss));
            accSeries.getData().add(new XYChart.Data<>(ep.epoch, ep.valAcc));
        }

        lossChart.getData().add(lossSeries);
        accuracyChart.getData().add(accSeries);

        // Apply the color directly to the line node
        Platform.runLater(() -> {
            if (lossSeries.getNode() != null) {
                lossSeries.getNode().setStyle("-fx-stroke: " + color + ";");
            }
            if (accSeries.getNode() != null) {
                accSeries.getNode().setStyle("-fx-stroke: " + color + ";");
            }
        });
    }
}