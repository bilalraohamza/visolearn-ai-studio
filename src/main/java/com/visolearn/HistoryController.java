package com.visolearn;

import com.visolearn.data.DatabaseUtil;
import com.visolearn.data.PatientDAO;
import com.visolearn.data.PredictionDAO;
import com.visolearn.data.model.Patient;
import com.visolearn.data.model.Prediction;
import com.visolearn.service.ClassificationService;
import com.visolearn.service.PatientService;
import com.visolearn.utils.UITokens;
import com.visolearn.utils.AnimationUtil;
import com.visolearn.utils.ToastUtil;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import javafx.scene.chart.PieChart;

public class HistoryController implements Initializable {

    // ─────────────────────────────────────────────────────────────────────────
    // FXML-injected fields
    @FXML private SplitPane         historyRoot;
    @FXML private TextField         searchField;
    @FXML private ListView<Patient> patientListView;
    @FXML private Label             noPatientsLabel;
    @FXML private VBox              emptyStateBox;
    @FXML private javafx.scene.canvas.Canvas emptyStateCanvas;
    @FXML private Button            registerPatientButton;
    @FXML private Button            deletePatientButton;

    @FXML private Text                             selectedPatientLabel;
    @FXML private TableView<Prediction>            predictionsTable;
    @FXML private TableColumn<Prediction, String>  predictedClassColumn;
    @FXML private TableColumn<Prediction, Double>  confidenceColumn;
    @FXML private TableColumn<Prediction, String>  riskColumn;
    @FXML private TableColumn<Prediction, String>  timestampColumn;
    @FXML private TableColumn<Prediction, String>  imageColumn;
    @FXML private TableColumn<Prediction, String>  notesColumn;
    @FXML private TableColumn<Prediction, Prediction> actionsColumn;

    @FXML private PieChart distributionChart;
    @FXML private Label    totalSessionsLabel;
    @FXML private Label    avgConfidenceLabel;
    @FXML private Label    totalInferenceLabel;
    @FXML private Label    totalSessionsDescLabel;
    @FXML private Label    avgConfidenceDescLabel;
    @FXML private Label    totalInferenceDescLabel;
    @FXML private Label    patientNotesLabel;

    private final PatientService        patientService        = new PatientService(new PatientDAO());
    private final ClassificationService classificationService = new ClassificationService(new PredictionDAO());

    private Patient selectedPatient;
    private PauseTransition searchDebouncer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Register with the ControllerBus so other controllers can reach this
        // instance via ControllerBus.ifPresent(HistoryController.class, ...)
        // without holding a strong static reference.
        ControllerBus.register(this);
        setupTableColumns();
        setupSearchDebouncer();
        setupPatientListSelection();
        setupButtonHandlers();
        setupAnimations();

        // Theme-aware styles for Session Summary card labels
        boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
        updateSummaryCardStyles(isDark);
        drawEmptyStateIllustration(emptyStateCanvas);
        com.visolearn.utils.SettingsManager.darkModeProperty().addListener((obs, oldVal, newVal) -> {
            updateSummaryCardStyles(newVal);
            drawEmptyStateIllustration(emptyStateCanvas);
        });

        // Advanced cell factory for custom styling of name and DOB
        patientListView.setCellFactory(lv -> new ListCell<Patient>() {
            @Override
            protected void updateItem(Patient patient, boolean empty) {
                super.updateItem(patient, empty);
                if (empty || patient == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
                    String mutedColor = UITokens.textMuted(isDark);

                    VBox rootBox = new VBox(4);

                    // Row 1: icon + name
                    HBox topRow = new HBox(8);
                    topRow.setAlignment(Pos.CENTER_LEFT);
                    Text icon = new Text("\u25CF");
                    icon.setStyle("-fx-fill: " + UITokens.EMERALD + "; -fx-font-size: 10px;");
                    Label nameLabel = new Label(patient.name);
                    nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + UITokens.EMERALD + ";");
                    topRow.getChildren().addAll(icon, nameLabel);

                    // Row 2: DOB + Gender + Skin Type
                    HBox detailRow = new HBox(10);
                    detailRow.setAlignment(Pos.CENTER_LEFT);
                    VBox.setMargin(detailRow, new Insets(0, 0, 0, 18));

                    String dobStr = (patient.dob != null && !patient.dob.isBlank())
                            ? patient.dob : "N/A";
                    Label dobLabel = new Label("DOB: " + dobStr);
                    dobLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + mutedColor + ";");
                    detailRow.getChildren().add(dobLabel);

                    if (patient.gender != null && !patient.gender.isBlank()) {
                        Label genderLabel = new Label("| " + patient.gender);
                        genderLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + mutedColor + ";");
                        detailRow.getChildren().add(genderLabel);
                    }
                    if (patient.skinType != null && !patient.skinType.isBlank()) {
                        Label skinLabel = new Label("| Skin " + patient.skinType);
                        skinLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + mutedColor + ";");
                        detailRow.getChildren().add(skinLabel);
                    }

