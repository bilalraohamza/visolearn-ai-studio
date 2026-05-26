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
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import com.visolearn.data.model.Patient;
import com.visolearn.service.ClassificationService;
import com.visolearn.service.PatientService;
import com.visolearn.data.PatientDAO;
import com.visolearn.data.PredictionDAO;
import com.visolearn.utils.AnimationUtil;
import com.visolearn.utils.ImageValidator;
import com.visolearn.utils.ImageValidator.ValidationResult;
import com.visolearn.utils.PdfReportExporter;
import com.visolearn.utils.ReportExportUtil;
import com.visolearn.utils.RiskLevel;
import com.visolearn.utils.SettingsManager;
import com.visolearn.utils.ToastUtil;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.control.TextInputControl;
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
 * and occlusion-sensitivity heatmap overlay toggle.
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
    public ClassifyController(AppContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("AppContext must not be null");
        this.ctx = ctx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FXML UI Elements
    // ─────────────────────────────────────────────────────────────────────────

    @FXML private StackPane imageContainer;
    @FXML private VBox      placeholderBox;
    @FXML private StackPane plusIcon;
    @FXML private ImageView inputImageView;
    @FXML private ImageView heatmapImageView;
    @FXML private Button    uploadButton;
    @FXML private Button    clearButton;
    @FXML private Button    exportReportButton;
    @FXML private CheckBox  occlusionToggle;
    @FXML private HBox      loadingBox;
    @FXML private Label     loadingLabel;

    @FXML private Label predictionLabel;
    @FXML private Label confidenceLabel;
    @FXML private Label confidenceStatLabel;
    @FXML private Label inferenceTimeLabel;
    @FXML private TextArea notesArea;
    @FXML private Label notesStatusLabel;
    @FXML private HBox  riskBanner;
    @FXML private Label riskLabel;

    @FXML private VBox saliencyOverlay;
    @FXML private ProgressIndicator saliencySpinner;
    @FXML private Label saliencyCountLabel;
    @FXML private ProgressBar saliencyProgressBar;
    @FXML private Label saliencyPctLabel;

    @FXML private ProgressBar bar0, bar1, bar2, bar3, bar4, bar5, bar6;
    @FXML private Label       pct0, pct1, pct2, pct3, pct4, pct5, pct6;
    @FXML private Label       lbl0, lbl1, lbl2, lbl3, lbl4, lbl5, lbl6;

    @FXML private ComboBox<Patient> patientComboBox;
    @FXML private Button            saveToHistoryButton;

    @FXML private HBox errorBanner;
    @FXML private Label errorLabel;
    
    @FXML private Label shortcutHintLabel;

    @FXML private RadioButton scoreCamRadio;
    @FXML private RadioButton occlusionRadio;
    @FXML private ToggleGroup saliencyModeGroup;
    @FXML private HBox        saliencyModeBox;

    // ─────────────────────────────────────────────────────────────────────────
    // Backend Components
    // ─────────────────────────────────────────────────────────────────────────

    /** Skin lesion classifier using ensemble EfficientNet-B4 + DenseNet-169 ONNX models. */
    private SkinClassifier classifier;

    /** Occlusion-sensitivity heatmap renderer (precise mode). */
    private OcclusionRenderer occlusionRenderer;

    /** Score-CAM saliency map renderer (fast mode). */
    private ScoreCamRenderer scoreCamRenderer;

    /**
     * Saliency mode flag.
     * true  = Score-CAM (fast, default)
     * false = Occlusion Sensitivity (precise)
     */
    private boolean useScoreCam = true;

    /** Currently loaded image file path. */
    private Path currentImagePath;

    /** Latest prediction result for occlusion map generation. */
    private SkinClassifier.PredictionResult lastResult;

    private final PatientService        patientService        = new PatientService(new PatientDAO());
    private final ClassificationService classificationService = new ClassificationService(new PredictionDAO());

    private File   currentImageFile;
    private String currentPrediction;
    private double currentConfidence;
    private int    currentInferenceTime;

    /**
     * Background-color pulse Timeline shown when risk level is URGENT.
     * Fades the banner between #FEF2F2 and #FECACA every 1.2 s.
     * Null when not active.
     */
    private Timeline urgentBgPulse;

    /**
     * Scale pulse on the ⚠ riskLabel shown when risk level is URGENT.
     * Breathes the label between 1.0× and 1.06× scale every 0.6 s (auto-reverse).
     * Null when not active.
     */
    private ScaleTransition urgentIconPulse;
    // ─────────────────────────────────────────────────────────────────────────

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
        // Register with the ControllerBus so other controllers can reach this
        // instance via ControllerBus.ifPresent(ClassifyController.class, ...) 
        // without holding a strong static reference.
        ControllerBus.register(this);
        // Load patients into the dropdown on startup
        loadPatientsIntoDropdown();
        heatmapImageView.opacityProperty().bind(
                SettingsManager.heatmapOpacityProperty());

        // Bind image sizes to container size
        inputImageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(4));
        inputImageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(4));
        heatmapImageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(4));
        heatmapImageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(4));

        // Format the ComboBox dropdown list — theme-aware colors
        patientComboBox.setCellFactory(lv -> new ListCell<Patient>() {
            @Override
            protected void updateItem(Patient patient, boolean empty) {
                super.updateItem(patient, empty);
                boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
                String bg = isDark ? "#1E1E2A" : "#FFFFFF";
                String fg = isDark ? "#F8F9FA" : "#1E293B";
                if (empty || patient == null) {
                    setText(null);
                    setStyle("-fx-background-color: " + bg + ";");
                } else {
                    setText(patient.toString());
                    setStyle("-fx-text-fill: " + fg + "; -fx-font-size: 13px; -fx-padding: 8px 12px; -fx-background-color: " + bg + ";");
                }

                // Add Green Hover Effect
                setOnMouseEntered(e -> {
                    if (!empty && patient != null) {
                        setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 8px 12px; -fx-background-color: #10B981; -fx-cursor: hand;");
                    }
                });
                setOnMouseExited(e -> {
                    if (!empty && patient != null) {
                        boolean dark = com.visolearn.utils.SettingsManager.isDarkMode();
                        String exitBg = dark ? "#1E1E2A" : "#FFFFFF";
                        String exitFg = dark ? "#F8F9FA" : "#1E293B";
                        setStyle("-fx-text-fill: " + exitFg + "; -fx-font-size: 13px; -fx-padding: 8px 12px; -fx-background-color: " + exitBg + ";");
                    }
                });
            }
        });

        // Format the selected item (the text that shows before you click the dropdown)
        patientComboBox.setButtonCell(new ListCell<Patient>() {
            @Override
            protected void updateItem(Patient patient, boolean empty) {
                super.updateItem(patient, empty);
                boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
                String fg = isDark ? "#F8F9FA" : "#1E293B";
                if (empty || patient == null) {
                    setText(patientComboBox.getPromptText());
                    setStyle("-fx-text-fill: " + (isDark ? "#9CA3AF" : "#64748B") + "; -fx-background-color: transparent; -fx-font-size: 13px;");
                } else {
                    setText(patient.toString());
                    setStyle("-fx-text-fill: " + fg + "; -fx-background-color: transparent; -fx-font-size: 13px;");
                }
            }
        });

        // NOTE: saveToHistoryButton action is wired via onAction="#handleSaveToHistory"
        // in classify_tab.fxml. Do NOT add setOnAction here — it conflicts with FXML binding.

        // Disable controls synchronously until the classifier is ready
        uploadButton.setDisable(true);
        if (exportReportButton != null) exportReportButton.setDisable(true);
        predictionLabel.setText("Loading model...");

        // Subscribe to the classifier future instead of polling in a loop.
        // whenComplete() fires the instant initialization succeeds or fails —
        // no 15-second hard timeout, no NullPointerException on slow machines.
        ctx.getClassifierFuture().whenComplete((readyClassifier, ex) ->
            Platform.runLater(() -> {
                if (ex != null) {
                    // Initialization failed — show a clear error message
                    predictionLabel.setText("Model failed to load.");
                    predictionLabel.setStyle("-fx-text-fill: #E24B4A; -fx-font-size: 14px;");
                    System.err.println("ClassifyController: classifier error — " + ex.getMessage());
                } else {
                    // Initialization succeeded — wire up the classifier and unlock the UI
                    classifier = readyClassifier;
                    occlusionRenderer = new OcclusionRenderer(classifier);
                    scoreCamRenderer  = new ScoreCamRenderer(classifier);
                    uploadButton.setDisable(false);
                    predictionLabel.setText("Awaiting Image...");
                    predictionLabel.setOpacity(1.0);
                    System.out.println("ClassifyController: classifier ready.");
                }
            })
        );


        uploadButton.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                setupKeyboardShortcuts(newScene);
            }
        });

        boolean isDark = SettingsManager.isDarkMode();
        if (shortcutHintLabel != null) {
            shortcutHintLabel.setStyle("-fx-text-fill: " + (isDark ? "#4B5563" : "#94A3B8") + "; -fx-font-size: 11px;");
        }
        SettingsManager.darkModeProperty().addListener((obs, oldVal, newVal) -> {
            if (shortcutHintLabel != null) {
                shortcutHintLabel.setStyle("-fx-text-fill: " + (newVal ? "#4B5563" : "#94A3B8") + "; -fx-font-size: 11px;");
            }
        });

        setupDragAndDrop();
        setupAnimations();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Patient History Integration
    // ─────────────────────────────────────────────────────────────────────────

    private void setupKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown()) {
                switch (event.getCode()) {
                    case O -> {
                        handleUpload();
                        event.consume();
                    }
                    case E -> {
                        if (exportReportButton != null && !exportReportButton.isDisabled()) {
                            handleExportReport();
                            event.consume();
                        }
                    }
                    case S -> {
                        if (saveToHistoryButton != null && !saveToHistoryButton.isDisabled()) {
                            handleSaveToHistory();
                            event.consume();
                        }
                    }
                }
            }
            if (event.getCode() == KeyCode.SPACE && occlusionToggle != null && !occlusionToggle.isDisabled()) {
                if (scene.getFocusOwner() instanceof TextInputControl) return;
                occlusionToggle.setSelected(!occlusionToggle.isSelected());
                handleOcclusionToggle();
                event.consume();
            }
            if (event.getCode() == KeyCode.ESCAPE) {
                if (currentImageFile != null) {
                    handleClear();
                    event.consume();
                }
            }
        });
    }

    private void loadPatientsIntoDropdown() {
        Task<List<Patient>> loadTask = new Task<>() {
            @Override
            protected List<Patient> call() throws Exception {
                return patientService.getAllPatients();
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

        // Snapshot notes text NOW on the FX thread — TextArea.getText() must NOT
        // be called from a background thread (Task.call runs off-FX-thread).
        final String notesSnapshot = (notesArea != null) ? notesArea.getText() : "";

        Task<Integer> saveTask = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return classificationService.savePrediction(
                        selectedPatient.id,
                        currentPrediction,
                        currentConfidence,
                        currentInferenceTime,
                        currentImageFile,
                        notesSnapshot
                );
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
            ControllerBus.ifPresent(HistoryController.class, HistoryController::refreshCurrentPatient);
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
            ValidationResult vr = ImageValidator.validate(selected);
            if (!vr.valid()) {
                ToastUtil.showToast(
                        (StackPane) uploadButton.getScene().getRoot(),
                        vr.title() + ": " + vr.detail(),
                        ToastUtil.ToastType.ERROR
                );
                return;
            }
            if (vr.isWarning()) {
                ToastUtil.showToast(
                        (StackPane) uploadButton.getScene().getRoot(),
                        vr.title() + ": " + vr.detail(),
                        ToastUtil.ToastType.INFO
                );
                // Warning is non-fatal — fall through and load the image.
            }
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

        // Use theme-aware colors: check current mode instead of hardcoding dark colors
        boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
        String textPrimary = isDark ? "#F8F9FA" : "#0F172A";

        predictionLabel.setText("Awaiting Image...");
        predictionLabel.setOpacity(1.0);
        predictionLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + textPrimary + ";");

        confidenceLabel.setText("");
        inferenceTimeLabel.setText("");

        resetBars();

        currentImagePath     = null;
        currentImageFile     = null;
        currentPrediction    = null;
        currentConfidence    = 0;
        currentInferenceTime = 0;
        lastResult           = null;
        occlusionToggle.setSelected(false);
        occlusionToggle.setDisable(true);
        useScoreCam = true;
        if (scoreCamRadio != null) scoreCamRadio.setSelected(true);
        if (saliencyModeBox != null) {
            saliencyModeBox.setVisible(false);
            saliencyModeBox.setManaged(false);
        }
        if (exportReportButton != null) exportReportButton.setDisable(true);

        if (notesArea != null) {
            notesArea.clear();
            notesArea.setDisable(true);
        }

        saveToHistoryButton.setDisable(true);
        saveToHistoryButton.setText("Save to Patient History");

        confidenceLabel.setOpacity(0);
        confidenceStatLabel.setOpacity(0);
        inferenceTimeLabel.setOpacity(0);

        // Stop any running urgent animations before hiding the banner
        stopUrgentAnimations();

        if (riskBanner != null) {
            riskBanner.setVisible(false);
            riskBanner.setManaged(false);
        }
        if (errorBanner != null) {
            errorBanner.setVisible(false);
            errorBanner.setManaged(false);
        }
        if (saliencyOverlay != null) {
            saliencyOverlay.setVisible(false);
            saliencyOverlay.setManaged(false);
            if (saliencyProgressBar != null) saliencyProgressBar.setProgress(0);
        }
    }


    /**
     * Shows a popup to let the user select between PNG and PDF export.
     */
    @FXML
    private void handleExportReport() {
        if (lastResult == null || inputImageView.getImage() == null) return;

        java.util.List<String> choices = new java.util.ArrayList<>();
        choices.add("Export as PDF (Clinical Document)");
        choices.add("Export as PNG (High Resolution Image)");

        javafx.scene.control.ChoiceDialog<String> dialog = new javafx.scene.control.ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle("Export Clinical Report");
        dialog.setHeaderText("Choose export format");
        dialog.setContentText("Format:");

        com.visolearn.MainController.applyThemeToDialog(dialog, com.visolearn.utils.SettingsManager.isDarkMode());

        java.util.Optional<String> choice = dialog.showAndWait();
        if (choice.isPresent()) {
            if (choice.get().contains("PDF")) {
                handleExportPdfInternal();
            } else {
                handleExportPngInternal();
            }
        }
    }

    private void handleExportPngInternal() {
        Image original = inputImageView.getImage();
        Image heatmap  = heatmapImageView.getImage();

        String notes = notesArea != null ? notesArea.getText() : "";

        ReportExportUtil.saveReportAsImage(
                exportReportButton.getScene().getWindow(),
                original, heatmap, lastResult, notes
        );
    }

    /** Handles the occlusion-sensitivity heatmap toggle checkbox. */
    @FXML
    private void handleOcclusionToggle() {
        boolean selected = occlusionToggle.isSelected();
        if (saliencyModeBox != null) {
            saliencyModeBox.setVisible(selected);
            saliencyModeBox.setManaged(selected);
        }
        if (selected && lastResult != null) {
            generateAndShowHeatmap();
        } else {
            heatmapImageView.setVisible(false);
        }
    }

    @FXML
    private void handleSaliencyModeChange() {
        if (scoreCamRadio != null) {
            useScoreCam = scoreCamRadio.isSelected();
        }
        // If a heatmap is already showing, regenerate with the new mode
        if (occlusionToggle.isSelected() && lastResult != null) {
            heatmapImageView.setVisible(false);
            generateAndShowHeatmap();
        }
    }

    private void handleExportPdfInternal() {
        if (lastResult == null || inputImageView.getImage() == null) return;

        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("Export Clinical Report PDF");
        fileChooser.setInitialFileName("visolearn_report.pdf");
        fileChooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF Documents", "*.pdf")
        );

        File file = fileChooser.showSaveDialog(exportReportButton.getScene().getWindow());

        String notes = notesArea != null ? notesArea.getText() : "";

        if (file != null) {
            try {
                PdfReportExporter.exportClinicalReport(
                        file, 
                        lastResult, 
                        inputImageView.getImage(), 
                        heatmapImageView.getImage(),
                        notes
                );
                StackPane root = (StackPane) exportReportButton.getScene().getRoot();
                ToastUtil.showToast(root, "PDF Report exported successfully!", ToastUtil.ToastType.SUCCESS);
            } catch (Exception e) {
                e.printStackTrace();
                StackPane root = (StackPane) exportReportButton.getScene().getRoot();
                ToastUtil.showToast(root, "Failed to export PDF: " + e.getMessage(), ToastUtil.ToastType.ERROR);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inference Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private void loadAndClassify(Path imagePath) {
        currentImagePath = imagePath;
        currentImageFile = imagePath.toFile();

        if (errorBanner != null) {
            errorBanner.setVisible(false);
            errorBanner.setManaged(false);
        }

        if (notesArea != null) {
            notesArea.setDisable(false);
            notesArea.clear();
        }

        try {
            Image fxImage = new Image(imagePath.toUri().toString());
            inputImageView.setImage(fxImage);
            inputImageView.setVisible(true);
            AnimationUtil.fadeIn(inputImageView, 500);
            placeholderBox.setVisible(false);
            heatmapImageView.setVisible(false);
            occlusionToggle.setSelected(false);
            if (exportReportButton != null) exportReportButton.setDisable(true);
        } catch (Exception e) {
            predictionLabel.setText("Awaiting Image...");
            if (errorBanner != null && errorLabel != null) {
                errorLabel.setText("Failed to read image file: " + e.getMessage());
                errorBanner.setVisible(true);
                errorBanner.setManaged(true);
            }
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
                predictionLabel.setText("Awaiting Image...");
                if (errorBanner != null && errorLabel != null) {
                    Throwable ex = inferTask.getException();
                    errorLabel.setText("Inference failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
                    errorBanner.setVisible(true);
                    errorBanner.setManaged(true);
                }
                loadingBox.setVisible(false);
                uploadButton.setDisable(false);
                if (inferTask.getException() != null) {
                    System.err.println("Inference error: " + inferTask.getException().getMessage());
                }
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

        AnimationUtil.fadeIn(confidenceLabel,    700);
        AnimationUtil.fadeIn(confidenceStatLabel, 800);
        AnimationUtil.fadeIn(inferenceTimeLabel,  900);

        ProgressBar[] bars = {bar0, bar1, bar2, bar3, bar4, bar5, bar6};
        Label[]       pcts = {pct0, pct1, pct2, pct3, pct4, pct5, pct6};
        Label[]       lbls = {lbl0, lbl1, lbl2, lbl3, lbl4, lbl5, lbl6};

        boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
        String defaultLabelColor = isDark ? "#9CA3AF" : "#475569";

        for (int i = 0; i < 7; i++) {
            float prob = result.allProbabilities[i];
            AnimationUtil.animateProgressBar(bars[i], prob, 900);
            pcts[i].setText(String.format("%.1f%%", prob * 100));

            if (i == result.classIndex) {
                String accentColor = (i == 4 || i == 1) ? "#EF4444" : "#10B981";
                bars[i].setStyle("-fx-accent: " + accentColor + "; -fx-pref-height: 10;");
                lbls[i].setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + accentColor + ";");
                pcts[i].setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + accentColor + ";");
                lbls[i].setText("✓  " + SkinClassifier.CLASS_FULL_NAMES[i]);
            } else {
                bars[i].setStyle("-fx-accent: #374151; -fx-pref-height: 8;");
                lbls[i].setStyle("-fx-font-size: 13px; -fx-text-fill: " + defaultLabelColor + ";");
                pcts[i].setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");
                lbls[i].setText(SkinClassifier.CLASS_FULL_NAMES[i]);
            }
        }

        RiskLevel riskLevel = classificationService.assessRisk(result);
        showRiskBanner(riskLevel);

        if (exportReportButton != null) exportReportButton.setDisable(false);
        saveToHistoryButton.setDisable(false);
        saveToHistoryButton.setText("Save to Patient History");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risk Banner
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies the appropriate visual treatment to {@code riskBanner} for each
     * risk level, starts or stops the URGENT animations, and makes the
     * banner visible.
     *
     * <p>Call {@link #stopUrgentAnimations()} before hiding the banner to avoid
     * stale animations running in the background.</p>
     */
    private void showRiskBanner(RiskLevel riskLevel) {
        if (riskBanner == null || riskLabel == null) return;

        // Always stop any previous animations before applying new styles
        stopUrgentAnimations();

        switch (riskLevel) {

            case URGENT -> {
                // ── Static base style — fixed 2px border, never animated ──────
                final String URGENT_BASE =
                        "-fx-background-color: #FEF2F2;"
                        + "-fx-background-radius: 8;"
                        + "-fx-border-color: #EF4444;"
                        + "-fx-border-width: 2;"
                        + "-fx-border-radius: 8;"
                        + "-fx-padding: 12 20;";
                final String URGENT_PEAK =
                        "-fx-background-color: #FECACA;"
                        + "-fx-background-radius: 8;"
                        + "-fx-border-color: #EF4444;"
                        + "-fx-border-width: 2;"
                        + "-fx-border-radius: 8;"
                        + "-fx-padding: 12 20;";

                riskLabel.setText("\u26a0  URGENT \u2014 Consult a dermatologist immediately");
                riskLabel.setStyle(
                        "-fx-text-fill: #991B1B;"
                        + "-fx-font-size: 14px;"
                        + "-fx-font-weight: bold;"
                );
                riskBanner.setMaxWidth(Double.MAX_VALUE);
                riskBanner.setOpacity(1.0);
                riskBanner.setStyle(URGENT_BASE);

                // ── Animation 1: background-color pulse (1.2 s loop) ─────────
                // KeyFrame events call setStyle() because JavaFX CSS properties
                // like -fx-background-color cannot be interpolated via KeyValue.
                urgentBgPulse = new Timeline(
                        new KeyFrame(Duration.ZERO,           e -> riskBanner.setStyle(URGENT_BASE)),
                        new KeyFrame(Duration.millis(600),    e -> riskBanner.setStyle(URGENT_PEAK)),
                        new KeyFrame(Duration.millis(1200),   e -> riskBanner.setStyle(URGENT_BASE))
                );
                urgentBgPulse.setCycleCount(Timeline.INDEFINITE);
                urgentBgPulse.play();

                // ── Animation 2: ⚠ icon scale pulse (0.6 s auto-reverse) ─────
                urgentIconPulse = new ScaleTransition(Duration.millis(600), riskLabel);
                urgentIconPulse.setFromX(1.0);
                urgentIconPulse.setFromY(1.0);
                urgentIconPulse.setToX(1.06);
                urgentIconPulse.setToY(1.06);
                urgentIconPulse.setAutoReverse(true);
                urgentIconPulse.setCycleCount(Animation.INDEFINITE);
                urgentIconPulse.play();
            }

            case MODERATE -> {
                // ── Static amber banner — no animation ───────────────────────
                riskLabel.setText("\u26a1  MODERATE RISK \u2014 Schedule a dermatology check-up");
                riskLabel.setStyle(
                        "-fx-text-fill: #92400E;"
                        + "-fx-font-size: 13px;"
                        + "-fx-font-weight: bold;"
                );
                riskBanner.setMaxWidth(Double.MAX_VALUE);
                riskBanner.setOpacity(1.0);
                // Reset scale in case a previous URGENT result left a mid-pulse state
                riskLabel.setScaleX(1.0);
                riskLabel.setScaleY(1.0);
                riskBanner.setStyle(
                        "-fx-background-color: #FFFBEB;"
                        + "-fx-background-radius: 8;"
                        + "-fx-border-radius: 8;"
                        + "-fx-border-color: #F59E0B;"
                        + "-fx-border-width: 1.5;"
                        + "-fx-padding: 10 16;"
                );
            }

            case LOW -> {
                // ── Quiet pill badge — no animation ──────────────────────────
                riskLabel.setText("\u2713  Low risk");
                riskLabel.setStyle(
                        "-fx-text-fill: #166534;"
                        + "-fx-font-size: 12px;"
                        + "-fx-font-weight: normal;"
                );
                riskBanner.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                riskBanner.setOpacity(1.0);
                // Reset scale in case a previous URGENT result left a mid-pulse state
                riskLabel.setScaleX(1.0);
                riskLabel.setScaleY(1.0);
                riskBanner.setStyle(
                        "-fx-background-color: #F0FDF4;"
                        + "-fx-background-radius: 20;"
                        + "-fx-border-width: 0;"
                        + "-fx-padding: 5 14;"
                );
            }
        }

        riskBanner.setVisible(true);
        riskBanner.setManaged(true);
        AnimationUtil.fadeIn(riskBanner, 400);
    }

    /**
     * Stops and discards both URGENT animations ({@link #urgentBgPulse} and
     * {@link #urgentIconPulse}). Also resets banner opacity and label scale to
     * 1.0 so no visual artefacts bleed into subsequent results.
     * Safe to call when either or both animations are null.
     */
    private void stopUrgentAnimations() {
        if (urgentBgPulse != null) {
            urgentBgPulse.stop();
            urgentBgPulse = null;
        }
        if (urgentIconPulse != null) {
            urgentIconPulse.stop();
            urgentIconPulse = null;
        }
        if (riskBanner  != null) riskBanner.setOpacity(1.0);
        if (riskLabel   != null) {
            riskLabel.setScaleX(1.0);
            riskLabel.setScaleY(1.0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Occlusion-Sensitivity Heatmap Generation
    // ─────────────────────────────────────────────────────────────────────────

    private void generateAndShowHeatmap() {
        if (currentImagePath == null || lastResult == null) return;

        saliencyOverlay.setVisible(true);
        saliencyOverlay.setManaged(true);
        saliencyProgressBar.setProgress(0);
        saliencyCountLabel.setText("Generating saliency map...");

        boolean useScore = useScoreCam;
        int totalPasses = useScore ? 25 : 49;
        saliencyPctLabel.setText("0 / " + totalPasses + (useScore ? " masks" : " patches"));

        Task<BufferedImage> heatmapTask = new Task<>() {
            @Override
            protected BufferedImage call() throws Exception {
                if (useScore) {
                    return scoreCamRenderer.generateHeatmap(
                        currentImagePath, lastResult,
                        (completed, total) ->
                            Platform.runLater(() -> {
                                saliencyProgressBar.setProgress(
                                    (double) completed / total);
                                saliencyCountLabel.setText(
                                    "Score-CAM: mask " + completed
                                    + " of " + total);
                                saliencyPctLabel.setText(
                                    completed + " / " + total + " masks");
                            })
                    );
                } else {
                    return occlusionRenderer.generateHeatmap(
                        currentImagePath, lastResult,
                        (completed, total) ->
                            Platform.runLater(() -> {
                                saliencyProgressBar.setProgress(
                                    (double) completed / total);
                                saliencyCountLabel.setText(
                                    "Occlusion: patch " + completed
                                    + " of " + total);
                                saliencyPctLabel.setText(
                                    completed + " / " + total + " patches");
                            })
                    );
                }
            }
        };

        heatmapTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                Image fxHeatmap = SwingFXUtils.toFXImage(heatmapTask.getValue(), null);
                heatmapImageView.setImage(fxHeatmap);
                heatmapImageView.setVisible(true);

                javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(400), saliencyOverlay);
                fade.setFromValue(1.0);
                fade.setToValue(0.0);
                fade.setOnFinished(ev -> {
                    saliencyOverlay.setVisible(false);
                    saliencyOverlay.setManaged(false);
                    saliencyOverlay.setOpacity(1.0);
                });
                fade.play();
            });
        });

        heatmapTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                saliencyOverlay.setVisible(false);
                saliencyOverlay.setManaged(false);
                System.err.println("Saliency map error: " + heatmapTask.getException().getMessage());
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
                File   dropped = files.get(0);
                String name    = dropped.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
                    ValidationResult vr = ImageValidator.validate(dropped);
                    if (!vr.valid()) {
                        ToastUtil.showToast(
                                (StackPane) imageContainer.getScene().getRoot(),
                                vr.title() + ": " + vr.detail(),
                                ToastUtil.ToastType.ERROR
                        );
                        event.consume();
                        return;
                    }
                    if (vr.isWarning()) {
                        ToastUtil.showToast(
                                (StackPane) imageContainer.getScene().getRoot(),
                                vr.title() + ": " + vr.detail(),
                                ToastUtil.ToastType.INFO
                        );
                        // Warning is non-fatal — fall through and load the image.
                    }
                    currentImageFile = dropped;
                    loadAndClassify(dropped.toPath());
                }
            }
            event.consume();
        });

        // Add click-to-upload for the entire drop area
        imageContainer.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                handleUpload();
            }
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

        // Fluid hover animation for the plus icon
        if (plusIcon != null) {
            plusIcon.setOnMouseEntered(e -> {
                if (SettingsManager.isAnimationsEnabled()) {
                    javafx.animation.ScaleTransition st = new javafx.animation.ScaleTransition(Duration.millis(150), plusIcon);
                    st.setToX(1.15);
                    st.setToY(1.15);
                    st.play();
                } else {
                    plusIcon.setScaleX(1.15);
                    plusIcon.setScaleY(1.15);
                }
            });
            plusIcon.setOnMouseExited(e -> {
                if (SettingsManager.isAnimationsEnabled()) {
                    javafx.animation.ScaleTransition st = new javafx.animation.ScaleTransition(Duration.millis(150), plusIcon);
                    st.setToX(1.0);
                    st.setToY(1.0);
                    st.play();
                } else {
                    plusIcon.setScaleX(1.0);
                    plusIcon.setScaleY(1.0);
                }
            });
        }
    }

    private void resetBars() {
        ProgressBar[] bars = {bar0, bar1, bar2, bar3, bar4, bar5, bar6};
        Label[]       pcts = {pct0, pct1, pct2, pct3, pct4, pct5, pct6};
        Label[]       lbls = {lbl0, lbl1, lbl2, lbl3, lbl4, lbl5, lbl6};

        boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
        String defaultLabelColor = isDark ? "#9CA3AF" : "#475569";

        for (int i = 0; i < 7; i++) {
            bars[i].setProgress(0);
            pcts[i].setText("0%");
            bars[i].setStyle("-fx-accent: #374151; -fx-pref-height: 8;");
            lbls[i].setText(SkinClassifier.CLASS_FULL_NAMES[i]);
            lbls[i].setStyle("-fx-font-size: 13px; -fx-text-fill: " + defaultLabelColor + ";");
            pcts[i].setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");
        }
    }
    /** Public method to allow HistoryController to trigger a refresh */
    public void refreshPatientDropdown() {
        loadPatientsIntoDropdown();
    }
}