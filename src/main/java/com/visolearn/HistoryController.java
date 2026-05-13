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

    @FXML private PieChart distributionChart;
    @FXML private Label    totalSessionsLabel;
    @FXML private Label    avgConfidenceLabel;
    @FXML private Label    totalInferenceLabel;

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
                    String dobColor = isDark ? "#94A3B8" : "#475569";

                    VBox rootBox = new VBox(4);

                    HBox topRow = new HBox(8);
                    topRow.setAlignment(Pos.CENTER_LEFT);

                    Text icon = new Text("👤");
                    icon.setStyle("-fx-fill: " + dobColor + "; -fx-font-size: 14px;");

                    Label nameLabel = new Label(patient.name);
                    nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #10B981;");

                    topRow.getChildren().addAll(icon, nameLabel);

                    Label dobLabel = new Label("DOB: " + (patient.dob != null ? patient.dob : "N/A"));
                    dobLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + dobColor + ";");
                    VBox.setMargin(dobLabel, new Insets(0, 0, 0, 22));

                    rootBox.getChildren().addAll(topRow, dobLabel);
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
                    } else {
                        selectedPatient = null;
                        selectedPatientLabel.setText("-");
                        deletePatientButton.setVisible(false);
                        predictionsTable.getItems().clear();
                        distributionChart.getData().clear();
                        totalSessionsLabel.setText("0");
                        avgConfidenceLabel.setText("0%");
                        totalInferenceLabel.setText("0 ms");
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

        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 10, 10, 10));

        TextField nameField = new TextField();
        nameField.setPromptText("Full Name");
        nameField.setPrefWidth(280);

        TextField dobField = new TextField();
        dobField.setPromptText("Date of Birth (YYYY-MM-DD)");
        dobField.setPrefWidth(280);

        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("DOB:"), 0, 1);
        grid.add(dobField, 1, 1);

        pane.setContent(grid);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        String name = nameField.getText().trim();
        String dob  = dobField.getText().trim();

        if (name.isEmpty()) {
            ToastUtil.showToast(findRootPane(), "Patient name is required.", ToastUtil.ToastType.ERROR);
            return;
        }

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception { return new PatientDAO().insert(name, dob.isEmpty() ? null : dob); }
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