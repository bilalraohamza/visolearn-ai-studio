package com.visolearn;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javax.imageio.ImageIO;
import javafx.scene.control.Alert;
import com.visolearn.data.PatientDAO;
import com.visolearn.data.PredictionDAO;
import com.visolearn.data.model.Patient;
import com.visolearn.data.model.Prediction;
import com.visolearn.utils.AnimationUtil;
import com.visolearn.utils.ReportExportUtil;
import com.visolearn.utils.SettingsManager;
import com.visolearn.utils.ToastUtil;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;

/**
 * ClassifyController controls Tab 1 of the VisoLearn AI Studio GUI.
 * Handles image upload, inference, confidence bar updates,
 * and Grad-CAM heatmap overlay toggle.
 *
 * <p><b>Threading model:</b> All DJL inference runs on a background
 * {@link Task} thread. All UI updates run on the JavaFX Application Thread
 * via {@link Platform#runLater(Runnable)} to prevent freezing.</p>
 *
 * <h3>Quality Improvements (v1.2):</h3>
 * <ul>
 * <li><b>Removed duplicated {@code CLASS_FULL_NAMES} array</b> — now uses
 * the centralized {@link SkinClassifier#CLASS_FULL_NAMES} constant.</li>
 * <li><b>Added {@code @FXML handleSaveToHistory()}</b> — fixes LoadException
 * caused by FXML referencing a method that was only wired programmatically.</li>
 * </ul>
 *
 * @author Rao Hamza Bilal
 * @version 1.2
 */
public class ClassifyController implements Initializable {

    // ─────────────────────────────────────────────────────────────────────────
    // FXML UI Elements
    // ─────────────────────────────────────────────────────────────────────────

    @FXML private StackPane imageContainer;
    @FXML private VBox      placeholderBox;
    @FXML private ImageView inputImageView;
    @FXML private ImageView heatmapImageView;
    @FXML private Button    uploadButton;
    @FXML private Button    clearButton;
    @FXML private Button    exportReportButton;
    @FXML private CheckBox  gradCamToggle;
    @FXML private HBox      loadingBox;
    @FXML private Label     loadingLabel;

    @FXML private Label predictionLabel;
    @FXML private Label confidenceLabel;
    @FXML private Label confidenceStatLabel;
    @FXML private Label inferenceTimeLabel;
    @FXML private Label descriptionLabel;

    @FXML private ProgressBar bar0, bar1, bar2, bar3, bar4, bar5, bar6;
    @FXML private Label       pct0, pct1, pct2, pct3, pct4, pct5, pct6;

    @FXML private ComboBox<Patient> patientComboBox;
    @FXML private Button            saveToHistoryButton;

    // ─────────────────────────────────────────────────────────────────────────
    // Backend Components
    // ─────────────────────────────────────────────────────────────────────────

    /** Skin lesion classifier using ensemble EfficientNet-B4 + DenseNet-169 ONNX models. */
    private SkinClassifier classifier;

    /** Grad-CAM heatmap renderer. */
    private GradCamRenderer gradCamRenderer;

    /** Currently loaded image file path. */
    private Path currentImagePath;

    /** Latest prediction result for Grad-CAM generation. */
    private SkinClassifier.PredictionResult lastResult;

    /** Whether the Grad-CAM heatmap is currently visible. */
    private boolean heatmapVisible = false;

    private final PatientDAO    patientDAO    = new PatientDAO();
    private final PredictionDAO predictionDAO = new PredictionDAO();

