package com.visolearn;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javax.imageio.ImageIO;
import javafx.scene.control.Alert;
import com.visolearn.utils.AnimationUtil;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ResourceBundle;

/**
 * ClassifyController controls Tab 1 of the VisoLearn AI Studio GUI.
 * Handles image upload, inference, confidence bar updates,
 * and Grad-CAM heatmap overlay toggle.
 *
 * Threading model:
 * All DJL inference runs on a background Task thread.
 * All UI updates run on the JavaFX Application Thread
 * via Platform.runLater() to prevent freezing.
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class ClassifyController implements Initializable {

    // ===== FXML UI Elements =====

    @FXML private StackPane imageContainer;
    @FXML private VBox      placeholderBox;
    @FXML private ImageView inputImageView;
    @FXML private ImageView heatmapImageView;
    @FXML private Button    uploadButton;
    @FXML private Button    clearButton;
    @FXML private CheckBox  gradCamToggle;
    @FXML private HBox      loadingBox;
    @FXML private Label     loadingLabel;

    // Prediction result labels
    @FXML private Label predictionLabel;
    @FXML private Label confidenceLabel;
    @FXML private Label confidenceStatLabel;
    @FXML private Label inferenceTimeLabel;
    @FXML private Label descriptionLabel;

    // Confidence bars for all 7 classes
    @FXML private ProgressBar bar0, bar1, bar2, bar3,
            bar4, bar5, bar6;

    // Confidence percentage labels
    @FXML private Label pct0, pct1, pct2, pct3,
            pct4, pct5, pct6;

    // ===== Backend components =====

    /** Skin lesion classifier using EfficientNet-B4 ONNX model. */
    private SkinClassifier classifier;

    /** Grad-CAM heatmap renderer. */
    private GradCamRenderer gradCamRenderer;

    /** Currently loaded image file path. */
    private Path currentImagePath;

    /** Latest prediction result for Grad-CAM generation. */
    private SkinClassifier.PredictionResult lastResult;

    /** Whether the Grad-CAM heatmap is currently visible. */
    private boolean heatmapVisible = false;

    /**
     * Full names for display in the UI.
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
     * Short descriptions for each skin lesion class.
     * Shown in the description box after classification.
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

    /**
     * Called automatically by JavaFX after FXML is loaded.
     * Initializes the classifier and drag-and-drop support.
     *
     * @param url      not used
     * @param rb       not used
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // GradCamRenderer constructed after classifier is ready (see initTask.setOnSucceeded)

        // Use shared classifier from MainApp
        // Avoids loading ONNX model twice
        Task<Void> initTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // Wait until shared classifier is ready
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
                gradCamRenderer = new GradCamRenderer(classifier);
                uploadButton.setDisable(false);
                predictionLabel.setText("Model ready. Upload an image.");
                System.out.println("ClassifyController: " +
                        "shared classifier connected.");
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
        predictionLabel.setText("Loading model...");

        Thread initThread = new Thread(initTask);
        initThread.setDaemon(true);
        initThread.start();

        setupDragAndDrop();
        setupAnimations();
    }

    /**
     * Handles the Upload Image button click.
     * Opens a file chooser and runs inference on the selected image.
     */
    @FXML
    private void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Skin Lesion Image");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "Image Files", "*.jpg", "*.jpeg", "*.png")
        );

        File selected = fileChooser.showOpenDialog(
                uploadButton.getScene().getWindow()
        );

        if (selected != null) {
            loadAndClassify(selected.toPath());
        }
    }

    /**
     * Handles the Clear button click.
     * Resets the UI to its initial empty state.
     */
    @FXML
    private void handleClear() {
        // Reset image views
        inputImageView.setImage(null);
        inputImageView.setVisible(false);
        heatmapImageView.setImage(null);
        heatmapImageView.setVisible(false);
        placeholderBox.setVisible(true);

        // Reset prediction labels
        predictionLabel.setText("Upload an image to classify");
        predictionLabel.setStyle(
                "-fx-font-size: 20px; -fx-font-weight: bold; " +
                        "-fx-text-fill: #e0e0e0;");
        confidenceLabel.setText("");
        inferenceTimeLabel.setText("");
        descriptionLabel.setText(
                "Upload a dermoscopy image to see classification " +
                        "results and Grad-CAM explanation.");

        // Reset confidence bars
        resetBars();

        // Reset state
        currentImagePath = null;
        lastResult       = null;
        heatmapVisible   = false;
        gradCamToggle.setSelected(false);

        predictionLabel.setOpacity(0);
        confidenceLabel.setOpacity(0);
        confidenceStatLabel.setOpacity(0);
        inferenceTimeLabel.setOpacity(0);
        descriptionLabel.setOpacity(0);
    }

    /**
     * Handles the Grad-CAM toggle checkbox.
     * Shows or hides the heatmap overlay on the image.
     */
    @FXML
    private void handleGradCamToggle() {
        if (gradCamToggle.isSelected() && lastResult != null) {
            // Generate and show heatmap
            generateAndShowHeatmap();
        } else {
            // Hide heatmap
            heatmapImageView.setVisible(false);
            heatmapVisible = false;
        }
    }

    /**
     * Loads an image from the given path, displays it in the
     * image view, and runs inference on a background thread.
     *
     * @param imagePath path to the image file
     */
    private void loadAndClassify(Path imagePath) {
        currentImagePath = imagePath;

        // Display the original image immediately
        try {
            Image fxImage = new Image(imagePath.toUri().toString());
            inputImageView.setImage(fxImage);
            inputImageView.setVisible(true);
            AnimationUtil.fadeIn(inputImageView, 500);
            placeholderBox.setVisible(false);
            heatmapImageView.setVisible(false);
            gradCamToggle.setSelected(false);
        } catch (Exception e) {
            predictionLabel.setText("Cannot load image.");
            return;
        }

        // Show loading indicator
        loadingBox.setVisible(true);
        AnimationUtil.fadeIn(loadingBox, 300);
        loadingLabel.setText("Running inference...");
        uploadButton.setDisable(true);

        // Run inference on background thread
        Task<SkinClassifier.PredictionResult> inferTask = new Task<>() {
            @Override
            protected SkinClassifier.PredictionResult call()
                    throws Exception {
                return classifier.predict(imagePath);
            }
        };

        // Update UI when inference completes
        inferTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                lastResult = inferTask.getValue();

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
                System.err.println("Inference error: " +
                        inferTask.getException().getMessage());
            });
        });

        Thread inferThread = new Thread(inferTask);
        inferThread.setDaemon(true);
        inferThread.start();
    }

    /**
     * Updates all UI elements with the prediction result.
     * Always called on the JavaFX Application Thread.
     *
     * @param result the prediction result from SkinClassifier
     */
    private void updateUIWithResult(
            SkinClassifier.PredictionResult result) {

        // Update top prediction display
        String fullName = CLASS_FULL_NAMES[result.classIndex];
        predictionLabel.setText(fullName);
        AnimationUtil.slideUp(predictionLabel, 500);
        predictionLabel.setStyle(
                "-fx-font-size: 18px; -fx-font-weight: bold; " +
                        "-fx-text-fill: #1D9E75;");

        confidenceLabel.setText(
                String.format("Confidence: %.2f%%", result.confidence));
        confidenceStatLabel.setText(
                String.format("%.1f%%", result.confidence));
        inferenceTimeLabel.setText(
                String.format("Inference time: %d ms",
                        result.inferenceTimeMs));

        // Update class description
        descriptionLabel.setText(
                CLASS_DESCRIPTIONS[result.classIndex]);

        AnimationUtil.fadeIn(confidenceLabel, 700);
        AnimationUtil.fadeIn(confidenceStatLabel, 800);
        AnimationUtil.fadeIn(inferenceTimeLabel, 900);
        AnimationUtil.fadeIn(descriptionLabel, 1000);

        // Update all 7 confidence bars
        ProgressBar[] bars = {bar0,bar1,bar2,bar3,bar4,bar5,bar6};
        Label[]       pcts = {pct0,pct1,pct2,pct3,pct4,pct5,pct6};

        for (int i = 0; i < 7; i++) {
            float prob = result.allProbabilities[i];
            AnimationUtil.animateProgressBar(
                    bars[i],
                    prob,
                    900
            );
            pcts[i].setText(String.format("%.1f%%", prob * 100));
        }
    }

    /**
     * Generates the Grad-CAM heatmap on a background thread
     * and overlays it on the input image when ready.
     */
    private void generateAndShowHeatmap() {
        if (currentImagePath == null || lastResult == null) return;

        loadingLabel.setText("Generating saliency map (0 / 49)...");
        loadingBox.setVisible(true);
        AnimationUtil.fadeIn(loadingBox, 300);

        Task<BufferedImage> heatmapTask = new Task<>() {
            @Override
            protected BufferedImage call() throws Exception {
                return gradCamRenderer.generateHeatmap(
                        currentImagePath,
                        lastResult,
                        (completed, total) -> Platform.runLater(() ->
                                loadingLabel.setText(String.format(
                                        "Saliency map... (%d / %d)",
                                        completed, total))
                        )
                );
            }
        };

        heatmapTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                BufferedImage heatmapImg = heatmapTask.getValue();
                Image fxHeatmap = SwingFXUtils.toFXImage(
                        heatmapImg, null);
                heatmapImageView.setImage(fxHeatmap);
                heatmapImageView.setVisible(true);
                AnimationUtil.fadeIn(heatmapImageView, 600);
                heatmapVisible = true;
                loadingBox.setVisible(false);
            });
        });

        heatmapTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                loadingBox.setVisible(false);
                System.err.println("Grad-CAM error: " +
                        heatmapTask.getException().getMessage());
            });
        });

        Thread heatmapThread = new Thread(heatmapTask);
        heatmapThread.setDaemon(true);
        heatmapThread.start();
    }

    /**
     * Sets up drag and drop support on the image container.
     * Users can drag image files directly onto the image panel.
     */
    private void setupDragAndDrop() {
        imageContainer.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(
                        javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });

        imageContainer.setOnDragDropped(event -> {
            var files = event.getDragboard().getFiles();
            if (!files.isEmpty()) {
                File dropped = files.get(0);
                String name  = dropped.getName().toLowerCase();
                if (name.endsWith(".jpg") ||
                        name.endsWith(".jpeg") ||
                        name.endsWith(".png")) {
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
        AnimationUtil.fadeIn(predictionLabel, 800);
    }

    /**
     * Resets all confidence bars to zero progress.
     * Called when clearing the current image.
     */
    private void resetBars() {
        ProgressBar[] bars = {bar0,bar1,bar2,bar3,bar4,bar5,bar6};
        Label[]       pcts = {pct0,pct1,pct2,pct3,pct4,pct5,pct6};
        for (int i = 0; i < 7; i++) {
            bars[i].setProgress(0);
            pcts[i].setText("0%");
        }
    }
}