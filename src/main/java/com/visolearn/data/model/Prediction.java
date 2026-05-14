package com.visolearn.data.model;


/**
 * Immutable data model representing a single AI inference session for a
 * patient in VisoLearn AI Studio.
 *
 * <p>An instance is constructed by {@link com.visolearn.data.PredictionDAO}
 * from a result set row and is displayed in the history {@code TableView}.
 * The cached image is stored at a relative path inside the session images
 * directory; it is resolved to an absolute path by {@link #getImagePath()}
 * at render time.</p>
 */
public class Prediction {

    /** Primary key — assigned by SQLite AUTOINCREMENT on insert. */
    public final int    id;

    /** Foreign key into {@code Patients.id}. */
    public final int    patientId;

    /**
     * Relative path from the session images root to the cached image file.
     * Stored in the database instead of the original path to decouple the
     * session record from the source image's lifetime.
     *
     * @see #getImagePath()
     */
    public final String imagePath;

    /** AI-predicted skin condition class name (e.g., {@code "Melanoma"}). */
    public final String predictedClass;

    /** Model confidence as a percentage (0–100). */
    public final double confidence;

    /** Model inference time in milliseconds. */
    public final int    inferenceTime;

    /**
     * ISO 8601 timestamp of when the prediction was recorded
     * ({@code YYYY-MM-DD HH:MM:SS}).
     */
    public final String timestamp;

    /**
     * Constructs a new {@link Prediction} record.
     *
     * @param id              SQLite-generated primary key.
     * @param patientId       Owning patient's {@code id}.
     * @param imagePath       Relative path to the cached session image.
     * @param predictedClass  Predicted class name.
     * @param confidence      Confidence percentage (0–100).
     * @param inferenceTime   Inference time in milliseconds.
     * @param timestamp       ISO 8601 timestamp string.
     */
    public Prediction(int id, int patientId, String imagePath,
                      String predictedClass, double confidence,
                      int inferenceTime, String timestamp) {
        this.id              = id;
        this.patientId        = patientId;
        this.imagePath        = imagePath;
        this.predictedClass   = predictedClass;
        this.confidence       = confidence;
        this.inferenceTime    = inferenceTime;
        this.timestamp        = timestamp;
    }

    /**
     * Resolves the cached image path to an absolute {@link java.nio.file.Path}.
     *
     * <p>This allows the {@link com.visolearn.HistoryController} to construct an
     * {@link javafx.scene.image.Image} for the table thumbnail without knowing
     * the internal directory structure of {@link com.visolearn.data.DatabaseUtil}.</p>
     *
     * @return Absolute {@link java.nio.file.Path} to the cached image file.
     */
    public java.nio.file.Path getImagePath() {
        return com.visolearn.data.DatabaseUtil.getImagesDir().resolve(imagePath);
    }
}