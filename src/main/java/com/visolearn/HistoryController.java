package com.visolearn;

import com.visolearn.data.DatabaseUtil;
import com.visolearn.data.PatientDAO;
import com.visolearn.data.PredictionDAO;
import com.visolearn.data.model.Patient;
import com.visolearn.data.model.Prediction;
import com.visolearn.utils.AnimationUtil;
import com.visolearn.utils.ToastUtil;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Window;
import javafx.util.Duration;

import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import javafx.scene.chart.PieChart;

public class HistoryController implements Initializable {

    private static HistoryController instance;
    public static HistoryController getInstance() { return instance; }

    @FXML private SplitPane         historyRoot;
    @FXML private TextField         searchField;
    @FXML private ListView<Patient> patientListView;
    @FXML private Label             noPatientsLabel;
    @FXML private Button            registerPatientButton;
    @FXML private Button            deletePatientButton;

    @FXML private Text                             selectedPatientLabel;
    @FXML private TableView<Prediction>            predictionsTable;
    @FXML private TableColumn<Prediction, String>  predictedClassColumn;
    @FXML private TableColumn<Prediction, Double>  confidenceColumn;
    @FXML private TableColumn<Prediction, String>  timestampColumn;
    @FXML private TableColumn<Prediction, String>  imageColumn;
    @FXML private TableColumn<Prediction, String>  notesColumn;

    @FXML private PieChart distributionChart;
    @FXML private Label    totalSessionsLabel;
    @FXML private Label    avgConfidenceLabel;
    @FXML private Label    totalInferenceLabel;
    @FXML private Label    patientNotesLabel;

    private final PatientDAO    patientDAO    = new PatientDAO();
    private final PredictionDAO predictionDAO = new PredictionDAO();

    private Patient selectedPatient;
    private PauseTransition searchDebouncer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        instance = this;
        setupTableColumns();
        setupSearchDebouncer();
        setupPatientListSelection();
        setupButtonHandlers();
        setupAnimations();

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
                    String mutedColor = isDark ? "#94A3B8" : "#475569";

                    VBox rootBox = new VBox(4);

                    // Row 1: icon + name
                    HBox topRow = new HBox(8);
                    topRow.setAlignment(Pos.CENTER_LEFT);
                    Text icon = new Text("\u25CF");
                    icon.setStyle("-fx-fill: #10B981; -fx-font-size: 10px;");
                    Label nameLabel = new Label(patient.name);
                    nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #10B981;");
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

    @SuppressWarnings("deprecation")
    private void setupTableColumns() {
        predictionsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

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
                    setStyle(confidence >= 90 ? "-fx-text-fill: #10B981; -fx-font-size: 14px; -fx-font-weight: bold;"
                            : "-fx-text-fill: #F59E0B; -fx-font-size: 14px; -fx-font-weight: bold;");
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
                        predictionDAO.updateNotes(pred.id, newNotes.isEmpty() ? null : newNotes);
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
        registerPatientButton.setOnAction(e -> showRegisterPatientDialog());
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
                        patientDAO.deleteById(selectedPatient.id);
                        return null;
                    }
                };

                deleteTask.setOnSucceeded(e -> {
                    ToastUtil.showToast(findRootPane(), "Patient deleted successfully.", ToastUtil.ToastType.SUCCESS);
                    searchPatients(searchField.getText());

                    javafx.application.Platform.runLater(() -> {
                        ClassifyController cc = ClassifyController.getInstance();
                        if (cc != null) cc.refreshPatientDropdown();
                    });
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
            @Override protected List<Patient> call() throws Exception { return patientDAO.searchByName(null); }
        };
        task.setOnSucceeded(e -> {
            List<Patient> patients = task.getValue();
            patientListView.setItems(FXCollections.observableList(patients));
            noPatientsLabel.setVisible(patients.isEmpty());
        });
        new Thread(task, "PatientSearch-init").start();
    }

    private void searchPatients(String searchStr) {
        Task<List<Patient>> task = new Task<>() {
            @Override protected List<Patient> call() throws Exception { return patientDAO.searchByName(searchStr); }
        };
        task.setOnSucceeded(e -> {
            List<Patient> results = task.getValue();
            patientListView.setItems(FXCollections.observableList(results));
            noPatientsLabel.setVisible(results.isEmpty());
        });
        new Thread(task, "PatientSearch-" + Thread.currentThread().getId()).start();
    }

    private void loadPatientHistory(int patientId) {
        Task<List<Prediction>> task = new Task<>() {
            @Override protected List<Prediction> call() throws Exception { return predictionDAO.getPredictionsByPatientId(patientId); }
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

    private void showRegisterPatientDialog() {
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

        // ── Row 0: Full Name (required) ──────────────────────────────────
        TextField nameField = new TextField();
        nameField.setPromptText("Full Name");
        nameField.setPrefWidth(300);
        grid.add(new Label("Name *:"), 0, 0);
        grid.add(nameField, 1, 0);

        // ── Row 1: Date of Birth ─────────────────────────────────────────
        TextField dobField = new TextField();
        dobField.setPromptText("YYYY-MM-DD");
        dobField.setPrefWidth(300);
        grid.add(new Label("Date of Birth:"), 0, 1);
        grid.add(dobField, 1, 1);

        // ── Row 2: Gender ────────────────────────────────────────────────
        ComboBox<String> genderCombo = new ComboBox<>();
        genderCombo.getItems().addAll("Male", "Female", "Non-binary", "Other", "Prefer not to say");
        genderCombo.setPromptText("Select gender");
        genderCombo.setPrefWidth(300);
        grid.add(new Label("Gender:"), 0, 2);
        grid.add(genderCombo, 1, 2);

        // ── Row 3: Phone ─────────────────────────────────────────────────
        TextField phoneField = new TextField();
        phoneField.setPromptText("+92-XXX-XXXXXXX");
        phoneField.setPrefWidth(300);
        grid.add(new Label("Phone:"), 0, 3);
        grid.add(phoneField, 1, 3);

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

        if (name.isEmpty()) {
            ToastUtil.showToast(findRootPane(), "Patient name is required.", ToastUtil.ToastType.ERROR);
            return;
        }

        final String finalSkinType = skinType;
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return new PatientDAO().insert(
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
            searchPatients(searchField.getText());
            javafx.application.Platform.runLater(() -> {
                ClassifyController cc = ClassifyController.getInstance();
                if (cc != null) cc.refreshPatientDropdown();
            });
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
}