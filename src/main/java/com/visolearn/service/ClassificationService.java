package com.visolearn.service;

import com.visolearn.SkinClassifier;
import com.visolearn.data.PredictionDAO;
import com.visolearn.data.model.Prediction;
import com.visolearn.utils.RiskAssessor;
import com.visolearn.utils.RiskLevel;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

/**
 * Business-logic layer for skin-lesion classification operations.
 *
 * <p>This service encapsulates two concerns that formerly lived inside
 * {@code ClassifyController} and {@code HistoryController}:</p>
 * <ol>
 *   <li><b>Persistence</b> — building a {@link Prediction} record and calling
 *       {@link PredictionDAO#insertPrediction} / {@link PredictionDAO#deleteById} /
 *       {@link PredictionDAO#updateNotes}.</li>
 *   <li><b>Risk assessment dispatch</b> — delegating to {@link RiskAssessor}
 *       so the risk-level logic is not duplicated across controllers.</li>
 * </ol>
 *
 * <h3>Design rationale</h3>
 * <ul>
 *   <li>Constructor injection of {@link PredictionDAO} allows the service to be
 *       unit tested without a live database — pass a stub in tests.</li>
 *   <li>Controllers use {@code ClassificationService} exclusively; they never
 *       import {@code PredictionDAO} or {@code RiskAssessor} directly.</li>
 * </ul>
 *
 * @see PatientService
 * @see RiskAssessor
 */
public class ClassificationService {

    private final PredictionDAO predictionDAO;

    /**
     * Creates a {@code ClassificationService} backed by the given DAO.
     *
     * @param predictionDAO The data-access object to delegate database calls to.
     *                      Must not be {@code null}.
     */
    public ClassificationService(PredictionDAO predictionDAO) {
        if (predictionDAO == null)
            throw new IllegalArgumentException("PredictionDAO must not be null");
        this.predictionDAO = predictionDAO;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persists one classification result for a patient.
     *
     * <p>Constructs the {@link Prediction} domain object, delegates image
     * caching and the database write to {@link PredictionDAO#insertPrediction},
     * and returns the newly assigned record id.</p>
     *
     * <p><b>Threading:</b> This method performs disk I/O and a database write.
     * It <b>must</b> be called from a background thread (e.g., inside a JavaFX
     * {@code Task}) to avoid blocking the Application Thread.</p>
     *
     * @param patientId       The patient's primary key.
     * @param predictedClass  Human-readable class label (e.g. {@code "Melanoma"}).
     * @param confidence      Confidence expressed as a percentage (0–100).
     * @param inferenceTimeMs Wall-clock inference duration in milliseconds.
     * @param imageFile       The source image {@link File} to cache alongside the record.
     * @param clinicianNotes  Free-text notes typed by the clinician; may be {@code null}.
     * @return The newly assigned SQLite rowid of the inserted record.
     * @throws SQLException       if the database write fails.
     * @throws java.io.IOException if the image copy fails.
     */
    public int savePrediction(int    patientId,
                              String predictedClass,
                              double confidence,
                              int    inferenceTimeMs,
                              File   imageFile,
                              String clinicianNotes)
            throws SQLException, java.io.IOException {

        Prediction record = new Prediction(
                0,               // id — assigned by the database
                patientId,
                "",              // imagePath — set by PredictionDAO after caching
                predictedClass,
                confidence,
                inferenceTimeMs,
                "",              // timestamp — set by the database DEFAULT
                clinicianNotes
        );
        return predictionDAO.insertPrediction(record, imageFile);
    }

    /**
     * Returns all prediction sessions for a given patient, ordered by
     * most recent first.
     *
     * <p><b>Threading:</b> Must be called from a background thread.</p>
     *
     * @param patientId The patient's primary key.
     * @return List of {@link Prediction} objects (never {@code null}).
     * @throws SQLException if the query fails.
     */
    public List<Prediction> getPredictionsForPatient(int patientId)
            throws SQLException {
        return predictionDAO.getPredictionsByPatientId(patientId);
    }

    /**
     * Deletes a single prediction record by its primary key.
     * Does not delete the cached image file.
     *
     * <p><b>Threading:</b> Must be called from a background thread.</p>
     *
     * @param predictionId The prediction's primary key.
     * @throws SQLException if the delete fails.
     */
    public void deletePrediction(int predictionId) throws SQLException {
        predictionDAO.deleteById(predictionId);
    }

    /**
     * Updates the per-session clinician notes for a single prediction record.
     *
     * <p><b>Threading:</b> Must be called from a background thread.</p>
     *
     * @param predictionId The prediction's primary key.
     * @param notes        New notes text; pass {@code null} to clear.
     * @throws SQLException if the update fails.
     */
    public void updateNotes(int predictionId, String notes) throws SQLException {
        predictionDAO.updateNotes(predictionId, notes == null || notes.isBlank()
                ? null : notes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risk assessment
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Assesses the clinical risk level for a prediction result.
     *
     * <p>Delegates to {@link RiskAssessor#assess(SkinClassifier.PredictionResult)}
     * so the risk-boundary logic lives in one place and is not duplicated
     * across controllers.</p>
     *
     * @param result The inference result to assess.
     * @return {@link RiskLevel#URGENT}, {@link RiskLevel#MODERATE}, or
     *         {@link RiskLevel#LOW}.
     */
    public RiskLevel assessRisk(SkinClassifier.PredictionResult result) {
        return RiskAssessor.assess(result);
    }
}