    private File   currentImageFile;
    private String currentPrediction;
    private double currentConfidence;
    private int    currentInferenceTime;
    private static ClassifyController instance;
    public static ClassifyController getInstance() { return instance; }
    // ─────────────────────────────────────────────────────────────────────────
    // Class Descriptions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Short clinical descriptions for each skin lesion class.
     * Shown in the description box after classification.
     * Index matches {@link SkinClassifier#CLASS_FULL_NAMES}.
     */
    private static final String[] CLASS_DESCRIPTIONS = {
            "Actinic Keratosis (AKIEC): A rough, scaly patch caused by " +
                    "years of sun exposure. Can develop into skin cancer if untreated.",
            "Basal Cell Carcinoma (BCC): The most common form of skin cancer. " +
                    "Rarely spreads but can be locally destructive if ignored.",
            "Benign Keratosis (BKL): A non-cancerous skin growth. " +
                    "Includes seborrheic keratoses and similar harmless lesions.",
            "Dermatofibroma (DF): A common benign skin nodule. " +
                    "Usually harmless and does not require treatment.",
            "Melanoma (MEL): The most dangerous form of skin cancer. " +
                    "Early detection is critical - consult a dermatologist immediately.",
            "Melanocytic Nevus (NV): A common mole. " +
                    "Usually benign but monitor for changes in size, shape, or color.",
            "Vascular Lesion (VASC): Lesions of blood vessels in the skin. " +
                    "Usually benign, including angiomas and pyogenic granulomas."
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called automatically by JavaFX after FXML is loaded.
     * Initializes the classifier reference (shared from {@link MainApp})
     * and sets up drag-and-drop support.
     *
     * @param url not used
     * @param rb  not used
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        instance = this;
        // Load patients into the dropdown on startup
        loadPatientsIntoDropdown();
        heatmapImageView.opacityProperty().bind(
                SettingsManager.gradCamOpacityProperty());

        // Format the ComboBox dropdown list for Dark Mode
        patientComboBox.setCellFactory(lv -> new ListCell<Patient>() {
            @Override
            protected void updateItem(Patient patient, boolean empty) {
                super.updateItem(patient, empty);
                if (empty || patient == null) {
                    setText(null);
                    setStyle("-fx-background-color: #1E1E2A;");
                } else {
                    setText(patient.toString());
                    // Default dark background, white text
                    setStyle("-fx-text-fill: #F8F9FA; -fx-font-size: 13px; -fx-padding: 8px 12px; -fx-background-color: #1E1E2A;");
                }

                // Add Green Hover Effect
                setOnMouseEntered(e -> {
                    if (!empty && patient != null) {
                        setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 8px 12px; -fx-background-color: #10B981; -fx-cursor: hand;");
                    }
                });
                setOnMouseExited(e -> {
                    if (!empty && patient != null) {
                        setStyle("-fx-text-fill: #F8F9FA; -fx-font-size: 13px; -fx-padding: 8px 12px; -fx-background-color: #1E1E2A;");
                    }
                });
            }
        });

        // Format the selected item (the text that shows before you click the dropdown)
        patientComboBox.setButtonCell(new ListCell<Patient>() {
            @Override
            protected void updateItem(Patient patient, boolean empty) {
                super.updateItem(patient, empty);
                if (empty || patient == null) {
                    setText(patientComboBox.getPromptText());
                    setStyle("-fx-text-fill: #9CA3AF; -fx-background-color: transparent; -fx-font-size: 13px;");
                } else {
                    setText(patient.toString());
                    setStyle("-fx-text-fill: #F8F9FA; -fx-background-color: transparent; -fx-font-size: 13px;");
                }
            }
        });

        // NOTE: saveToHistoryButton action is wired via onAction="#handleSaveToHistory"
        // in classify_tab.fxml. Do NOT add setOnAction here — it conflicts with FXML binding.

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
                gradCamRenderer = new GradCamRenderer(classifier);
                uploadButton.setDisable(false);
                predictionLabel.setText("Awaiting Image...");
                predictionLabel.setOpacity(1.0);
                descriptionLabel.setOpacity(1.0);
            });
        });

        initTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                predictionLabel.setText("Model failed to load.");
                predictionLabel.setStyle(
                        "-fx-text-fill: #E24B4A; -fx-font-size: 14px;");
            });
        });

        uploadButton.setDisable(true);
        if (exportReportButton != null) exportReportButton.setDisable(true);
        predictionLabel.setText("Loading model...");

        Thread initThread = new Thread(initTask);
        initThread.setDaemon(true);
        initThread.start();

        setupDragAndDrop();
        setupAnimations();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Patient History Integration
    // ─────────────────────────────────────────────────────────────────────────

    private void loadPatientsIntoDropdown() {
        Task<List<Patient>> loadTask = new Task<>() {
            @Override
            protected List<Patient> call() throws Exception {
                return patientDAO.searchByName(null); // Load all patients
            }
        };

        loadTask.setOnSucceeded(e ->
                patientComboBox.setItems(FXCollections.observableList(loadTask.getValue()))
        );

        new Thread(loadTask).start();
    }

    /**
     * FXML event handler for the "Save to Patient History" button.
     * This method MUST exist and be annotated with @FXML because
     * classify_tab.fxml references it via onAction="#handleSaveToHistory".
     * Without this method the app crashes with LoadException on startup.
     */
    @FXML
    private void handleSaveToHistory() {
        savePredictionToHistory();
    }

    private void savePredictionToHistory() {
        Patient selectedPatient = patientComboBox.getValue();

        if (selectedPatient == null || currentImageFile == null || currentPrediction == null) {
            ToastUtil.showToast(
                    (StackPane) saveToHistoryButton.getScene().getRoot(),
                    "Please select a patient and run a scan first.",
                    ToastUtil.ToastType.ERROR
            );
            return;
        }

        saveToHistoryButton.setDisable(true);
        saveToHistoryButton.setText("Saving...");

        Task<Integer> saveTask = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                Prediction newRecord = new Prediction(
                        0,
                        selectedPatient.id,
                        "",
                        currentPrediction,
                        currentConfidence,
                        currentInferenceTime,
                        ""
                );
                return predictionDAO.insertPrediction(newRecord, currentImageFile);
            }
        };

        saveTask.setOnSucceeded(e -> {
            saveToHistoryButton.setText("Saved Successfully!");
            ToastUtil.showToast(
                    (StackPane) saveToHistoryButton.getScene().getRoot(),
                    "Prediction saved to patient history.",
                    ToastUtil.ToastType.SUCCESS
            );
            // Refresh the History tab so the new record appears immediately
            // without requiring the user to re-select the patient manually.
            javafx.application.Platform.runLater(() -> {
                HistoryController hc = HistoryController.getInstance();
                if (hc != null) hc.refreshCurrentPatient();
            });
        });

        saveTask.setOnFailed(e -> {
            saveToHistoryButton.setDisable(false);
            saveToHistoryButton.setText("Save to Patient History");
            System.err.println("Failed to save: " + saveTask.getException().getMessage());
            ToastUtil.showToast(
                    (StackPane) saveToHistoryButton.getScene().getRoot(),
                    "Failed to save prediction.",
                    ToastUtil.ToastType.ERROR
            );
        });

        new Thread(saveTask, "SavePredictionThread").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event Handlers
    // ─────────────────────────────────────────────────────────────────────────

    /** Handles the Upload Image button click. */
    @FXML
    private void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Skin Lesion Image");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.jpg", "*.jpeg", "*.png")
        );

        File selected = fileChooser.showOpenDialog(uploadButton.getScene().getWindow());

        if (selected != null) {
            currentImageFile = selected;
            loadAndClassify(selected.toPath());
        }
    }

    /** Handles the Clear button click. Resets the UI to its initial empty state. */
    @FXML
    private void handleClear() {
        inputImageView.setImage(null);
        inputImageView.setVisible(false);
        heatmapImageView.setImage(null);
        heatmapImageView.setVisible(false);
        placeholderBox.setVisible(true);

        predictionLabel.setText("Awaiting Image...");
        predictionLabel.setOpacity(1.0);
        predictionLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #F8F9FA;");

        confidenceLabel.setText("");
        inferenceTimeLabel.setText("");

        descriptionLabel.setText("Upload a dermoscopy image to see classification results and Grad-CAM explanation.");
        descriptionLabel.setOpacity(1.0);
        descriptionLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #D1D5DB;");

        resetBars();

        currentImagePath     = null;
        currentImageFile     = null;
        currentPrediction    = null;
        currentConfidence    = 0;
        currentInferenceTime = 0;
        lastResult           = null;
        heatmapVisible       = false;
        gradCamToggle.setSelected(false);
        if (exportReportButton != null) exportReportButton.setDisable(true);

        saveToHistoryButton.setDisable(false);
        saveToHistoryButton.setText("Save to Patient History");

        confidenceLabel.setOpacity(0);
        confidenceStatLabel.setOpacity(0);
        inferenceTimeLabel.setOpacity(0);
    }

    /** Handles the Export Report button click. */
    @FXML
    private void handleExportReport() {
        if (lastResult == null || currentImagePath == null) return;

        Image original = inputImageView.getImage();
        Image heatmap  = heatmapImageView.getImage();

        String topClass    = SkinClassifier.CLASS_FULL_NAMES[lastResult.classIndex];
        double confidence  = lastResult.confidence;
        String inferenceMs = lastResult.inferenceTimeMs + " ms";

        ReportExportUtil.saveReportAsImage(
                exportReportButton.getScene().getWindow(),
                original, heatmap, topClass, confidence, inferenceMs
        );
    }

    /** Handles the Grad-CAM toggle checkbox. */
    @FXML
    private void handleGradCamToggle() {
        if (gradCamToggle.isSelected() && lastResult != null) {
            generateAndShowHeatmap();
        } else {
            heatmapImageView.setVisible(false);
            heatmapVisible = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inference Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private void loadAndClassify(Path imagePath) {
        currentImagePath = imagePath;

        try {
            Image fxImage = new Image(imagePath.toUri().toString());
            inputImageView.setImage(fxImage);
            inputImageView.setVisible(true);
            AnimationUtil.fadeIn(inputImageView, 500);
            placeholderBox.setVisible(false);
            heatmapImageView.setVisible(false);
            gradCamToggle.setSelected(false);
            if (exportReportButton != null) exportReportButton.setDisable(true);
        } catch (Exception e) {
            predictionLabel.setText("Cannot load image.");
            return;
        }

        loadingBox.setVisible(true);
        AnimationUtil.fadeIn(loadingBox, 300);
        loadingLabel.setText("Running inference...");
        uploadButton.setDisable(true);

        Task<SkinClassifier.PredictionResult> inferTask = new Task<>() {
            @Override
            protected SkinClassifier.PredictionResult call() throws Exception {
                return classifier.predict(imagePath);
            }
        };

        inferTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                lastResult           = inferTask.getValue();
                currentPrediction    = SkinClassifier.CLASS_FULL_NAMES[lastResult.classIndex];
                currentConfidence    = lastResult.confidence;
                currentInferenceTime = (int) lastResult.inferenceTimeMs;

                PauseTransition delay = new PauseTransition(Duration.millis(250));
                delay.setOnFinished(ev -> updateUIWithResult(lastResult));
                delay.play();

                loadingBox.setVisible(false);
                uploadButton.setDisable(false);
            });
        });

        inferTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                predictionLabel.setText("Inference failed.");
                loadingBox.setVisible(false);
                uploadButton.setDisable(false);
                System.err.println("Inference error: " + inferTask.getException().getMessage());
            });
        });

        Thread inferThread = new Thread(inferTask);
        inferThread.setDaemon(true);
        inferThread.start();
    }

    private void updateUIWithResult(SkinClassifier.PredictionResult result) {

        String fullName = SkinClassifier.CLASS_FULL_NAMES[result.classIndex];
        predictionLabel.setText(fullName);
        AnimationUtil.slideUp(predictionLabel, 500);
        predictionLabel.setStyle(
                "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1D9E75;");

        confidenceLabel.setText(String.format("Confidence: %.2f%%", result.confidence));
        confidenceStatLabel.setText(String.format("%.1f%%", result.confidence));
        inferenceTimeLabel.setText(String.format("Inference time: %d ms", result.inferenceTimeMs));

        descriptionLabel.setText(CLASS_DESCRIPTIONS[result.classIndex]);

        AnimationUtil.fadeIn(confidenceLabel,    700);
        AnimationUtil.fadeIn(confidenceStatLabel, 800);
        AnimationUtil.fadeIn(inferenceTimeLabel,  900);
        AnimationUtil.fadeIn(descriptionLabel,   1000);

        ProgressBar[] bars = {bar0, bar1, bar2, bar3, bar4, bar5, bar6};
        Label[]       pcts = {pct0, pct1, pct2, pct3, pct4, pct5, pct6};

        for (int i = 0; i < 7; i++) {
            float prob = result.allProbabilities[i];
            AnimationUtil.animateProgressBar(bars[i], prob, 900);
            pcts[i].setText(String.format("%.1f%%", prob * 100));
        }

        if (exportReportButton != null) exportReportButton.setDisable(false);
        saveToHistoryButton.setDisable(false);
        saveToHistoryButton.setText("Save to Patient History");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Grad-CAM Heatmap Generation
    // ─────────────────────────────────────────────────────────────────────────

    private void generateAndShowHeatmap() {
        if (currentImagePath == null || lastResult == null) return;

        loadingLabel.setText("Generating saliency map (0 / 49)...");
        loadingBox.setVisible(true);
        AnimationUtil.fadeIn(loadingBox, 300);

        Task<BufferedImage> heatmapTask = new Task<>() {
            @Override
            protected BufferedImage call() throws Exception {
                return gradCamRenderer.generateHeatmap(
                        currentImagePath, lastResult,
                        (completed, total) -> Platform.runLater(() ->
                                loadingLabel.setText(
                                        String.format("Saliency map... (%d / %d)", completed, total))
                        )
                );
            }
        };

        heatmapTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                Image fxHeatmap = SwingFXUtils.toFXImage(heatmapTask.getValue(), null);
                heatmapImageView.setImage(fxHeatmap);
                heatmapImageView.setVisible(true);
                heatmapVisible = true;
                loadingBox.setVisible(false);
            });
        });

        heatmapTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                loadingBox.setVisible(false);
                System.err.println("Grad-CAM error: " + heatmapTask.getException().getMessage());
            });
        });

        Thread heatmapThread = new Thread(heatmapTask);
        heatmapThread.setDaemon(true);
        heatmapThread.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setupDragAndDrop() {
        imageContainer.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });

        imageContainer.setOnDragDropped(event -> {
            var files = event.getDragboard().getFiles();
            if (!files.isEmpty()) {
                File dropped = files.get(0);
                String name  = dropped.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
                    currentImageFile = dropped;
                    loadAndClassify(dropped.toPath());
                }
            }
            event.consume();
        });
    }

    private void setupAnimations() {
        AnimationUtil.fadeIn(imageContainer, 700);
        AnimationUtil.applyButtonHover(uploadButton);
        AnimationUtil.applyButtonHover(clearButton);
        AnimationUtil.applyButtonHover(saveToHistoryButton);
        if (exportReportButton != null) {
            AnimationUtil.applyButtonHover(exportReportButton);
        }
        AnimationUtil.fadeIn(predictionLabel, 800);
    }

    private void resetBars() {
        ProgressBar[] bars = {bar0, bar1, bar2, bar3, bar4, bar5, bar6};
        Label[]       pcts = {pct0, pct1, pct2, pct3, pct4, pct5, pct6};
        for (int i = 0; i < 7; i++) {
            bars[i].setProgress(0);
            pcts[i].setText("0%");
        }
    }
    /** Public method to allow HistoryController to trigger a refresh */
    public void refreshPatientDropdown() {
        loadPatientsIntoDropdown();
    }
}