                    // Row 3: Phone (if available)
                    rootBox.getChildren().addAll(topRow, detailRow);
                    if (patient.phone != null && !patient.phone.isBlank()) {
                        Label phoneLabel = new Label("\u260E " + patient.phone);
                        phoneLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + mutedColor + ";");
                        VBox.setMargin(phoneLabel, new Insets(0, 0, 0, 18));
                        rootBox.getChildren().add(phoneLabel);
                    }

                    setGraphic(rootBox);
                    setText(null);
                }
            }
        });

        loadAllPatients();
        AnimationUtil.fadeIn(historyRoot, 600);
    }

    private void setupAnimations() {
        AnimationUtil.applyButtonHover(registerPatientButton);
        AnimationUtil.applyButtonHover(deletePatientButton);
    }

    private void setupTableColumns() {
        predictionsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // ── Custom placeholder: default Modena text is black (invisible on dark bg) ─
        Label noContent = new Label("No sessions found for this patient");
        noContent.setStyle("-fx-text-fill: #6B7280; -fx-font-size: 13px;");
        predictionsTable.setPlaceholder(noContent);

        predictedClassColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().predictedClass));
        predictedClassColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String className, boolean empty) {
                super.updateItem(className, empty);
                if (empty || className == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    setText(className);
                    // No explicit text-fill — let .table-cell CSS rule control color
                    // so it renders correctly in both dark (#D1D5DB) and light (#1E293B) themes
                    setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
                }
            }
        });

        confidenceColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleObjectProperty<>(data.getValue().confidence));
        confidenceColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double confidence, boolean empty) {
                super.updateItem(confidence, empty);
                if (empty || confidence == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    setText(String.format("%.2f%%", confidence));
                    setStyle(confidence >= 90
                        ? "-fx-text-fill: " + UITokens.EMERALD + "; -fx-font-size: 14px; -fx-font-weight: bold;"
                        : "-fx-text-fill: " + UITokens.AMBER   + "; -fx-font-size: 14px; -fx-font-weight: bold;");
                }
            }
        });

        timestampColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().timestamp));
        timestampColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String ts, boolean empty) {
                super.updateItem(ts, empty);
                if (empty || ts == null) {
                    setText(null);
                } else {
                    setText(ts.length() >= 10 ? ts.substring(0, 10) : ts);
                    // No explicit text-fill — CSS handles per-theme coloring
                    setStyle("-fx-font-size: 13px;");
                }
            }
        });

        imageColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().imagePath != null ? data.getValue().imagePath : ""));
        imageColumn.setCellFactory(col -> new TableCell<>() {
            private final ImageView thumbnail = new ImageView();
            {
                thumbnail.setFitWidth(60);
                thumbnail.setFitHeight(52);
                thumbnail.setPreserveRatio(true);
                thumbnail.setSmooth(true);
            }
            @Override
            protected void updateItem(String imagePath, boolean empty) {
                super.updateItem(imagePath, empty);
                if (empty || imagePath == null || imagePath.isBlank()) {
                    setGraphic(null);
                } else {
                    try {
                        Path absPath = DatabaseUtil.getImagesDir().resolve(imagePath);
                        thumbnail.setImage(absPath.toFile().exists() ? new Image(absPath.toUri().toString()) : null);
                    } catch (Exception e) {
                        thumbnail.setImage(null);
                    }
                    setGraphic(thumbnail);
                }
            }
        });

        // ── Notes column: editable per-session doctor notes ──────────────────
        notesColumn.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(
                        data.getValue().notes != null ? data.getValue().notes : ""));
        notesColumn.setCellFactory(col -> new TableCell<>() {
            private final TextField textField = new TextField();
            private boolean editing = false;

            {
                textField.setStyle("-fx-font-size: 12px;");
                textField.setPromptText("Add notes...");

                // Save on Enter key
                textField.setOnAction(ev -> commitNotesEdit());

                // Save on focus lost
                textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (!isFocused && editing) {
                        commitNotesEdit();
                    }
                });
            }

            private void commitNotesEdit() {
                editing = false;
                int rowIndex = getIndex();
                if (rowIndex < 0 || rowIndex >= getTableView().getItems().size()) return;
                Prediction pred = getTableView().getItems().get(rowIndex);
                String newNotes = textField.getText().trim();
                pred.notes = newNotes;

                // Persist to database on a background thread
                Task<Void> saveTask = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        classificationService.updateNotes(pred.id,
                                newNotes.isEmpty() ? null : newNotes);
                        return null;
                    }
                };
                new Thread(saveTask, "SaveNotes-" + pred.id).start();
            }

            @Override
            protected void updateItem(String notes, boolean empty) {
                super.updateItem(notes, empty);
                if (empty) {
                    setGraphic(null);
                    setText(null);
                } else {
                    textField.setText(notes != null ? notes : "");
                    editing = true;
                    setGraphic(textField);
                    setText(null);
                }
            }
        });

        // ── Risk Level column ────────────────────────────────────────────────
        riskColumn.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(
                        computeRisk(data.getValue().predictedClass, data.getValue().confidence)));
        riskColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String risk, boolean empty) {
                super.updateItem(risk, empty);
                if (empty || risk == null) {
                    setText(null);
                    setStyle("");
                    setGraphic(null);
                } else {
                    String color = switch (risk) {
                        case "URGENT"   -> UITokens.RED;
                        case "MODERATE" -> UITokens.AMBER;
                        default         -> UITokens.EMERALD;
                    };
                    String label  = switch (risk) {
                        case "URGENT"   -> "⚠ Urgent";
                        case "MODERATE" -> "● Moderate";
                        default         -> "✔ Low";
                    };
                    Label badge = new Label(label);
                    badge.setStyle(
                        "-fx-text-fill: " + color + ";" +
                        "-fx-font-size: 12px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-color: " + color + "22;" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 3 8 3 8;"
                    );
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        // ── Actions column ───────────────────────────────────────────────────
        actionsColumn.setCellValueFactory(data ->
                new javafx.beans.property.SimpleObjectProperty<>(data.getValue()));
        actionsColumn.setCellFactory(col -> new TableCell<>() {
            private final Button viewBtn   = makeActionBtn("\uD83D\uDC41",  UITokens.CYAN);
            private final Button deleteBtn = makeActionBtn("\uD83D\uDDD1",  UITokens.RED);
            private final Button exportBtn = makeActionBtn("\u2913",         UITokens.EMERALD);
            private final HBox   box       = new HBox(6, viewBtn, deleteBtn, exportBtn);

            {
                box.setAlignment(Pos.CENTER);

                viewBtn.setOnAction(e -> {
                    Prediction p = (Prediction) getTableRow().getItem();
                    if (p != null) openImageViewer(p);
                });

                deleteBtn.setOnAction(e -> {
                    Prediction p = (Prediction) getTableRow().getItem();
                    if (p != null) confirmAndDeletePrediction(p);
                });

                exportBtn.setOnAction(e -> {
                    Prediction p = (Prediction) getTableRow().getItem();
                    if (p != null) exportPredictionImage(p);
                });
            }

            @Override
            protected void updateItem(Prediction item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : box);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Action column helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Derives a risk level string from class name + confidence without a PredictionResult. */
    private static String computeRisk(String predictedClass, double confidence) {
        boolean isMelanomaOrBCC = "Melanoma".equalsIgnoreCase(predictedClass)
                || "Basal Cell Carcinoma".equalsIgnoreCase(predictedClass)
                || "BCC".equalsIgnoreCase(predictedClass);
        boolean isAK = "Actinic Keratosis".equalsIgnoreCase(predictedClass)
                || "AK".equalsIgnoreCase(predictedClass);
        if (isMelanomaOrBCC && confidence > 60.0) return "URGENT";
        if (isMelanomaOrBCC || (isAK && confidence > 60.0)) return "MODERATE";
        return "LOW";
    }

    /** Creates a compact square icon button for the actions cell. */
    private static Button makeActionBtn(String icon, String color) {
        Button btn = new Button(icon);
        btn.setStyle(
            "-fx-background-color: " + color + "22;" +
            "-fx-text-fill: " + color + ";" +
            "-fx-font-size: 13px;" +
            "-fx-cursor: hand;" +
            "-fx-background-radius: 6;" +
            "-fx-min-width: 32; -fx-min-height: 28;" +
            "-fx-padding: 2 6 2 6;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
            "-fx-background-color: " + color + "44;" +
            "-fx-text-fill: " + color + ";" +
            "-fx-font-size: 13px;" +
            "-fx-cursor: hand;" +
            "-fx-background-radius: 6;" +
            "-fx-min-width: 32; -fx-min-height: 28;" +
            "-fx-padding: 2 6 2 6;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
            "-fx-background-color: " + color + "22;" +
            "-fx-text-fill: " + color + ";" +
            "-fx-font-size: 13px;" +
            "-fx-cursor: hand;" +
            "-fx-background-radius: 6;" +
            "-fx-min-width: 32; -fx-min-height: 28;" +
            "-fx-padding: 2 6 2 6;"
        ));
        return btn;
    }

    /**
     * Opens a modal window showing the full-size cached scan image.
     */
    private void openImageViewer(Prediction p) {
        Path imgPath = p.getImagePath();
        if (!imgPath.toFile().exists()) {
            ToastUtil.showToast(findRootPane(), "Image file not found on disk.", ToastUtil.ToastType.ERROR);
            return;
        }
        Image img = new Image(imgPath.toUri().toString());
        ImageView iv = new ImageView(img);
        iv.setFitWidth(600);
        iv.setFitHeight(600);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        VBox root = new VBox(12);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(16));
        boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
        root.setStyle("-fx-background-color: " + UITokens.bgBase(isDark) + ";");

        Label title = new Label(p.predictedClass + "  \u00B7  " + String.format("%.2f%%", p.confidence)
                + "  \u00B7  " + (p.timestamp != null && p.timestamp.length() >= 10 ? p.timestamp.substring(0, 10) : p.timestamp));
        title.setStyle("-fx-text-fill: " + UITokens.textPrimary(isDark) + "; -fx-font-size: 14px; -fx-font-weight: bold;");

        root.getChildren().addAll(title, iv);

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Scan Viewer — " + p.predictedClass);
        stage.setScene(new Scene(root));
        stage.showAndWait();
    }

    /**
     * Confirms then deletes a single prediction row (DB record only; cached image is retained).
     */
    private void confirmAndDeletePrediction(Prediction p) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete Scan Record");
        alert.setHeaderText("Delete this scan record?");
        alert.setContentText("Predicted: " + p.predictedClass + "  (" + String.format("%.2f%%", p.confidence) + ")\n"
                + "Date: " + p.timestamp + "\n\nThis cannot be undone.");
        com.visolearn.MainController.applyThemeToDialog(alert, com.visolearn.utils.SettingsManager.isDarkMode());

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                Task<Void> task = new Task<>() {
                    @Override protected Void call() throws Exception {
                        classificationService.deletePrediction(p.id);
                        return null;
                    }
                };
                task.setOnSucceeded(e -> {
                    ToastUtil.showToast(findRootPane(), "Scan record deleted.", ToastUtil.ToastType.SUCCESS);
                    if (selectedPatient != null) loadPatientHistory(selectedPatient.id);
                });
                task.setOnFailed(e -> ToastUtil.showToast(findRootPane(), "Delete failed.", ToastUtil.ToastType.ERROR));
                new Thread(task, "DeletePrediction-" + p.id).start();
            }
        });
    }

    /**
     * Saves the cached scan image to a user-chosen location via FileChooser.
     */
    private void exportPredictionImage(Prediction p) {
        Path srcPath = p.getImagePath();
        if (!srcPath.toFile().exists()) {
            ToastUtil.showToast(findRootPane(), "Image file not found on disk.", ToastUtil.ToastType.ERROR);
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Scan Image");
        fc.setInitialFileName(p.predictedClass.replaceAll("\\s+", "_") + "_scan.png");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PNG Image", "*.png"),
                new FileChooser.ExtensionFilter("JPEG Image", "*.jpg")
        );
        File dest = fc.showSaveDialog(historyRoot.getScene().getWindow());
        if (dest == null) return;

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                // If the user chose PNG but the cached file is JPEG (or vice versa),
                // re-encode via ImageIO so the format always matches the chosen extension.
                String ext = dest.getName().toLowerCase().endsWith(".jpg") ? "jpg" : "png";
                BufferedImage bi = ImageIO.read(srcPath.toFile());
                if (bi == null) throw new IOException("Cannot decode cached image.");
                ImageIO.write(bi, ext, dest);
                return null;
            }
        };
        task.setOnSucceeded(e -> ToastUtil.showToast(findRootPane(), "Image exported: " + dest.getName(), ToastUtil.ToastType.SUCCESS));
        task.setOnFailed(e -> ToastUtil.showToast(findRootPane(), "Export failed: " + task.getException().getMessage(), ToastUtil.ToastType.ERROR));
        new Thread(task, "ExportImage-" + p.id).start();
    }

    private void setupSearchDebouncer() {
        searchDebouncer = new PauseTransition(Duration.millis(300));
        searchDebouncer.setOnFinished(e -> searchPatients(searchField.getText()));
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            searchDebouncer.stop();
            searchDebouncer.playFromStart();
        });
    }

    private void setupPatientListSelection() {
        patientListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldPatient, newPatient) -> {
                    if (newPatient != null) {
                        selectedPatient = newPatient;
                        selectedPatientLabel.setText("- " + newPatient.name);
                        deletePatientButton.setVisible(true);
                        loadPatientHistory(newPatient.id);

                        // Show patient's registration notes in the summary panel
                        if (patientNotesLabel != null) {
                            String dn = newPatient.doctorNotes;
                            patientNotesLabel.setText(
                                    (dn != null && !dn.isBlank()) ? dn : "No notes available.");
                        }
                    } else {
                        selectedPatient = null;
                        selectedPatientLabel.setText("-");
                        deletePatientButton.setVisible(false);
                        predictionsTable.getItems().clear();
                        distributionChart.getData().clear();
                        totalSessionsLabel.setText("0");
                        avgConfidenceLabel.setText("0%");
                        totalInferenceLabel.setText("0 ms");
                        if (patientNotesLabel != null) {
                            patientNotesLabel.setText("No notes available.");
                        }
                    }
                });
    }

    private void setupButtonHandlers() {
        registerPatientButton.setOnAction(e -> handleRegisterPatient());
        deletePatientButton.setOnAction(e -> deleteSelectedPatient());
    }

    private void deleteSelectedPatient() {
        if (selectedPatient == null) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete Patient");
        alert.setHeaderText("Delete " + selectedPatient.name + "?");
        alert.setContentText("This will permanently delete the patient and all associated scan history. This action cannot be undone.");

        com.visolearn.MainController.applyThemeToDialog(alert, com.visolearn.utils.SettingsManager.isDarkMode());

        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                Task<Void> deleteTask = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        patientService.deletePatient(selectedPatient.id);
                        return null;
                    }
                };

                deleteTask.setOnSucceeded(e -> {
                    ToastUtil.showToast(findRootPane(), "Patient deleted successfully.", ToastUtil.ToastType.SUCCESS);
                    searchPatients(searchField.getText());
                    // Refresh the patient dropdown in the Classify tab.
                    // ControllerBus.ifPresent() is null-safe and always dispatches on the FX thread.
                    ControllerBus.ifPresent(ClassifyController.class, ClassifyController::refreshPatientDropdown);
                });

                deleteTask.setOnFailed(e -> {
                    ToastUtil.showToast(findRootPane(), "Failed to delete patient.", ToastUtil.ToastType.ERROR);
                });

                new Thread(deleteTask).start();
            }
        });
    }

    private void loadAllPatients() {
        Task<List<Patient>> task = new Task<>() {
            @Override protected List<Patient> call() throws Exception { return patientService.getAllPatients(); }
        };
        task.setOnSucceeded(e -> {
            List<Patient> patients = task.getValue();
            patientListView.setItems(FXCollections.observableList(patients));
            if (patients.isEmpty()) {
                emptyStateBox.setVisible(true);
                emptyStateBox.setManaged(true);
                patientListView.setVisible(false);
                patientListView.setManaged(false);
                noPatientsLabel.setVisible(false);
                noPatientsLabel.setManaged(false);
            } else {
                emptyStateBox.setVisible(false);
                emptyStateBox.setManaged(false);
                patientListView.setVisible(true);
                patientListView.setManaged(true);
                noPatientsLabel.setVisible(false);
                noPatientsLabel.setManaged(false);
            }
        });
        new Thread(task, "PatientSearch-init").start();
    }

    private void searchPatients(String searchStr) {
        Task<List<Patient>> task = new Task<>() {
            @Override protected List<Patient> call() throws Exception { return patientService.searchPatients(searchStr); }
        };
        task.setOnSucceeded(e -> {
            List<Patient> results = task.getValue();
            patientListView.setItems(FXCollections.observableList(results));
            emptyStateBox.setVisible(false);
            emptyStateBox.setManaged(false);
            noPatientsLabel.setVisible(results.isEmpty());
            noPatientsLabel.setManaged(results.isEmpty());
            patientListView.setVisible(!results.isEmpty());
            patientListView.setManaged(!results.isEmpty());
        });
        new Thread(task, "PatientSearch-" + Thread.currentThread().getId()).start();
    }

    private void loadPatientHistory(int patientId) {
        Task<List<Prediction>> task = new Task<>() {
            @Override protected List<Prediction> call() throws Exception { return classificationService.getPredictionsForPatient(patientId); }
        };

        task.setOnSucceeded(e -> {
            List<Prediction> predictions = task.getValue();
            predictionsTable.setItems(FXCollections.observableList(predictions));

            if (!predictions.isEmpty()) {
                double totalConf  = predictions.stream().mapToDouble(p -> p.confidence).sum();
                int    totalTime  = predictions.stream().mapToInt(p -> p.inferenceTime).sum();
                int    totalCount = predictions.size();

                totalSessionsLabel.setText(String.valueOf(totalCount));
                avgConfidenceLabel.setText(String.format("%.1f%%", totalConf / totalCount));
                totalInferenceLabel.setText(totalTime < 1000 ? totalTime + " ms" : String.format("%.2f s", totalTime / 1000.0));
            } else {
                totalSessionsLabel.setText("0");
                avgConfidenceLabel.setText("0%");
                totalInferenceLabel.setText("0 ms");
            }
            buildDistributionChart(predictions);
        });
        new Thread(task, "History-load-" + patientId).start();
    }

    private void buildDistributionChart(List<Prediction> predictions) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (Prediction p : predictions) {
            distribution.merge(p.predictedClass, 1L, Long::sum);
        }
        List<PieChart.Data> chartData = new ArrayList<>();
        distribution.forEach((className, count) -> chartData.add(new PieChart.Data(className + " (" + count + ")", count)));

        distributionChart.getData().clear();
        distributionChart.getData().addAll(chartData);
    }

    public void refreshCurrentPatient() {
        if (selectedPatient != null) {
            loadPatientHistory(selectedPatient.id);
        }
    }

    private boolean validateField(TextField field, Label errorLabel, String errorMessage, boolean condition) {
        if (!condition) {
            field.setStyle("-fx-border-color: #EF4444; -fx-border-width: 1.5; -fx-border-radius: 6;");
            errorLabel.setText(errorMessage);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
            return false;
        } else {
            field.setStyle("-fx-border-color: #10B981; -fx-border-width: 1.5; -fx-border-radius: 6;");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            return true;
        }
    }

    private boolean validateName(TextField nameField, Label nameError) {
        String text = nameField.getText().trim();
        return validateField(nameField, nameError, "Name is required (minimum 2 characters)", text.length() >= 2);
    }

    private boolean validateDob(TextField dobField, Label dobError) {
        String text = dobField.getText().trim();
        if (text.isEmpty()) {
            dobField.setStyle("");
            dobError.setVisible(false);
            dobError.setManaged(false);
            return true;
        }
        boolean matches = text.matches("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$");
        if (!matches) {
            return validateField(dobField, dobError, "Enter a valid date (YYYY-MM-DD, not in future)", false);
        }
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(text);
            if (date.isAfter(java.time.LocalDate.now())) {
                return validateField(dobField, dobError, "Enter a valid date (YYYY-MM-DD, not in future)", false);
            }
        } catch (Exception e) {
            return validateField(dobField, dobError, "Enter a valid date (YYYY-MM-DD, not in future)", false);
        }
        return validateField(dobField, dobError, "", true);
    }

    private boolean validatePhone(TextField phoneField, Label phoneError) {
        String text = phoneField.getText().trim();
        if (text.isEmpty()) {
            phoneField.setStyle("");
            phoneError.setVisible(false);
            phoneError.setManaged(false);
            return true;
        }
        boolean matches = text.matches("^\\+?[\\d\\s\\-]{7,15}$");
        return validateField(phoneField, phoneError, "Enter a valid phone number", matches);
    }

    @FXML
    private void handleRegisterPatient() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Register New Patient");
        dialog.setHeaderText("Enter patient details below.");

        com.visolearn.MainController.applyThemeToDialog(dialog, com.visolearn.utils.SettingsManager.isDarkMode());

        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 10, 10, 10));

        Label nameError = new Label();
        nameError.setVisible(false); nameError.setManaged(false);
        nameError.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 11px;");

        Label dobError = new Label();
        dobError.setVisible(false); dobError.setManaged(false);
        dobError.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 11px;");

        Label phoneError = new Label();
        phoneError.setVisible(false); phoneError.setManaged(false);
        phoneError.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 11px;");

        // ── Row 0: Full Name (required) ──────────────────────────────────
        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Ahmed Khan");
        nameField.setPrefWidth(300);
        nameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) validateName(nameField, nameError);
        });

        HBox nameLabelBox = new HBox(4);
        Label nameLabelStr = new Label("Name");
        Label nameAsterisk = new Label("*");
        nameAsterisk.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 12px;");
        nameLabelBox.getChildren().addAll(nameLabelStr, nameAsterisk);

        VBox nameBox = new VBox(2, nameField, nameError);
        grid.add(nameLabelBox, 0, 0);
        grid.add(nameBox, 1, 0);

        // ── Row 1: Date of Birth ─────────────────────────────────────────
        TextField dobField = new TextField();
        dobField.setPromptText("YYYY-MM-DD");
        dobField.setPrefWidth(300);
        dobField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) validateDob(dobField, dobError);
        });

        VBox dobBox = new VBox(2, dobField, dobError);
        grid.add(new Label("Date of Birth:"), 0, 1);
        grid.add(dobBox, 1, 1);

        // ── Row 2: Gender ────────────────────────────────────────────────
        ComboBox<String> genderCombo = new ComboBox<>();
        genderCombo.getItems().addAll("Male", "Female", "Non-binary", "Other", "Prefer not to say");
        genderCombo.setPromptText("Select gender");
        genderCombo.setPrefWidth(300);
        grid.add(new Label("Gender:"), 0, 2);
        grid.add(genderCombo, 1, 2);

        // ── Row 3: Phone ─────────────────────────────────────────────────
        TextField phoneField = new TextField();
        phoneField.setPromptText("+92 300 0000000");
        phoneField.setPrefWidth(300);
        phoneField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) validatePhone(phoneField, phoneError);
        });

        VBox phoneBox = new VBox(2, phoneField, phoneError);
        grid.add(new Label("Phone:"), 0, 3);
        grid.add(phoneBox, 1, 3);

        // ── Row 4: Fitzpatrick Skin Type ─────────────────────────────────
        ComboBox<String> skinTypeCombo = new ComboBox<>();
        skinTypeCombo.getItems().addAll(
                "Type I  \u2014 Very fair, always burns",
                "Type II \u2014 Fair, burns easily",
                "Type III \u2014 Medium, sometimes burns",
                "Type IV \u2014 Olive, rarely burns",
                "Type V  \u2014 Brown, very rarely burns",
                "Type VI \u2014 Dark brown/black, never burns"
        );
        skinTypeCombo.setPromptText("Select Fitzpatrick phototype");
        skinTypeCombo.setPrefWidth(300);
        grid.add(new Label("Skin Type:"), 0, 4);
        grid.add(skinTypeCombo, 1, 4);

        // ── Row 5: Doctor Notes ──────────────────────────────────────────
        TextArea notesArea = new TextArea();
        notesArea.setPromptText("Medical history, allergies, ongoing treatments...");
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);
        notesArea.setPrefWidth(300);
        grid.add(new Label("Doctor Notes:"), 0, 5);
        grid.add(notesArea, 1, 5);

        pane.setContent(grid);

        final Button okButton = (Button) pane.lookupButton(ButtonType.OK);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            boolean validName = validateName(nameField, nameError);
            boolean validDob = validateDob(dobField, dobError);
            boolean validPhone = validatePhone(phoneField, phoneError);

            if (!validName || !validDob || !validPhone) {
                event.consume(); // Prevent dialog from closing
                
                if (!validName) nameField.requestFocus();
                else if (!validDob) dobField.requestFocus();
                else if (!validPhone) phoneField.requestFocus();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        String name       = nameField.getText().trim();
        String dob        = dobField.getText().trim();
        String gender     = genderCombo.getValue();
        String phone      = phoneField.getText().trim();
        String skinType   = skinTypeCombo.getValue();
        String doctorNotes = notesArea.getText().trim();

        // Extract just the type label (e.g. "Type III") from the full description
        if (skinType != null && skinType.contains("\u2014")) {
            skinType = skinType.substring(0, skinType.indexOf("\u2014")).trim();
        }

        final String finalSkinType = skinType;
        Task<Patient> task = new Task<>() {
            @Override
            protected Patient call() throws Exception {
                return patientService.registerPatient(
                        name,
                        dob.isEmpty() ? null : dob,
                        gender,
                        phone.isEmpty() ? null : phone,
                        finalSkinType,
                        doctorNotes.isEmpty() ? null : doctorNotes
                );
            }
        };

        task.setOnSucceeded(e -> {
            ToastUtil.showToast(findRootPane(), "Patient registered successfully.", ToastUtil.ToastType.SUCCESS);
            loadAllPatients();
            // Refresh the patient dropdown in the Classify tab.
            ControllerBus.ifPresent(ClassifyController.class, ClassifyController::refreshPatientDropdown);
        });

        new Thread(task, "PatientRegister").start();
    }

    private StackPane findRootPane() {
        Window w = historyRoot.getScene().getWindow();
        if (w.getScene().getRoot() instanceof StackPane sp) {
            return sp;
        }
        throw new IllegalStateException("Scene root must be a StackPane.");
    }

    private void drawEmptyStateIllustration(javafx.scene.canvas.Canvas canvas) {
        if (canvas == null) return;
        javafx.scene.canvas.GraphicsContext gc = canvas.getGraphicsContext2D();
        boolean isDark = com.visolearn.utils.SettingsManager.isDarkMode();
        String strokeColor = isDark ? "#374151" : "#CBD5E1";
        String accentColor = "#10B981";

        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.save();
        gc.scale(1.5, 1.5);

        gc.setStroke(javafx.scene.paint.Color.web(strokeColor));
        gc.setLineWidth(2);
        
        // Card outline
        gc.strokeRoundRect(10, 10, 100, 70, 12, 12);

        // Avatar circle top center
        gc.setStroke(javafx.scene.paint.Color.web(accentColor));
        gc.strokeOval(42, 20, 36, 36);

        // Name lines
        gc.setStroke(javafx.scene.paint.Color.web(strokeColor));
        gc.setLineWidth(2);
        gc.strokeLine(30, 62, 90, 62);
        gc.strokeLine(40, 70, 80, 70);

        // small "+" circle in bottom-right corner
        gc.setFill(javafx.scene.paint.Color.web(accentColor));
        gc.fillOval(90, 60, 20, 20);
        
        // white + inside
        gc.setStroke(javafx.scene.paint.Color.WHITE);
        gc.setLineWidth(2);
        gc.strokeLine(100, 65, 100, 75);
        gc.strokeLine(95, 70, 105, 70);

        gc.restore();
    }

    private void updateSummaryCardStyles(boolean isDark) {
        if (totalSessionsLabel != null) {
            totalSessionsLabel.setStyle(isDark 
                ? "-fx-text-fill: #F8F9FA; -fx-font-size: 18px; -fx-font-weight: bold;" 
                : "-fx-text-fill: #0F172A; -fx-font-size: 18px; -fx-font-weight: bold;");
        }
        if (avgConfidenceLabel != null) {
            avgConfidenceLabel.setStyle(isDark 
                ? "-fx-text-fill: #F8F9FA; -fx-font-size: 18px; -fx-font-weight: bold;" 
                : "-fx-text-fill: #0F172A; -fx-font-size: 18px; -fx-font-weight: bold;");
        }
        if (totalInferenceLabel != null) {
            totalInferenceLabel.setStyle(isDark 
                ? "-fx-text-fill: #F8F9FA; -fx-font-size: 18px; -fx-font-weight: bold;" 
                : "-fx-text-fill: #0F172A; -fx-font-size: 18px; -fx-font-weight: bold;");
        }
        if (totalSessionsDescLabel != null) {
            totalSessionsDescLabel.setStyle(isDark 
                ? "-fx-text-fill: #9CA3AF; -fx-font-size: 12px;" 
                : "-fx-text-fill: #64748B; -fx-font-size: 12px;");
        }
        if (avgConfidenceDescLabel != null) {
            avgConfidenceDescLabel.setStyle(isDark 
                ? "-fx-text-fill: #9CA3AF; -fx-font-size: 12px;" 
                : "-fx-text-fill: #64748B; -fx-font-size: 12px;");
        }
        if (totalInferenceDescLabel != null) {
            totalInferenceDescLabel.setStyle(isDark 
                ? "-fx-text-fill: #9CA3AF; -fx-font-size: 12px;" 
                : "-fx-text-fill: #64748B; -fx-font-size: 12px;");
        }
    }
}