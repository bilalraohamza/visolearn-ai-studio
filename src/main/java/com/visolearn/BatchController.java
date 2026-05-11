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
 * <p>All inference runs on a background thread using {@link Task}
 * to keep the UI responsive during batch processing.</p>
 *
 * <h3>Quality Fixes (v1.1):</h3>
 * <ul>
 *   <li><b>Removed duplicated {@code CLASS_FULL_NAMES} array</b> — now uses
 *       the centralized {@link SkinClassifier#CLASS_FULL_NAMES} constant.</li>
 *   <li><b>Fixed locale-dependent {@code NumberFormatException}</b> in
 *       {@link #showSummary} — {@link BatchResult} now stores the raw
 *       {@code double} confidence value internally, avoiding brittle
 *       string parsing of percentage-formatted display strings.</li>
 * </ul>
 *
 * @author Rao Hamza Bilal
 * @version 1.1 (Quality Improvements)
 */
public class BatchController implements Initializable {

    // ─────────────────────────────────────────────────────────────────────────
    // FXML UI Elements
    // ─────────────────────────────────────────────────────────────────────────

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

    @FXML private TableView<BatchResult>           resultsTable;
    @FXML private TableColumn<BatchResult, String> fileNameColumn;
    @FXML private TableColumn<BatchResult, String> predictedClassColumn;
    @FXML private TableColumn<BatchResult, String> confidenceColumn;
    @FXML private TableColumn<BatchResult, String> inferenceTimeColumn;

    // ─────────────────────────────────────────────────────────────────────────
    // Backend Fields
    // ─────────────────────────────────────────────────────────────────────────

    /** Classifier shared from the main application. */
    private SkinClassifier classifier;

    /** Selected folder path. */
    private File selectedFolder;

    /** Batch results for CSV export and summary statistics. */
    private List<BatchResult> batchResults = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Batch Result Data Model
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Represents one row in the batch results table.
     *
     * <p>JavaFX {@link TableView} requires public properties with getters
     * matching the {@link PropertyValueFactory} key strings. The raw
     * {@code rawConfidence} field is stored internally for accurate summary
     * calculations, while the formatted {@code confidence} string is used
     * for table display.</p>
     */
    public static class BatchResult {

        private final String fileName;
        private final String predictedClass;
        private final String confidence;
        private final String inferenceTime;

        /** Raw confidence value (0–100) stored for summary aggregation. */
        private final double rawConfidence;

        /**
         * Constructs one batch result row.
         *
         * @param fileName       Image file name (e.g., {@code "lesion_001.jpg"}).
         * @param predictedClass Predicted class display name (e.g., {@code "Melanoma"}).
         * @param rawConfidence  Raw confidence percentage as a {@code double} (0–100).
         * @param inferenceTime  Inference time in milliseconds as a formatted string.
         */
        public BatchResult(String fileName, String predictedClass,
                           double rawConfidence, String inferenceTime) {
            this.fileName       = fileName;
            this.predictedClass = predictedClass;
            this.rawConfidence  = rawConfidence;
            this.confidence     = String.format("%.2f%%", rawConfidence);
            this.inferenceTime  = inferenceTime;
        }

        /** @return Image file name. */
        public String getFileName()       { return fileName; }

        /** @return Predicted class display name. */
        public String getPredictedClass() { return predictedClass; }

        /**
         * @return Formatted confidence percentage string for {@link TableView}
         *         display (e.g., {@code "97.34%"}).
         */
        public String getConfidence()     { return confidence; }

        /** @return Inference time string (e.g., {@code "142 ms"}). */
        public String getInferenceTime()  { return inferenceTime; }

        /**
         * Returns the raw confidence value as a {@code double} (0–100).
         *
         * <p>Used by {@link #showSummary} to compute the average confidence
         * without parsing the formatted display string, which can fail in
         * non-US locales where {@link String#format} may produce {@code "97,34%"}
         * instead of {@code "97.34%"}.</p>
         *
         * @return Raw confidence percentage (0.0–100.0).
         */
        public double getRawConfidence()  { return rawConfidence; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called automatically by JavaFX after FXML loads.
     * Sets up the {@link TableView} columns and initializes the classifier
     * reference on a background thread.
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
                while (MainApp.getSharedClassifier() == null && attempts < 30) {
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
                System.out.println("BatchController: shared classifier connected.");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Event Handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles the Browse Folder button click.
     * Opens a {@link DirectoryChooser} and stores the selected path.
     */
    @FXML
    private void handleSelectFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Image Folder");

        File folder = chooser.showDialog(selectFolderButton.getScene().getWindow());

        if (folder != null) {
            selectedFolder = folder;
            folderPathField.setText(folder.getAbsolutePath());
            runBatchButton.setDisable(false);

            File[] images = getImageFiles(folder);
            progressLabel.setText(images.length + " images found in folder.");
            progressBox.setVisible(true);
        }
    }

    /**
     * Handles the Run Analysis button click.
     *
     * <p>Runs inference on all images in the selected folder on a background
     * thread with live progress updates. Results are appended to the table
     * as each image completes.</p>
     */
    @FXML
    private void handleRunBatch() {
        if (selectedFolder == null) return;

        File[] imageFiles = getImageFiles(selectedFolder);
        if (imageFiles.length == 0) {
            progressLabel.setText("No JPG or PNG images found in folder.");
            return;
        }

        batchResults.clear();
        resultsTable.getItems().clear();
        summaryBox.setVisible(false);
        exportCsvButton.setDisable(true);
        runBatchButton.setDisable(true);

        progressBox.setVisible(true);
        batchProgressBar.setProgress(0);
        progressCountLabel.setText("0 / " + imageFiles.length);

        long startTime = System.currentTimeMillis();

        Task<Void> batchTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                int total = imageFiles.length;

                for (int i = 0; i < total; i++) {
                    File imageFile = imageFiles[i];
                    Path imagePath = imageFile.toPath();

                    try {
                        SkinClassifier.PredictionResult result =
                                classifier.predict(imagePath);

                        // Use centralized class names from SkinClassifier
                        String fullName =
                                SkinClassifier.CLASS_FULL_NAMES[result.classIndex];

                        // Pass raw confidence (0–100) to BatchResult constructor
                        BatchResult row = new BatchResult(
                                imageFile.getName(),
                                fullName,
                                result.confidence,  // raw double, not formatted string
                                result.inferenceTimeMs + " ms"
                        );

                        batchResults.add(row);

                        final int current = i + 1;
                        final BatchResult finalRow = row;
                        Platform.runLater(() -> {
                            resultsTable.getItems().add(finalRow);
                            batchProgressBar.setProgress((double) current / total);
                            progressCountLabel.setText(current + " / " + total);
                            progressLabel.setText("Processing: " + imageFile.getName());
                        });

                    } catch (Exception e) {
                        System.err.println("Error on " + imageFile.getName()
                                + ": " + e.getMessage());
                    }
                }
                return null;
            }
        };

        batchTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                long elapsed = System.currentTimeMillis() - startTime;
                showSummary(imageFiles.length, elapsed);
                runBatchButton.setDisable(false);
                exportCsvButton.setDisable(false);
                progressLabel.setText("Analysis complete.");
                System.out.println("BatchController: processed "
                        + imageFiles.length + " images in " + elapsed + "ms");
            });
        });

        batchTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                progressLabel.setText("Batch analysis failed.");
                runBatchButton.setDisable(false);
                System.err.println("Batch error: "
                        + batchTask.getException().getMessage());
            });
        });

        Thread batchThread = new Thread(batchTask);
        batchThread.setDaemon(true);
        batchThread.start();
    }

    /**
     * Handles the Export CSV button click.
     *
     * <p>Saves all batch results to a CSV file chosen by the user via a
     * {@link javafx.stage.FileChooser} dialog. Displays a success or error
     * toast notification on completion.</p>
     */
    @FXML
    private void handleExportCsv() {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("Save Results as CSV");
        fileChooser.setInitialFileName("visolearn_batch_results.csv");
        fileChooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("CSV Files", "*.csv")
        );

        File csvFile = fileChooser.showSaveDialog(
                exportCsvButton.getScene().getWindow());

        if (csvFile != null) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
                writer.println("File Name,Predicted Class,Confidence,Inference Time (ms)");

                for (BatchResult r : batchResults) {
                    writer.printf("%s,%s,%s,%s%n",
                            r.getFileName(),
                            r.getPredictedClass(),
                            r.getConfidence(),
                            r.getInferenceTime()
                    );
                }

                progressLabel.setText("CSV saved: " + csvFile.getName());
                System.out.println("BatchController: CSV exported to "
                        + csvFile.getAbsolutePath());

                StackPane root = (StackPane) exportCsvButton.getScene().getRoot();
                ToastUtil.showToast(root, "CSV Exported Successfully!",
                        ToastUtil.ToastType.SUCCESS);

            } catch (Exception e) {
                progressLabel.setText("CSV export failed.");
                System.err.println("CSV error: " + e.getMessage());

                StackPane root = (StackPane) exportCsvButton.getScene().getRoot();
                ToastUtil.showToast(root, "Failed to export CSV.",
                        ToastUtil.ToastType.ERROR);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Summary & Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Displays summary statistics after batch analysis completes.
     *
     * <p><b>Fixed:</b> Now aggregates confidence values using
     * {@link BatchResult#getRawConfidence()} instead of parsing the
     * formatted display string, which could fail in non-US locales
     * where {@link String#format} produces {@code "97,34%"} instead
     * of {@code "97.34%"}.</p>
     *
     * @param totalImages Total number of images processed.
     * @param elapsedMs   Total processing time in milliseconds.
     */
    private void showSummary(int totalImages, long elapsedMs) {
        summaryBox.setVisible(true);
        totalImagesLabel.setText(String.valueOf(totalImages));

        Map<String, Integer> classCounts = new HashMap<>();
        double totalConfidence = 0;

        for (BatchResult r : batchResults) {
            classCounts.merge(r.getPredictedClass(), 1, Integer::sum);
            totalConfidence += r.getRawConfidence();
        }

        String topClass = classCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("—");

        topClassLabel.setText(topClass);

        double avgConf = batchResults.isEmpty() ? 0 :
                totalConfidence / batchResults.size();
        avgConfidenceLabel.setText(String.format("%.1f%%", avgConf));

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
     * @param folder The directory to scan.
     * @return Array of image files found, or an empty array if none exist.
     */
    private File[] getImageFiles(File folder) {
        File[] files = folder.listFiles(f ->
                f.isFile() && (
                        f.getName().toLowerCase().endsWith(".jpg")  ||
                                f.getName().toLowerCase().endsWith(".jpeg") ||
                                f.getName().toLowerCase().endsWith(".png")
                )
        );
        return files != null ? files : new File[0];
    }
}