package com.visolearn;

import com.visolearn.utils.ToastUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

/**
 * BatchController controls Tab 3 of VisoLearn AI Studio.
 * Allows users to select a folder of images and run inference
 * on all of them at once, displaying results in a table
 * and providing CSV export functionality.
 *
 * All inference runs on a background thread using Task
 * to keep the UI responsive during batch processing.
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class BatchController implements Initializable {

    // ===== FXML UI Elements =====

    @FXML private TextField    folderPathField;
    @FXML private Button       selectFolderButton;
    @FXML private Button       runBatchButton;
    @FXML private Button       exportCsvButton;
    @FXML private VBox         progressBox;
    @FXML private ProgressBar  batchProgressBar;
    @FXML private Label        progressLabel;
    @FXML private Label        progressCountLabel;
    @FXML private HBox         summaryBox;
    @FXML private Label        totalImagesLabel;
    @FXML private Label        topClassLabel;
    @FXML private Label        avgConfidenceLabel;
    @FXML private Label        processingTimeLabel;

    @FXML private TableView<BatchResult>          resultsTable;
    @FXML private TableColumn<BatchResult, String> fileNameColumn;
    @FXML private TableColumn<BatchResult, String> predictedClassColumn;
    @FXML private TableColumn<BatchResult, String> confidenceColumn;
    @FXML private TableColumn<BatchResult, String> inferenceTimeColumn;

    // ===== Backend =====

    /** Classifier shared from the main application. */
    private SkinClassifier classifier;

    /** Selected folder path. */
    private File selectedFolder;

    /** Batch results for CSV export. */
    private List<BatchResult> batchResults = new ArrayList<>();

    /**
     * Represents one row in the batch results table.
     * JavaFX TableView requires public properties with getters.
     */
    public static class BatchResult {

        private final String fileName;
        private final String predictedClass;
        private final String confidence;
        private final String inferenceTime;

        /**
         * Constructs one batch result row.
         *
         * @param fileName       image file name
         * @param predictedClass predicted class name
         * @param confidence     confidence percentage string
         * @param inferenceTime  inference time in ms string
         */
        public BatchResult(String fileName, String predictedClass,
                           String confidence, String inferenceTime) {
            this.fileName       = fileName;
            this.predictedClass = predictedClass;
            this.confidence     = confidence;
            this.inferenceTime  = inferenceTime;
        }

        /** @return image file name */
        public String getFileName()       { return fileName; }

        /** @return predicted class name */
        public String getPredictedClass() { return predictedClass; }

        /** @return confidence percentage string */
        public String getConfidence()     { return confidence; }

        /** @return inference time string */
        public String getInferenceTime()  { return inferenceTime; }
    }

    /**
     * Full class names for display in the results table.
     * Index matches class label order in labels.txt.
     */
    private static final String[] CLASS_FULL_NAMES = {
            "Actinic Keratosis",
            "Basal Cell Carcinoma",
            "Benign Keratosis",
            "Dermatofibroma",
            "Melanoma",
            "Melanocytic Nevus",
            "Vascular Lesion"
    };

    /**
     * Called automatically by JavaFX after FXML loads.
     * Sets up the TableView columns and initializes classifier.
     *
     * @param url not used
     * @param rb  not used
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        fileNameColumn.setCellValueFactory(
                new PropertyValueFactory<>("fileName"));
        predictedClassColumn.setCellValueFactory(
                new PropertyValueFactory<>("predictedClass"));
        confidenceColumn.setCellValueFactory(
                new PropertyValueFactory<>("confidence"));
        inferenceTimeColumn.setCellValueFactory(
                new PropertyValueFactory<>("inferenceTime"));

        // Use shared classifier from MainApp
        Task<Void> initTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                int attempts = 0;
                while (MainApp.getSharedClassifier() == null
                        && attempts < 30) {
                    Thread.sleep(500);
                    attempts++;
                }
                classifier = MainApp.getSharedClassifier();
                if (classifier == null) {
                    throw new Exception("Shared classifier not available.");
                }
                return null;
            }
        };

        initTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                selectFolderButton.setDisable(false);
                System.out.println("BatchController: " +
                        "shared classifier connected.");
            });
        });

        initTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                progressLabel.setText("Model failed to load.");
            });
        });

        selectFolderButton.setDisable(true);

        Thread initThread = new Thread(initTask);
        initThread.setDaemon(true);
        initThread.start();
    }

    /**
     * Handles the Browse Folder button click.
     * Opens a directory chooser and stores the selected path.
     */
    @FXML
    private void handleSelectFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Image Folder");

        File folder = chooser.showDialog(
                selectFolderButton.getScene().getWindow());

        if (folder != null) {
            selectedFolder = folder;
            folderPathField.setText(folder.getAbsolutePath());
            runBatchButton.setDisable(false);

            // Count images in folder
            File[] images = getImageFiles(folder);
            progressLabel.setText(
                    images.length + " images found in folder.");
            progressBox.setVisible(true);
        }
    }

    /**
     * Handles the Run Analysis button click.
     * Runs inference on all images in the selected folder
     * on a background thread with live progress updates.
     */
    @FXML
    private void handleRunBatch() {
        if (selectedFolder == null) return;

        File[] imageFiles = getImageFiles(selectedFolder);
        if (imageFiles.length == 0) {
            progressLabel.setText(
                    "No JPG or PNG images found in folder.");
            return;
        }

        // Clear previous results
        batchResults.clear();
        resultsTable.getItems().clear();
        summaryBox.setVisible(false);
        exportCsvButton.setDisable(true);
        runBatchButton.setDisable(true);

        // Show progress
        progressBox.setVisible(true);
        batchProgressBar.setProgress(0);
        progressCountLabel.setText("0 / " + imageFiles.length);

        long startTime = System.currentTimeMillis();

        // Run batch inference on background thread
        Task<Void> batchTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                int total = imageFiles.length;

                for (int i = 0; i < total; i++) {
                    File imageFile = imageFiles[i];
                    Path imagePath = imageFile.toPath();

                    try {
                        // Run inference on this image
                        SkinClassifier.PredictionResult result =
                                classifier.predict(imagePath);

                        // Build table row
                        String fullName =
                                CLASS_FULL_NAMES[result.classIndex];
                        BatchResult row = new BatchResult(
                                imageFile.getName(),
                                fullName,
                                String.format("%.2f%%",
                                        result.confidence),
                                result.inferenceTimeMs + " ms"
                        );

                        batchResults.add(row);

                        // Update UI on JavaFX thread
                        final int current = i + 1;
                        final BatchResult finalRow = row;
                        Platform.runLater(() -> {
                            resultsTable.getItems().add(finalRow);
                            batchProgressBar.setProgress(
                                    (double) current / total);
                            progressCountLabel.setText(
                                    current + " / " + total);
                            progressLabel.setText(
                                    "Processing: " +
                                            imageFile.getName());
                        });

                    } catch (Exception e) {
                        System.err.println("Error on " +
                                imageFile.getName() + ": " +
                                e.getMessage());
                    }
                }
                return null;
            }
        };

        batchTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                long elapsed =
                        System.currentTimeMillis() - startTime;
                showSummary(imageFiles.length, elapsed);
                runBatchButton.setDisable(false);
                exportCsvButton.setDisable(false);
                progressLabel.setText("Analysis complete.");
                System.out.println("BatchController: " +
                        "processed " + imageFiles.length +
                        " images in " + elapsed + "ms");
            });
        });

        batchTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                progressLabel.setText("Batch analysis failed.");
                runBatchButton.setDisable(false);
                System.err.println("Batch error: " +
                        batchTask.getException().getMessage());
            });
        });

        Thread batchThread = new Thread(batchTask);
        batchThread.setDaemon(true);
        batchThread.start();
    }

    /**
     * Handles the Export CSV button click.
     * Saves all batch results to a CSV file chosen by the user.
     */
    @FXML
    private void handleExportCsv() {
        javafx.stage.FileChooser fileChooser =
                new javafx.stage.FileChooser();
        fileChooser.setTitle("Save Results as CSV");
        fileChooser.setInitialFileName("visolearn_batch_results.csv");
        fileChooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter(
                        "CSV Files", "*.csv")
        );

        File csvFile = fileChooser.showSaveDialog(
                exportCsvButton.getScene().getWindow());

        if (csvFile != null) {
            try (PrintWriter writer =
                         new PrintWriter(new FileWriter(csvFile))) {
                // Write header
                writer.println(
                        "File Name,Predicted Class," +
                                "Confidence,Inference Time (ms)");

                // Write each result row
                for (BatchResult r : batchResults) {
                    writer.printf("%s,%s,%s,%s%n",
                            r.getFileName(),
                            r.getPredictedClass(),
                            r.getConfidence(),
                            r.getInferenceTime()
                    );
                }

                progressLabel.setText("CSV saved: " + csvFile.getName());
                System.out.println("BatchController: CSV exported to " + csvFile.getAbsolutePath());

                // ---> Trigger the Success Toast
                StackPane root = (StackPane) exportCsvButton.getScene().getRoot();
                com.visolearn.utils.ToastUtil.showToast(root, "CSV Exported Successfully!", com.visolearn.utils.ToastUtil.ToastType.SUCCESS);

            } catch (Exception e) {
                progressLabel.setText("CSV export failed.");
                System.err.println("CSV error: " + e.getMessage());

                // ---> Trigger the Error Toast
                StackPane root = (StackPane) exportCsvButton.getScene().getRoot();
                com.visolearn.utils.ToastUtil.showToast(root, "Failed to export CSV.", com.visolearn.utils.ToastUtil.ToastType.ERROR);
            }
        }
    }

    /**
     * Displays summary statistics after batch analysis completes.
     *
     * @param totalImages  total number of images processed
     * @param elapsedMs    total processing time in milliseconds
     */
    private void showSummary(int totalImages, long elapsedMs) {
        summaryBox.setVisible(true);
        totalImagesLabel.setText(String.valueOf(totalImages));

        // Count predictions per class
        Map<String, Integer> classCounts = new HashMap<>();
        double totalConfidence = 0;

        for (BatchResult r : batchResults) {
            classCounts.merge(r.getPredictedClass(), 1,
                    Integer::sum);
            // Parse confidence value
            try {
                String pctStr = r.getConfidence()
                        .replace("%", "").trim();
                totalConfidence += Double.parseDouble(pctStr);
            } catch (NumberFormatException ignored) {}
        }

        // Find most common predicted class
        String topClass = classCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("—");

        topClassLabel.setText(topClass);

        // Average confidence
        double avgConf = batchResults.isEmpty() ? 0 :
                totalConfidence / batchResults.size();
        avgConfidenceLabel.setText(
                String.format("%.1f%%", avgConf));

        // Total processing time
        if (elapsedMs < 1000) {
            processingTimeLabel.setText(elapsedMs + " ms");
        } else {
            processingTimeLabel.setText(
                    String.format("%.1f s", elapsedMs / 1000.0));
        }
    }

    /**
     * Returns all JPG and PNG image files in a given folder.
     * Filters out non-image files and subdirectories.
     *
     * @param folder the directory to scan
     * @return array of image files found
     */
    private File[] getImageFiles(File folder) {
        File[] files = folder.listFiles(f ->
                f.isFile() && (
                        f.getName().toLowerCase().endsWith(".jpg") ||
                                f.getName().toLowerCase().endsWith(".jpeg") ||
                                f.getName().toLowerCase().endsWith(".png")
                )
        );
        return files != null ? files : new File[0];
    }
}