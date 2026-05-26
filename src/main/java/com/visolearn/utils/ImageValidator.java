package com.visolearn.utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * ImageValidator performs lightweight sanity checks on an image file
 * before it is passed to the ONNX inference pipeline.
 *
 * <p>These checks prevent three known silent-failure modes:</p>
 * <ol>
 *   <li><b>Corrupted / unreadable file</b> — {@link ImageIO#read} returns
 *       {@code null}; without this check the model receives a null tensor
 *       and throws an {@link IllegalArgumentException} that the UI reports
 *       only as a generic "Inference failed." message.</li>
 *   <li><b>Tiny thumbnail</b> — an image smaller than
 *       {@value #MIN_DIMENSION}×{@value #MIN_DIMENSION} px is silently
 *       upscaled to 380×380 by the preprocessor, producing a numerically
 *       valid but semantically meaningless prediction.</li>
 *   <li><b>Extreme aspect ratio</b> — images wider or taller than
 *       {@value #MAX_ASPECT_RATIO}:1 are almost certainly not dermoscopy
 *       scans; the user is warned so they can select the correct file.</li>
 * </ol>
 *
 * <p>This class is intentionally free of JavaFX dependencies so it can be
 * used from any controller without coupling.</p>
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public final class ImageValidator {

    // ─────────────────────────────────────────────────────────────────────────
    // Validation Thresholds
    // ─────────────────────────────────────────────────────────────────────────

    /** Minimum accepted image dimension (width and height) in pixels. */
    private static final int MIN_DIMENSION = 50;

    /**
     * Maximum accepted file size in bytes (50 MB).
     * Real dermoscopy images are typically 1–15 MB; anything larger is
     * almost certainly the wrong file type.
     */
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB

    /**
     * Minimum accepted file size in bytes (1 KB).
     * Files smaller than this are almost certainly empty or zero-byte stubs.
     */
    private static final long MIN_FILE_SIZE_BYTES = 1024; // 1 KB

    /**
     * Maximum accepted aspect ratio (width÷height or height÷width).
     * Dermoscopy images are near-square; a ratio above this value strongly
     * suggests a panoramic photo, banner image, or similar non-clinical file.
     */
    private static final double MAX_ASPECT_RATIO = 4.0;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Utility class — no instances. */
    private ImageValidator() {}

    /**
     * Validates {@code file} and returns a {@link ValidationResult} that
     * describes whether the file is safe to pass to the inference pipeline.
     *
     * <p>Checks are performed in the following order so that expensive
     * operations (disk read) are skipped when cheap ones already fail:</p>
     * <ol>
     *   <li>File existence and readability</li>
     *   <li>File size (too small or too large)</li>
     *   <li>Decodability via {@link ImageIO#read}</li>
     *   <li>Minimum pixel dimensions</li>
     *   <li>Aspect ratio</li>
     * </ol>
     *
     * @param file The candidate image file. Must not be {@code null}.
     * @return A {@link ValidationResult}; call {@link ValidationResult#valid()}
     *         to decide whether to proceed with inference.
     */
    public static ValidationResult validate(File file) {
        if (file == null) {
            return ValidationResult.failure(
                    "No file selected",
                    "No file was provided. Please choose a dermoscopy image."
            );
        }

        // ── Check 1: existence and readability ──────────────────────────────
        if (!file.exists()) {
            return ValidationResult.failure(
                    "File not found",
                    "The selected file no longer exists:\n" + file.getAbsolutePath() +
                    "\n\nPlease re-select the image."
            );
        }
        if (!file.canRead()) {
            return ValidationResult.failure(
                    "File not readable",
                    "Cannot read the selected file. Check that the file is not locked " +
                    "by another application and that you have permission to access it."
            );
        }

        // ── Check 2: file size ──────────────────────────────────────────────
        long sizeBytes = file.length();
        if (sizeBytes < MIN_FILE_SIZE_BYTES && !file.getName().toLowerCase().endsWith(".png")) {
            return ValidationResult.failure(
                    "File too small",
                    String.format(
                            "The selected file is only %d bytes — it appears to be empty " +
                            "or corrupt.\n\nPlease choose a valid dermoscopy image.",
                            sizeBytes)
            );
        }
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            return ValidationResult.failure(
                    "File too large",
                    String.format(
                            "The selected file is %.1f MB. VisoLearn accepts images up to " +
                            "50 MB. Please check that you have selected the correct file.",
                            sizeBytes / (1024.0 * 1024.0))
            );
        }

        // ── Check 3: decodability ───────────────────────────────────────────
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(file);
        } catch (Exception e) {
            return ValidationResult.failure(
                    "Cannot decode image",
                    "The file could not be decoded as an image:\n" +
                    file.getName() + "\n\nIt may be corrupt or in an unsupported format. " +
                    "Please use a standard JPG or PNG file."
            );
        }

        if (decoded == null) {
            return ValidationResult.failure(
                    "Unrecognised image format",
                    "The file '" + file.getName() + "' could not be read as a JPG or PNG image.\n\n" +
                    "Common causes:\n" +
                    "  • The file extension does not match the actual format\n" +
                    "  • The file is truncated or partially downloaded\n" +
                    "  • The file is a TIFF, HEIC, or other unsupported format"
            );
        }

        // ── Check 4: minimum dimensions ─────────────────────────────────────
        int width  = decoded.getWidth();
        int height = decoded.getHeight();

        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            return ValidationResult.failure(
                    "Image too small",
                    String.format(
                            "The image is only %d×%d pixels — too small for reliable analysis.\n\n" +
                            "Dermoscopy images should be at least %d×%d pixels. " +
                            "Please provide a full-resolution scan.",
                            width, height, MIN_DIMENSION, MIN_DIMENSION)
            );
        }

        // ── Check 5: aspect ratio ───────────────────────────────────────────
        double ratio = (double) Math.max(width, height) / Math.min(width, height);
        if (ratio > MAX_ASPECT_RATIO) {
            // Warn but do not block — the user may intentionally upload a
            // cropped or wide-field image. Return a warning result instead.
            return ValidationResult.warning(
                    "Unusual image shape",
                    String.format(
                            "The image has an extreme aspect ratio (%.1f:1) and may not be a " +
                            "standard dermoscopy scan.\n\nIf this is the correct image, inference " +
                            "will still run. Otherwise, please re-select.",
                            ratio)
            );
        }

        // All checks passed
        return ValidationResult.ok();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result Type
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable result returned by {@link #validate(File)}.
     *
     * <p>Three factory methods cover all outcomes:</p>
     * <ul>
     *   <li>{@link #ok()} — all checks passed, proceed with inference</li>
     *   <li>{@link #warning(String, String)} — non-fatal issue; inference is
     *       allowed but the user should be notified</li>
     *   <li>{@link #failure(String, String)} — fatal issue; inference must
     *       be blocked</li>
     * </ul>
     */
    public static final class ValidationResult {

        /**
         * Severity level of a {@link ValidationResult}.
         *
         * <ul>
         *   <li>{@code OK}      — no issues detected</li>
         *   <li>{@code WARNING} — non-fatal anomaly; inference may proceed</li>
         *   <li>{@code ERROR}   — fatal issue; inference must be blocked</li>
         * </ul>
         */
        public enum Severity { OK, WARNING, ERROR }

        private final Severity severity;
        private final String   title;
        private final String   detail;

        private ValidationResult(Severity severity, String title, String detail) {
            this.severity = severity;
            this.title    = title;
            this.detail   = detail;
        }

        /** @return {@code true} if inference may proceed (OK or WARNING). */
        public boolean valid()    { return severity != Severity.ERROR; }

        /** @return {@code true} if the result is a non-fatal warning. */
        public boolean isWarning() { return severity == Severity.WARNING; }

        /** @return Short title suitable for a toast heading or dialog title. */
        public String  title()    { return title; }

        /** @return Long descriptive message for the user. */
        public String  detail()   { return detail; }

        // Factory methods
        static ValidationResult ok() {
            return new ValidationResult(Severity.OK, "", "");
        }

        static ValidationResult warning(String title, String detail) {
            return new ValidationResult(Severity.WARNING, title, detail);
        }

        static ValidationResult failure(String title, String detail) {
            return new ValidationResult(Severity.ERROR, title, detail);
        }
    }
}
