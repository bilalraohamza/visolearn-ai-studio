package com.visolearn.service;

import com.visolearn.data.PatientDAO;
import com.visolearn.data.model.Patient;

import java.sql.SQLException;
import java.util.List;

/**
 * Business-logic layer for patient management operations.
 *
 * <p>This service wraps {@link PatientDAO} and provides named business-level
 * methods so that JavaFX controllers contain no DAO imports and no data-access
 * logic. All methods are synchronous; callers are responsible for dispatching
 * them on a background thread (e.g., inside a JavaFX {@code Task}) to avoid
 * blocking the Application Thread.</p>
 *
 * <h3>Design rationale</h3>
 * <ul>
 *   <li>Constructor injection of {@link PatientDAO} makes this class
 *       independently unit-testable without a live database — pass a stub
 *       or subclass in tests.</li>
 *   <li>Controllers use {@code PatientService} exclusively; they never import
 *       {@code PatientDAO} or {@code DatabaseUtil} directly.</li>
 * </ul>
 *
 * @see PatientDAO
 * @see ClassificationService
 */
public class PatientService {

    private final PatientDAO dao;

    /**
     * Creates a {@code PatientService} backed by the given DAO.
     *
     * @param dao The data-access object to delegate database calls to.
     *            Must not be {@code null}.
     */
    public PatientService(PatientDAO dao) {
        if (dao == null) throw new IllegalArgumentException("PatientDAO must not be null");
        this.dao = dao;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all registered patients, ordered alphabetically by name.
     *
     * <p>Used to populate dropdowns (Classify tab) and the patient list
     * (History tab) on startup and after mutations.</p>
     *
     * @return All patients (never {@code null}, may be empty).
     * @throws SQLException if the database query fails.
     */
    public List<Patient> getAllPatients() throws SQLException {
        return dao.searchByName(null);
    }

    /**
     * Returns patients whose names contain {@code query} (case-insensitive,
     * partial match). If {@code query} is blank or {@code null}, returns all
     * patients — identical behaviour to {@link #getAllPatients()}.
     *
     * <p>Used by the live-search field in the History tab.</p>
     *
     * @param query Partial name to search for. May be {@code null} or blank.
     * @return Matching patients ordered by name (never {@code null}).
     * @throws SQLException if the database query fails.
     */
    public List<Patient> searchPatients(String query) throws SQLException {
        return dao.searchByName(query);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers a new patient and returns the fully-constructed {@link Patient}
     * record (including the database-assigned {@code id}).
     *
     * <p>{@code name} is the only required field; all other parameters may be
     * {@code null} or blank.</p>
     *
     * @param name        Patient's full name (required; must not be blank).
     * @param dob         Date of birth in {@code YYYY-MM-DD} format, or {@code null}.
     * @param gender      Gender / biological sex, or {@code null}.
     * @param phone       Contact phone number, or {@code null}.
     * @param skinType    Fitzpatrick skin phototype (e.g. {@code "Type III"}), or {@code null}.
     * @param doctorNotes Free-text clinical notes, or {@code null}.
     * @return The newly created {@link Patient} with its assigned database id.
     * @throws IllegalArgumentException if {@code name} is blank.
     * @throws SQLException             if the database insert fails.
     */
    public Patient registerPatient(String name,
                                   String dob,
                                   String gender,
                                   String phone,
                                   String skinType,
                                   String doctorNotes) throws SQLException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Patient name must not be blank.");
        }

        // Normalise empty strings to null for nullable columns
        String dobVal         = blankToNull(dob);
        String phoneVal       = blankToNull(phone);
        String doctorNotesVal = blankToNull(doctorNotes);
        // skinType and gender are already validated/selected from controlled
        // combo boxes in the UI, so we preserve them as-is.

        int newId = dao.insert(name, dobVal, gender, phoneVal, skinType, doctorNotesVal);
        return new Patient(newId, name, dobVal, gender, phoneVal, skinType, doctorNotesVal);
    }

    /**
     * Deletes a patient and all their associated prediction records (via
     * database CASCADE constraint).
     *
     * @param patientId The patient's primary key.
     * @throws SQLException if the database delete fails.
     */
    public void deletePatient(int patientId) throws SQLException {
        dao.deleteById(patientId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
