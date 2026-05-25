package com.visolearn.data;

import com.visolearn.data.model.Prediction;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Data Access Object for {@link Prediction} entities.
 *
 * <p>Handles all CRUD operations against the {@code Predictions} table,
 * including the image caching mechanism that decouples session records
 * from their source image files.</p>
 *
 * <h3>Image Caching Contract</h3>
 * <p>When {@link #insertPrediction(Prediction, File)} is called, the
 * original image file is <em>copied</em> (not moved) to the local session
 * cache directory ({@code app_data/sessions/images/}) using a
 * deterministic UUID-based filename. Only the <em>relative path</em>
 * to this copy is stored in the database. This ensures:</p>
 * <ul>
 *   <li>The session record is not invalidated if the user moves or deletes the
 *       original source image.</li>
 *   <li>Batch-imported images do not accumulate duplicate copies.</li>
 *   <li>The cache can be cleared independently of the source directory.</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Each call acquires and releases its own
 * {@link Connection} and manages its own file I/O, so concurrent calls
 * are safe provided they run on distinct threads.</p>
 */
public class PredictionDAO {

    /**
     * Inserts a new prediction record and copies the original image to the
     * session cache directory.
     *
     * <p>The image copy uses {@link StandardCopyOption#REPLACE_EXISTING},
     * so re-running inference on the same source image will overwrite the
     * cached copy without creating duplicates.</p>
     *
     * <h3>Thread Safety:</h3>
     * <p>This method performs both disk I/O and a database write. It
     * <b>must</b> be called from a background thread (e.g., inside a
     * {@link javafx.concurrent.Task}) to avoid freezing the JavaFX UI.</p>
     *
     * @param prediction    The {@link Prediction} object containing all
     *                      inference metadata to persist.
     * @param originalImage The source {@link File} to copy into the cache.
     * @return The newly assigned SQLite rowid of the inserted record.
     * @throws SQLException       If the database write fails.
     * @throws java.io.IOException If the image copy fails (e.g., disk full,
     *                             missing source file, or permission denied).
     */
    public int insertPrediction(Prediction prediction, File originalImage)
            throws SQLException, java.io.IOException {

        // ── Step 1: Copy image to session cache, store relative path ──────────
        //
        // Strategy: generate a UUID-based filename to avoid collisions when
        // the same source file is processed multiple times. The original
        // file extension is preserved to allow SwingFXUtils/SwingFXUtils
        // to detect the image format automatically.
        //
        // Example: /home/user/Downloads/lesion_001.jpg
        //          → app_data/sessions/images/a1b2c3d4.jpg

        String originalExtension = getExtension(originalImage.getName());
        String cachedFileName     = UUID.randomUUID().toString() + originalExtension;
        Path  imagesDir         = DatabaseUtil.getImagesDir();
        Path  cachedImagePath    = imagesDir.resolve(cachedFileName);

        Files.copy(
                originalImage.toPath(),
                cachedImagePath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES   // preserve modification timestamp
        );

        // ── Step 2: Persist prediction record with relative path ──────────────
        //
        // We store the relative path (just the filename segment) so that the
        // Prediction model can resolve it to an absolute path at render time
        // using DatabaseUtil.getImagesDir().resolve(imagePath).

        String sql = """
            INSERT INTO Predictions
                (patient_id, image_path, predicted_class, confidence, inference_time, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, prediction.patientId);
            ps.setString(2, cachedFileName);  // relative path (filename only)
            ps.setString(3, prediction.predictedClass);
            ps.setDouble(4, prediction.confidence);
            ps.setInt(5, prediction.inferenceTime);
            ps.setString(6, prediction.notes);

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
     * Retrieves all prediction sessions for a given patient, ordered by
     * most recent first.
     *
     * <p><b>Thread Safety:</b> Returns a modifiable {@link ArrayList}; callers
     * should wrap it in {@link javafx.collections.FXCollections#observableList}
     * before assigning to a {@code TableView}.</p>
     *
     * @param patientId The patient's primary key.
     * @return List of {@link Prediction} objects for that patient (never {@code null}).
     * @throws SQLException If the query fails.
     */
    public List<Prediction> getPredictionsByPatientId(int patientId) throws SQLException {
        List<Prediction> results = new ArrayList<>();

        String sql = """
            SELECT id, patient_id, image_path, predicted_class,
                   confidence, inference_time, timestamp, notes
            FROM   Predictions
            WHERE  patient_id = ?
            ORDER BY timestamp DESC
            """;

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, patientId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Prediction(
                            rs.getInt("id"),
                            rs.getInt("patient_id"),
                            rs.getString("image_path"),
                            rs.getString("predicted_class"),
                            rs.getDouble("confidence"),
                            rs.getInt("inference_time"),
                            rs.getString("timestamp"),
                            rs.getString("notes")
                    ));
                }
            }
        }

        return results;
    }

    /**
     * Retrieves all prediction sessions for a given patient, ordered by
     * oldest first (for longitudinal chart).
     */
    public List<Prediction> getByPatientOrderedByDate(int patientId) {
        List<Prediction> results = new ArrayList<>();
        String sql = """
            SELECT id, patient_id, image_path, predicted_class,
                   confidence, inference_time, timestamp, notes
            FROM   Predictions
            WHERE  patient_id = ?
            ORDER BY timestamp ASC
            """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, patientId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Prediction(
                            rs.getInt("id"),
                            rs.getInt("patient_id"),
                            rs.getString("image_path"),
                            rs.getString("predicted_class"),
                            rs.getDouble("confidence"),
                            rs.getInt("inference_time"),
                            rs.getString("timestamp"),
                            rs.getString("notes")
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }

    /**
     * Deletes a single prediction record by its primary key.
     * Does not delete the cached image file.
     *
     * @param predictionId The prediction's primary key.
     * @throws SQLException If the delete fails.
     */
    public void deleteById(int predictionId) throws SQLException {
        String sql = "DELETE FROM Predictions WHERE id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, predictionId);
            ps.executeUpdate();
        }
    }

    /**
     * Counts the number of predictions per predicted class for a given patient.
     * Used to populate the distribution {@link javafx.scene.chart.PieChart}.
     *
     * @param patientId The patient's primary key.
     * @return A {@link List} of {@code String[] {className, count}} pairs.
     * @throws SQLException If the query fails.
     */
    public List<String[]> getClassDistributionByPatient(int patientId) throws SQLException {
        List<String[]> results = new ArrayList<>();

        String sql = """
            SELECT predicted_class, COUNT(*) AS cnt
            FROM   Predictions
            WHERE  patient_id = ?
            GROUP BY predicted_class
            ORDER BY cnt DESC
            """;

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, patientId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new String[]{
                            rs.getString("predicted_class"),
                            String.valueOf(rs.getInt("cnt"))
                    });
                }
            }
        }

        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Extracts the lowercase file extension including the dot. */
    private static String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot).toLowerCase() : "";
    }

    /**
     * Updates the per-session doctor notes for a single prediction.
     *
     * @param predictionId The prediction's primary key.
     * @param notes        New notes text (may be {@code null} to clear).
     * @throws SQLException If the update fails.
     */
    public void updateNotes(int predictionId, String notes) throws SQLException {
        String sql = "UPDATE Predictions SET notes = ? WHERE id = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, notes);
            ps.setInt(2, predictionId);
            ps.executeUpdate();
        }
    }
}