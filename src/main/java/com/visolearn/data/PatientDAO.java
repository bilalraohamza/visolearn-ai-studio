package com.visolearn.data;

import com.visolearn.data.model.Patient;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for {@link Patient} entities.
 *
 * <p>Provides read and write operations against the {@code Patients} table
 * managed by {@link DatabaseUtil}. All public methods are <b>synchronous</b>;
 * callers are responsible for executing them on a background thread (e.g.,
 * inside a JavaFX {@link javafx.concurrent.Task}) to avoid blocking the
 * JavaFX Application Thread.</p>
 *
 * <h3>Usage from a JavaFX Task:</h3>
 * <pre>{@code
 * Task<List<Patient>> task = new Task<>() {
 *     protected List<Patient> call() throws Exception {
 *         return new PatientDAO().searchByName("melan");
 *     }
 * };
 * task.setOnSucceeded(e -> patientListView.setItems(
 *     FXCollections.observableList(task.getValue())));
 * new Thread(task).start();
 * }</pre>
 */
public class PatientDAO {

    /**
     * Searches for patients whose names match a partial string pattern.
     *
     * <p><b>SQL Injection Prevention:</b> The search term is passed as a
     * parameter to a {@link PreparedStatement}. The {@code LIKE} pattern
     * wraps the term with {@code %} wildcards on both sides, so
     * {@code "mel"} matches {@code "Melanoma"}, {@code "Melanie"}, etc.</p>
     *
     * <p><b>Thread Safety:</b> Each call acquires and releases its own
     * {@link Connection} via try-with-resources, so concurrent calls are safe.</p>
     *
     * @param searchStr Partial name string to match (case-insensitive).
     *                  If {@code null} or blank, returns all patients.
     * @return List of matching {@link Patient} objects (never {@code null}).
     * @throws SQLException If the query fails.
     */
    public List<Patient> searchByName(String searchStr) throws SQLException {
        List<Patient> results = new ArrayList<>();

        String sql;
        boolean hasFilter = searchStr != null && !searchStr.trim().isEmpty();

        if (hasFilter) {
            sql = "SELECT id, name, dob, gender, phone, skin_type, doctor_notes "
                + "FROM Patients WHERE name LIKE ? ORDER BY name";
        } else {
            sql = "SELECT id, name, dob, gender, phone, skin_type, doctor_notes "
                + "FROM Patients ORDER BY name";
        }

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (hasFilter) {
                // Case-insensitive partial match: wraps user input with SQL wildcards
                ps.setString(1, "%" + searchStr.trim() + "%");
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Patient(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("dob"),
                            rs.getString("gender"),
                            rs.getString("phone"),
                            rs.getString("skin_type"),
                            rs.getString("doctor_notes")
                    ));
                }
            }
        }

        return results;
    }

    /**
     * Inserts a new patient into the database with all clinical fields.
     *
     * @param name        Patient's full name (required).
     * @param dob         Date of birth in {@code YYYY-MM-DD} format (may be {@code null}).
     * @param gender      Gender / biological sex (may be {@code null}).
     * @param phone       Contact phone number (may be {@code null}).
     * @param skinType    Fitzpatrick skin phototype I–VI (may be {@code null}).
     * @param doctorNotes Free-text clinical notes (may be {@code null}).
     * @return The newly assigned SQLite rowid (returned as the {@code id} field).
     * @throws SQLException If the insert fails (e.g., unique constraint violation).
     */
    public int insert(String name, String dob,
                      String gender, String phone,
                      String skinType, String doctorNotes) throws SQLException {

        String sql = "INSERT INTO Patients (name, dob, gender, phone, skin_type, doctor_notes) "
                   + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, name);
            ps.setString(2, dob);
            ps.setString(3, gender);
            ps.setString(4, phone);
            ps.setString(5, skinType);
            ps.setString(6, doctorNotes);

            int affected = ps.executeUpdate();
            if (affected == 0) {
                throw new SQLException("INSERT failed — no rows affected.");
            }

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new SQLException("INSERT failed — no generated key returned.");
            }
        }
    }

    /**
     * Deletes a patient and all their prediction records (CASCADE).
     *
     * @param patientId The patient's primary key.
     * @throws SQLException If the delete fails.
     */
    public void deleteById(int patientId) throws SQLException {
        String sql = "DELETE FROM Patients WHERE id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, patientId);
            ps.executeUpdate();
        }
    }
}