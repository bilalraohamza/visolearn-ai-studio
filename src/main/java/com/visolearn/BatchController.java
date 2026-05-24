package com.visolearn;

import com.visolearn.utils.AnimationUtil;
import com.visolearn.utils.ImageValidator;
import com.visolearn.utils.ImageValidator.ValidationResult;
import com.visolearn.utils.ToastUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BatchController controls Tab 3 of VisoLearn AI Studio.
 * Allows users to select a folder of images and run inference
 * on all of them at once, displaying results in a table
 * and providing CSV export functionality.
 *
 * <h3>OOP Feature 2 — Batch Processing with ExecutorService Thread Pool (v2.0):</h3>
 * <p>Images are now processed in parallel using a
 * {@link ExecutorService} fixed-thread pool. The thread count is configurable
 * via a "Threads" {@link Slider} (range 1–4) rendered in the batch tab UI.
 * Each image is submitted as an independent {@link Callable} returning a
 * {@link BatchResult}. Futures are collected in a {@link List} and a
 * {@link CompletableFuture} is used to detect overall completion.</p>
 *
 * <h4>Thread-Safety Notes:</h4>
 * <ul>
 *   <li>{@link SkinClassifier#predict(Path)} internally uses two shared
 *       {@link ai.djl.inference.Predictor} instances. To allow concurrent
 *       calls from the pool threads, invocations are {@code synchronized}
 *       on the classifier instance, serialising ONNX execution while still
 *       allowing the pool to manage per-image overhead in parallel.</li>
 *   <li>{@link BatchResult#statusProperty()} is a {@link StringProperty} that
 *       is mutated only via {@link Platform#runLater} to keep all JavaFX
 *       updates on the FX Application Thread.</li>
 * </ul>
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
 * @version 2.0 (ExecutorService Batch Processing)
 */
public class BatchController implements Initializable {

    // ─────────────────────────────────────────────────────────────────────────
    // Application context — injected via FXMLLoader.setControllerFactory()
    // ─────────────────────────────────────────────────────────────────────────

    private final AppContext ctx;

    /**
     * Constructor called by {@link MainApp}'s controller factory.
     * The {@link AppContext} is the only application-level dependency;
     * no static accessors are used.
     *
     * @param ctx the application context carrying the classifier future
     */
    public BatchController(AppContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("AppContext must not be null");
        this.ctx = ctx;
    }

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

    /** Slider controlling the thread pool size (1–4). */
    @FXML private Slider       threadSlider;

    /** Label that mirrors the current thread slider value (e.g. "2 Threads"). */
    @FXML private Label        threadCountLabel;

    @FXML private TableView<BatchResult>           resultsTable;
    @FXML private TableColumn<BatchResult, String> fileNameColumn;
    @FXML private TableColumn<BatchResult, String> predictedClassColumn;
    @FXML private TableColumn<BatchResult, String> confidenceColumn;
    @FXML private TableColumn<BatchResult, String> inferenceTimeColumn;

    /**
     * Status column — shows "Processing…" while the worker thread is running,
     * then updates to "✓ Done" or "✗ Error" via {@link Platform#runLater}.
     */
    @FXML private TableColumn<BatchResult, String> statusColumn;

    /**
     * The four thread-count option tiles (Single / Default / Fast / Max).
     * Their inline CSS is swapped by {@link #highlightTile(int)} whenever the
     * slider value changes or a tile is clicked directly.
     */
    @FXML private VBox tile1;
    @FXML private VBox tile2;
    @FXML private VBox tile3;
    @FXML private VBox tile4;

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
     *
     * <p>The {@link #statusProperty()} is a mutable {@link StringProperty}
     * so that the status column can update live without replacing the row
     * object in the table's {@link javafx.collections.ObservableList}.</p>
     */
    public static class BatchResult {

        private final String fileName;
        private String predictedClass;
        private String confidence;
        private String inferenceTime;

        /** Raw confidence value (0–100) stored for summary aggregation. */
        private double rawConfidence;

        /**
         * Observable status string shown in the status column.
         * Starts as "⏳ Processing…" and is updated on the FX thread once
         * the worker callable completes.
         */
        private final StringProperty status = new SimpleStringProperty("⏳ Processing…");

        /**
         * Constructs a placeholder batch result row used when the image is
         * first queued. All result fields start as empty/default; they are
         * filled in by {@link #complete(String, double, String)} or
         * {@link #fail()} once the worker finishes.
         *
         * @param fileName Image file name (e.g., {@code "lesion_001.jpg"}).
         */
        public BatchResult(String fileName) {
            this.fileName        = fileName;
            this.predictedClass  = "—";
            this.confidence      = "—";
            this.inferenceTime   = "—";
            this.rawConfidence   = 0.0;
        }

        /**
         * Fills in the prediction result fields and marks status as done.
         * Must be called on the FX Application Thread (inside
         * {@link Platform#runLater}).
         *
         * @param predictedClass Predicted class display name.
         * @param rawConf        Raw confidence percentage (0.0–100.0).
         * @param infTimeMs      Inference time formatted string (e.g., "142 ms").
         */
        public void complete(String predictedClass, double rawConf, String infTimeMs) {
            this.predictedClass = predictedClass;
            this.rawConfidence  = rawConf;
            this.confidence     = String.format("%.2f%%", rawConf);
            this.inferenceTime  = infTimeMs;
            this.status.set("✓ Done");
        }

        /**
         * Marks this row as failed. Must be called on the FX Application Thread.
         */
        public void fail() {
            this.status.set("✗ Error");
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

        /**
         * Observable property bound to the status {@link TableColumn}.
         * Enables live cell refresh without removing/re-adding the row.
         *
         * @return {@link StringProperty} for the current status text.
         */
        public StringProperty statusProperty() { return status; }

        /** @return Current status string (convenience accessor). */
        public String getStatus()         { return status.get(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called automatically by JavaFX after FXML loads.
     * Sets up the {@link TableView} columns, initialises the thread
     * slider, and waits for the shared classifier on a background thread.
     *
     * @param url not used
     * @param rb  not used
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // ── Table columns ────────────────────────────────────────────────────
        fileNameColumn.setCellValueFactory(
                new PropertyValueFactory<>("fileName"));
        predictedClassColumn.setCellValueFactory(
                new PropertyValueFactory<>("predictedClass"));
        confidenceColumn.setCellValueFactory(
                new PropertyValueFactory<>("confidence"));
        inferenceTimeColumn.setCellValueFactory(
                new PropertyValueFactory<>("inferenceTime"));

        // Status column binds to the observable StringProperty for live updates
        statusColumn.setCellValueFactory(
                cellData -> cellData.getValue().statusProperty());

        // Apply CSS styling to status cells
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (item.startsWith("✓")) {
                        setStyle("-fx-text-fill: #10B981; -fx-font-weight: bold;");
                    } else if (item.startsWith("✗")) {
                        setStyle("-fx-text-fill: #EF4444; -fx-font-weight: bold;");
                    } else {
                        // "⏳ Processing…"
                        setStyle("-fx-text-fill: #F59E0B; -fx-font-weight: bold;");
                    }
                }
            }
        });

        // ── Thread slider ────────────────────────────────────────────────────
        if (threadSlider != null) {
            threadSlider.setMin(1);
            threadSlider.setMax(4);
            threadSlider.setValue(2);
            threadSlider.setMajorTickUnit(1);
            threadSlider.setMinorTickCount(0);
            threadSlider.setSnapToTicks(true);
            threadSlider.setShowTickLabels(true);
            threadSlider.setShowTickMarks(true);

            // Initial state
            int initial = (int) threadSlider.getValue();
            updateThreadLabel(initial);
            highlightTile(initial);

            // Sync tiles whenever the slider moves
            threadSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                int v = newVal.intValue();
                updateThreadLabel(v);
                highlightTile(v);
            });
        }

        // Clicking a tile also moves the slider
        wireTileClick(tile1, 1);
        wireTileClick(tile2, 2);
        wireTileClick(tile3, 3);
        wireTileClick(tile4, 4);

        // Disable select button synchronously until the classifier is ready
        selectFolderButton.setDisable(true);

        // Subscribe to the classifier future instead of polling in a loop.
        // whenComplete() fires the instant initialization succeeds or fails —
        // no 15-second hard timeout, no NullPointerException on slow machines.
        ctx.getClassifierFuture().whenComplete((readyClassifier, ex) ->
            Platform.runLater(() -> {
                if (ex != null) {
                    // Initialization failed — show a clear error in the progress label
                    progressLabel.setText("Model failed to load: " + ex.getMessage());
                    System.err.println("BatchController: classifier error — " + ex.getMessage());
                } else {
                    // Initialization succeeded — wire up the classifier and unlock the UI
                    classifier = readyClassifier;
                    selectFolderButton.setDisable(false);
                    System.out.println("BatchController: classifier ready.");
                }
            })
        );


        setupAnimations();
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
     * <p>Replaces the previous single {@link Task} with an
     * {@link ExecutorService} fixed-thread pool whose size is driven by the
     * "Threads" slider. Each image file is submitted as a {@link Callable}
     * that synchronises on the classifier, runs inference, then updates
     * its placeholder row via {@link Platform#runLater}. A
     * {@link CompletableFuture} created from all per-image futures is used
     * to detect overall completion and show the summary.</p>
     *
     * <h4>Concurrency Design:</h4>
     * <pre>{@code
     * ExecutorService pool = Executors.newFixedThreadPool(threadCount);
     * List<CompletableFuture<Void>> futures = new ArrayList<>();
     * for (File f : imageFiles) {
     *     futures.add(CompletableFuture.runAsync(() -> processImage(f), pool));
     * }
     * CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
     *     .whenComplete((v, ex) -> Platform.runLater(() -> onBatchComplete()));
     * pool.shutdown();
     * }</pre>
     */
    @FXML
    private void handleRunBatch() {
        if (selectedFolder == null) return;

        File[] imageFiles = getImageFiles(selectedFolder);
        if (imageFiles.length == 0) {
            progressLabel.setText("No JPG or PNG images found in folder.");
            return;
        }

        // ── Determine thread count from slider ───────────────────────────────
        int threadCount = (threadSlider != null)
                ? Math.max(1, Math.min(4, (int) threadSlider.getValue()))
                : 2;

        // ── Reset UI state ───────────────────────────────────────────────────
        batchResults.clear();
        resultsTable.getItems().clear();
        summaryBox.setVisible(false);
        exportCsvButton.setDisable(true);
        runBatchButton.setDisable(true);

        progressBox.setVisible(true);
        batchProgressBar.setProgress(0);
        int total = imageFiles.length;
        progressCountLabel.setText("0 / " + total);
        progressLabel.setText("Starting " + threadCount + "-thread batch…");

        // ── Pre-populate the table with placeholder rows ─────────────────────
        // Each row starts with "⏳ Processing…" status so the user immediately
        // sees all queued images before any results arrive.
        List<BatchResult> placeholders = new ArrayList<>();
        for (File f : imageFiles) {
            BatchResult placeholder = new BatchResult(f.getName());
            placeholders.add(placeholder);
            batchResults.add(placeholder);
        }
        resultsTable.getItems().addAll(placeholders);

        long startTime = System.currentTimeMillis();

        // ── Atomic counter for progress bar updates ──────────────────────────
        AtomicInteger completedCount = new AtomicInteger(0);

        // ── Create fixed-thread pool ─────────────────────────────────────────
        // threadIndex gives each worker a unique name (e.g. "batch-worker-1",
        // "batch-worker-2") regardless of pool size. Using threadCount here
        // would give every thread the same name, making stack traces useless.
        AtomicInteger threadIndex = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
                r -> {
                    Thread t = new Thread(r, "batch-worker-" + threadIndex.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });

        // ── Submit one Callable<Void> per image ──────────────────────────────
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < imageFiles.length; i++) {
            final File   imageFile   = imageFiles[i];
            final Path   imagePath   = imageFile.toPath();
            final BatchResult row    = placeholders.get(i);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {

                // ── Pre-flight validation ────────────────────────────────────
                ValidationResult vr = ImageValidator.validate(imageFile);
                if (!vr.valid()) {
                    System.err.println("Batch validation failed for "
                            + imageFile.getName() + ": " + vr.title());
                    Platform.runLater(() -> {
                        row.fail();
                        resultsTable.refresh();
                        int done = completedCount.incrementAndGet();
                        batchProgressBar.setProgress((double) done / total);
                        progressCountLabel.setText(done + " / " + total);
                        progressLabel.setText("Skipped (invalid): " + imageFile.getName());
                    });
                    return;
                }

                // ── Per-worker PredictorPair ─────────────────────────────────
                // Each worker creates its own pair of Predictor instances from
                // the shared (thread-safe) ZooModel objects. No synchronization
                // is needed: DJL Predictor is per-thread; ZooModel is shared.
                // The try-with-resources guarantees the pair is always closed,
                // releasing native ONNX Runtime memory when the worker finishes.
                try (SkinClassifier.PredictorPair pair = classifier.newPredictorPair()) {

                    SkinClassifier.PredictionResult result = pair.predict(imagePath);

                    String fullName = SkinClassifier.CLASS_FULL_NAMES[result.classIndex];
                    double rawConf  = result.confidence;
                    String timeStr  = result.inferenceTimeMs + " ms";

                    Platform.runLater(() -> {
                        row.complete(fullName, rawConf, timeStr);
                        resultsTable.refresh();
                        int done  = completedCount.incrementAndGet();
                        batchProgressBar.setProgress((double) done / total);
                        progressCountLabel.setText(done + " / " + total);
                        progressLabel.setText("Processed: " + imageFile.getName());
                    });

                } catch (Exception ex) {
                    System.err.println("Batch error on " + imageFile.getName()
                            + ": " + ex.getMessage());
                    Platform.runLater(() -> {
                        row.fail();
                        resultsTable.refresh();
                        int done = completedCount.incrementAndGet();
                        batchProgressBar.setProgress((double) done / total);
                        progressCountLabel.setText(done + " / " + total);
                    });
                }

            }, pool);


            futures.add(future);
        }

        // ── Wait for all futures, then show summary ──────────────────────────
        // CompletableFuture.allOf() returns a new future that completes when
        // every submitted task is done (success or failure).
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((result, throwable) -> {
                    // Shutdown the pool — no new tasks will be submitted
                    pool.shutdown();

                    long elapsed = System.currentTimeMillis() - startTime;

                    Platform.runLater(() -> {
                        showSummary(imageFiles.length, elapsed);
                        runBatchButton.setDisable(false);
                        exportCsvButton.setDisable(false);
                        progressLabel.setText("Analysis complete. ("
                                + threadCount + " thread"
                                + (threadCount > 1 ? "s" : "") + ")");
                        System.out.printf("BatchController: %d images in %d ms "
                                + "using %d thread(s)%n",
                                imageFiles.length, elapsed, threadCount);
                    });
                });
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
                writer.println("File Name,Predicted Class,Confidence,Inference Time (ms),Status");

                for (BatchResult r : batchResults) {
                    writer.printf("%s,%s,%s,%s,%s%n",
                            r.getFileName(),
                            r.getPredictedClass(),
                            r.getConfidence(),
                            r.getInferenceTime(),
                            r.getStatus()
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
            // Only count rows that completed successfully (not errors / pending)
            if (r.getStatus().startsWith("✓")) {
                classCounts.merge(r.getPredictedClass(), 1, Integer::sum);
                totalConfidence += r.getRawConfidence();
            }
        }

        String topClass = classCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("—");

        topClassLabel.setText(topClass);

        long successCount = batchResults.stream()
                .filter(r -> r.getStatus().startsWith("✓"))
                .count();

        double avgConf = (successCount == 0) ? 0 : totalConfidence / successCount;
        avgConfidenceLabel.setText(String.format("%.1f%%", avgConf));

        if (elapsedMs < 1000) {
            processingTimeLabel.setText(elapsedMs + " ms");
        } else {
            processingTimeLabel.setText(
                    String.format("%.1f s", elapsedMs / 1000.0));
        }
    }

    /**
     * Updates the thread count label to reflect the current slider value.
     *
     * @param count Current thread count from the slider.
     */
    private void updateThreadLabel(int count) {
        if (threadCountLabel != null) {
            threadCountLabel.setText(count + " Thread" + (count > 1 ? "s" : ""));
        }
    }

    // Inactive tile style — computed per call so it adapts to dark/light theme.
    // Static constants are NOT used for inactive because the dark-mode values
    // (near-white rgba overlays, #F8F9FA text) are invisible in light mode.

    // Active tile style — purple tint and glow border (looks correct in both themes)
    private static final String TILE_ACTIVE =
            "-fx-background-color: rgba(167,139,250,0.16);" +
            "-fx-background-radius: 8; -fx-padding: 10 14 10 14;" +
            "-fx-border-color: rgba(167,139,250,0.45);" +
            "-fx-border-radius: 8; -fx-border-width: 1;" +
            "-fx-min-width: 56; -fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian,rgba(167,139,250,0.25),8,0,0,0);";

    // Label styles for the active state (purple — same in both themes)
    private static final String TILE_NUM_ACTIVE =
            "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #A78BFA;";
    private static final String TILE_LBL_INACTIVE =
            "-fx-font-size: 9px; -fx-text-fill: #6B7280;";
    private static final String TILE_LBL_ACTIVE =
            "-fx-font-size: 9px; -fx-text-fill: #A78BFA; -fx-font-weight: bold;";


    /**
     * Highlights the tile that corresponds to {@code activeThread} and dims
     * all others. Reads the current theme at call time so inactive tiles use
     * the correct text and border colours in both dark and light modes.
     *
     * @param activeThread Thread count whose tile should be highlighted (1–4).
     */
    private void highlightTile(int activeThread) {
        boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();

        // Inactive tile container: dark mode uses a translucent white overlay;
        // light mode uses a neutral translucent grey so the border is visible.
        String tileInactive = isDark
                ? "-fx-background-color: rgba(255,255,255,0.04);" +
                  "-fx-background-radius: 8; -fx-padding: 10 14 10 14;" +
                  "-fx-border-color: rgba(255,255,255,0.12);" +
                  "-fx-border-radius: 8; -fx-border-width: 1;" +
                  "-fx-min-width: 56; -fx-cursor: hand;"
                : "-fx-background-color: rgba(0,0,0,0.04);" +
                  "-fx-background-radius: 8; -fx-padding: 10 14 10 14;" +
                  "-fx-border-color: rgba(0,0,0,0.12);" +
                  "-fx-border-radius: 8; -fx-border-width: 1;" +
                  "-fx-min-width: 56; -fx-cursor: hand;";

        // Inactive number label: white in dark mode, near-black in light mode.
        String tileNumInactive = isDark
                ? "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #F8F9FA;"
                : "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1E293B;";

        VBox[] tiles = { tile1, tile2, tile3, tile4 };
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] == null) continue;
            boolean active = (i + 1) == activeThread;
            tiles[i].setStyle(active ? TILE_ACTIVE : tileInactive);
            // Child 0 = number label, Child 1 = caption label
            if (tiles[i].getChildren().size() >= 2) {
                javafx.scene.control.Label numLbl =
                        (javafx.scene.control.Label) tiles[i].getChildren().get(0);
                javafx.scene.control.Label capLbl =
                        (javafx.scene.control.Label) tiles[i].getChildren().get(1);
                numLbl.setStyle(active ? TILE_NUM_ACTIVE : tileNumInactive);
                capLbl.setStyle(active ? TILE_LBL_ACTIVE : TILE_LBL_INACTIVE);
            }
        }
    }

    /**
     * Makes a tile VBox clickable: clicking it moves the slider to {@code value},
     * which triggers the slider listener and calls {@link #highlightTile(int)}
     * automatically.
     *
     * @param tile  The tile VBox to attach the click handler to.
     * @param value The thread count this tile represents.
     */
    private void wireTileClick(VBox tile, int value) {
        if (tile == null || threadSlider == null) return;
        tile.setOnMouseClicked(e -> threadSlider.setValue(value));
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

    private void setupAnimations() {
        AnimationUtil.applyButtonHover(selectFolderButton);
        AnimationUtil.applyButtonHover(runBatchButton);
        AnimationUtil.applyButtonHover(exportCsvButton);
    }
}