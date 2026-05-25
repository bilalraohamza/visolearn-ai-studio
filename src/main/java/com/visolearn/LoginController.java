package com.visolearn;

import com.visolearn.data.DoctorDAO;
import com.visolearn.data.model.Doctor;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.net.URL;
import java.util.ResourceBundle;

public class LoginController implements Initializable {

    @FXML private Label         formTitleLabel;
    @FXML private VBox          fullNameBox;
    @FXML private TextField     fullNameField;
    @FXML private Label         fullNameError;
    @FXML private TextField     usernameField;
    @FXML private Label         usernameError;
    @FXML private PasswordField passwordField;
    @FXML private Label         passwordError;
    @FXML private VBox          confirmBox;
    @FXML private PasswordField confirmField;
    @FXML private Label         confirmError;
    @FXML private Label         errorBanner;
    @FXML private Button        primaryButton;
    @FXML private Label         toggleLabel;
    @FXML private Label         toggleLink;

    private final DoctorDAO doctorDAO = new DoctorDAO();
    private boolean registrationMode  = false;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        try {
            if (doctorDAO.hasNoDoctors()) {
                switchToRegistration();
            }
        } catch (Exception e) {
            showError("Database error: " + e.getMessage());
        }
        passwordField.setOnAction(e -> handlePrimary());
        confirmField.setOnAction(e -> handlePrimary());
    }

    @FXML
    private void handlePrimary() {
        clearErrors();
        if (registrationMode) {
            handleRegister();
        } else {
            handleLogin();
        }
    }

    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        boolean valid = true;

        if (username.isBlank()) {
            showFieldError(usernameError,
                "Username is required");
            valid = false;
        }
        if (password.isBlank()) {
            showFieldError(passwordError,
                "Password is required");
            valid = false;
        }
        if (!valid) return;

        primaryButton.setDisable(true);
        primaryButton.setText("Logging in...");

        Task<Doctor> task = new Task<>() {
            @Override
            protected Doctor call() throws Exception {
                return doctorDAO.login(username, password);
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            Doctor doctor = task.getValue();
            if (doctor == null) {
                showError(
                    "Incorrect username or password.");
                primaryButton.setDisable(false);
                primaryButton.setText("Login");
            } else {
                SessionManager.setCurrentDoctor(doctor);
                launchMainWindow();
            }
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            showError("Login failed: " +
                task.getException().getMessage());
            primaryButton.setDisable(false);
            primaryButton.setText("Login");
        }));

        new Thread(task, "LoginThread").start();
    }

    private void handleRegister() {
        String fullName = fullNameField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String confirm  = confirmField.getText();
        boolean valid   = true;

        if (fullName.isBlank()) {
            showFieldError(fullNameError,
                "Full name is required");
            valid = false;
        }
        if (username.length() < 3) {
            showFieldError(usernameError,
                "Username must be at least 3 characters");
            valid = false;
        }
        if (password.length() < 6) {
            showFieldError(passwordError,
                "Password must be at least 6 characters");
            valid = false;
        }
        if (!password.equals(confirm)) {
            showFieldError(confirmError,
                "Passwords do not match");
            valid = false;
        }
        if (!valid) return;

        primaryButton.setDisable(true);
        primaryButton.setText("Creating account...");

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return doctorDAO.register(
                    username, fullName, password);
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            try {
                Doctor doctor =
                    doctorDAO.login(username, password);
                if (doctor != null) {
                    SessionManager.setCurrentDoctor(
                        doctor);
                    launchMainWindow();
                }
            } catch (Exception ex) {
                showError("Auto-login failed: " +
                    ex.getMessage());
                switchToLogin();
            }
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            String msg =
                task.getException().getMessage();
            if (msg != null && msg.contains("UNIQUE")) {
                showError("Username already taken.");
            } else {
                showError("Registration failed: " + msg);
            }
            primaryButton.setDisable(false);
            primaryButton.setText("Create Account");
        }));

        new Thread(task, "RegisterThread").start();
    }

    private void launchMainWindow() {
        try {
            Stage loginStage = (Stage)
                primaryButton.getScene().getWindow();
            AppContext ctx =
                (AppContext) loginStage.getUserData();

            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/main.fxml"));
            loader.setControllerFactory(
                clazz -> MainApp.createController(
                    clazz, ctx));

            Scene scene = new Scene(
                loader.load(), 900, 600);
            MainController.applyTheme(scene,
                com.visolearn.utils.SettingsManager
                    .isDarkMode());

            Stage mainStage = new Stage();
            mainStage.setTitle(
                "VisoLearn AI Studio — Dr. " +
                SessionManager.getCurrentDoctorName());
            mainStage.setScene(scene);
            mainStage.setMinWidth(900);
            mainStage.setMinHeight(600);
            mainStage.centerOnScreen();

            mainStage.setOnCloseRequest(ev -> {
                if (ctx.getClassifierFuture().isDone()) {
                    try {
                        ctx.getClassifierFuture()
                           .get().close();
                    } catch (Exception ignored) {}
                }
                Platform.exit();
            });

            mainStage.show();
            loginStage.close();

        } catch (Exception e) {
            e.printStackTrace();
            showError("Failed to open main window: "
                + e.getMessage());
        }
    }

    @FXML
    private void handleToggleMode() {
        if (registrationMode) {
            switchToLogin();
        } else {
            switchToRegistration();
        }
    }

    private void switchToRegistration() {
        registrationMode = true;
        formTitleLabel.setText(
            "Create Doctor Account");
        primaryButton.setText("Create Account");
        toggleLabel.setText(
            "Already have an account?");
        toggleLink.setText("Login instead");
        fullNameBox.setVisible(true);
        fullNameBox.setManaged(true);
        confirmBox.setVisible(true);
        confirmBox.setManaged(true);
        clearErrors();
    }

    private void switchToLogin() {
        registrationMode = false;
        formTitleLabel.setText("Doctor Login");
        primaryButton.setText("Login");
        toggleLabel.setText("New to VisoLearn?");
        toggleLink.setText("Create account");
        fullNameBox.setVisible(false);
        fullNameBox.setManaged(false);
        confirmBox.setVisible(false);
        confirmBox.setManaged(false);
        clearErrors();
    }

    private void showError(String message) {
        errorBanner.setText(message);
        errorBanner.setVisible(true);
        errorBanner.setManaged(true);
    }

    private void showFieldError(Label lbl,
                                 String msg) {
        lbl.setText(msg);
        lbl.setVisible(true);
        lbl.setManaged(true);
    }

    private void clearErrors() {
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
        for (Label l : new Label[]{
            usernameError, passwordError,
            fullNameError, confirmError}) {
            l.setVisible(false);
            l.setManaged(false);
        }
    }
}
