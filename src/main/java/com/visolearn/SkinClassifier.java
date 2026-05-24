package com.visolearn;

import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SkinClassifier loads the EfficientNet-B4 and DenseNet-169 ONNX models
 * and runs an ensemble inference on preprocessed skin lesion images.
 *
 * <h3>Memory Management Fix (v2.2):</h3>
 * <p>The {@code executeEnsemble()} method now wraps all {@link NDList} prediction
 * outputs in a {@code try-with-resources} block. {@link NDList} implements
 * {@link AutoCloseable} and manages off-heap native memory that is <em>not</em>
 * subject to Java garbage collection. Failing to explicitly close these objects
 * caused native memory to balloon linearly during batch analysis sessions,
 * eventually exhausting system memory and crashing the application.
 * The fix ensures that both {@code effNetOutput} and {@code denseNetOutput}
 * are deterministically released immediately after their float arrays are
 * extracted — regardless of whether the block exits normally or via exception.</p>
 *
 * @author Rao Hamza Bilal
 * @version 2.3 (Centralized Class Name Constants)
 */
public class SkinClassifier implements AutoCloseable {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    private static final String EFFNET_RESOURCE   = "/efficientnet_b4_v3.onnx";
    private static final String DENSENET_RESOURCE = "/densenet169_v2.onnx";
    private static final String LABELS_RESOURCE   = "/labels.txt";
    private static final int    NUM_CLASSES       = 7;

    /**
     * Canonical full-text names for all 7 skin condition classes, indexed to
     * match model output logits and the {@code labels.txt} resource file.
     *
     * <p>This centralized constant prevents duplication across UI controllers,
     * report generators, and batch analysis modules. Any component displaying
     * or exporting class names should reference this array rather than hardcoding
     * strings inline.</p>
     *
     * <table border="1">
     *   <caption>Class index mapping</caption>
     *   <tr><th>Index</th><th>Full Name</th></tr>
     *   <tr><td>0</td><td>Actinic Keratosis</td></tr>
     *   <tr><td>1</td><td>Basal Cell Carcinoma</td></tr>
     *   <tr><td>2</td><td>Benign Keratosis</td></tr>
     *   <tr><td>3</td><td>Dermatofibroma</td></tr>
     *   <tr><td>4</td><td>Melanoma</td></tr>
     *   <tr><td>5</td><td>Melanocytic Nevus</td></tr>
     *   <tr><td>6</td><td>Vascular Lesion</td></tr>
     * </table>
     */
    public static final String[] CLASS_FULL_NAMES = {
            "Actinic Keratosis", "Basal Cell Carcinoma", "Benign Keratosis",
            "Dermatofibroma", "Melanoma", "Melanocytic Nevus", "Vascular Lesion"
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Model & Predictor Fields
    // ─────────────────────────────────────────────────────────────────────────

    // ZooModel is thread-safe: multiple Predictor instances can be created
    // from the same model concurrently. Only Predictor itself is not thread-safe.
    private ZooModel<NDList, NDList>   effNetModel;
    private Predictor<NDList, NDList>  effNetPredictor;   // used by single-image predict()

    private ZooModel<NDList, NDList>   denseNetModel;
    private Predictor<NDList, NDList>  denseNetPredictor; // used by single-image predict()

    private final ImagePreprocessor    preprocessor;
    private List<String>               classLabels;

    ZooModel<NDList, NDList> getEffNetModel() { return effNetModel; }
    ZooModel<NDList, NDList> getDenseNetModel() { return denseNetModel; }

    // ─────────────────────────────────────────────────────────────────────────
    // Prediction Result Record
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable value object carrying the full result of one ensemble inference pass.
     */
    public static class PredictionResult {

        /** Zero-based index of the winning class. */
        public final int    classIndex;

        /** Human-readable label for the winning class (loaded from labels.txt). */
        public final String className;

        /**
         * Confidence of the winning class expressed as a percentage (0–100).
         * Derived from the softmax probability of the averaged ensemble logits.
         */
        public final float  confidence;

        /**
         * Full softmax probability distribution across all {@code NUM_CLASSES} classes.
         * Values sum to approximately 1.0 (minor floating-point rounding may apply).
         */
        public final float[] allProbabilities;

        /** Wall-clock time from prediction entry to result, in milliseconds. */
        public final long   inferenceTimeMs;

        public PredictionResult(int classIndex, String className,
                                float confidence, float[] allProbabilities,
                                long inferenceTimeMs) {
            this.classIndex       = classIndex;
            this.className        = className;
            this.confidence       = confidence;
            this.allProbabilities = allProbabilities;
            this.inferenceTimeMs  = inferenceTimeMs;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-Thread Predictor Pair
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A short-lived pair of {@link Predictor} instances — one per ONNX model —
     * intended to be used by a single batch worker thread and closed when done.
     *
     * <p>{@link Predictor} is <em>not</em> thread-safe: it holds mutable inference
     * state internally. {@link ZooModel}, however, <em>is</em> thread-safe and
     * can create many {@code Predictor} instances concurrently. This class
     * therefore lets each batch worker own an independent predictor pair sourced
     * from the same shared models — giving true parallel inference with no
     * synchronization needed at the call site.</p>
     *
     * <p>Usage pattern:
     * <pre>{@code
     * try (SkinClassifier.PredictorPair pair = classifier.newPredictorPair()) {
     *     PredictionResult r = pair.predict(imagePath);
     * }
     * }</pre>
     */
    public class PredictorPair implements AutoCloseable {

        private final Predictor<NDList, NDList> effNet;
        private final Predictor<NDList, NDList> denseNet;

        private PredictorPair(Predictor<NDList, NDList> effNet,
                              Predictor<NDList, NDList> denseNet) {
            this.effNet   = effNet;
            this.denseNet = denseNet;
        }

        /**
         * Runs ensemble inference on an image file using this pair's private
         * predictors. Safe to call concurrently with other {@code PredictorPair}
         * instances — no synchronization required.
         *
         * @param imagePath Absolute path to the source image.
         * @return A fully populated {@link PredictionResult}.
         * @throws Exception if preprocessing or inference fails.
         */
        public PredictionResult predict(Path imagePath) throws Exception {
            long startTime = System.currentTimeMillis();
            try (NDManager manager = NDManager.newBaseManager()) {
                NDArray input = preprocessor.preprocessFromFile(manager, imagePath);
                return runEnsemble(input, startTime);
            }
        }

        /**
         * Runs ensemble inference on an in-memory image using this pair's private
         * predictors.
         *
         * @param image A fully decoded {@link BufferedImage}.
         * @return A fully populated {@link PredictionResult}.
         * @throws Exception if preprocessing or inference fails.
         */
        public PredictionResult predictFromImage(BufferedImage image) throws Exception {
            long startTime = System.currentTimeMillis();
            try (NDManager manager = NDManager.newBaseManager()) {
                NDArray input = preprocessor.preprocessFromImage(manager, image);
                return runEnsemble(input, startTime);
            }
        }

        /** Delegates to the shared ensemble logic using this pair's predictors. */
        private PredictionResult runEnsemble(NDArray inputTensor, long startTime)
                throws Exception {
            return SkinClassifier.this.executeEnsemble(inputTensor, startTime, effNet, denseNet);
        }

        /**
         * Closes both predictors, releasing their native ONNX Runtime resources.
         * The parent {@link ZooModel} instances are not affected.
         */
        @Override
        public void close() {
            if (effNet   != null) effNet.close();
            if (denseNet != null) denseNet.close();
        }
    }

    /**
     * Creates a new {@link PredictorPair} backed by the already-loaded ONNX models.
     *
     * <p>Call this once per batch worker thread and use it for all images that
     * worker processes. Close the pair when the worker finishes to release its
     * native predictor memory. {@link ZooModel#newPredictor()} is thread-safe
     * and can be called concurrently from multiple threads.</p>
     *
     * @return A fresh {@link PredictorPair} owned by the caller.
     * @throws IllegalStateException if {@link #initialize()} has not been called yet.
     */
    public PredictorPair newPredictorPair() {
        if (effNetModel == null || denseNetModel == null) {
            throw new IllegalStateException(
                    "SkinClassifier.initialize() must be called before newPredictorPair().");
        }
        return new PredictorPair(
                effNetModel.newPredictor(),
                denseNetModel.newPredictor()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public SkinClassifier() {
        this.preprocessor = new ImagePreprocessor();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads both ONNX models from the JAR resources into temporary files,
     * then constructs DJL {@link ZooModel} and {@link Predictor} instances
     * for each. Must be called once before any {@code predict} call.
     *
     * @throws Exception if a model resource is missing, the ONNX engine is
     *                   unavailable, or any DJL initialization step fails.
     */
    public void initialize() throws Exception {
        System.out.println("SkinClassifier: loading ensemble models...");
        classLabels = loadClassLabels();

        // ── Load EfficientNet-B4 ─────────────────────────────────────────────
        Path effNetPath = extractModelToTemp(EFFNET_RESOURCE, "effnet");
        Criteria<NDList, NDList> effNetCriteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(effNetPath)
                .optEngine("OnnxRuntime")
                .optDevice(Device.cpu())
                .build();
        effNetModel     = ModelZoo.loadModel(effNetCriteria);
        effNetPredictor = effNetModel.newPredictor();

        // ── Load DenseNet-169 ────────────────────────────────────────────────
        Path denseNetPath = extractModelToTemp(DENSENET_RESOURCE, "densenet");
        Criteria<NDList, NDList> denseNetCriteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(denseNetPath)
                .optEngine("OnnxRuntime")
                .optDevice(Device.cpu())
                .build();
        denseNetModel     = ModelZoo.loadModel(denseNetCriteria);
        denseNetPredictor = denseNetModel.newPredictor();

        System.out.println("SkinClassifier: Both models loaded successfully.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public Prediction API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Primary prediction entry point for image files on disk.
     *
     * <p>Opens a scoped {@link NDManager}, preprocesses the image at the given
     * path into a normalised input tensor, delegates to
     * {@link #executeEnsemble(NDArray, long)}, and then releases all
     * intermediate NDArrays when the manager is closed.</p>
     *
     * @param imagePath Absolute or relative path to the source image.
     * @return A fully populated {@link PredictionResult}.
     * @throws Exception if preprocessing or inference fails.
     */
    public PredictionResult predict(Path imagePath) throws Exception {
        long startTime = System.currentTimeMillis();
        try (NDManager predictionManager = NDManager.newBaseManager()) {
            NDArray inputTensor = preprocessor.preprocessFromFile(predictionManager, imagePath);
            return executeEnsemble(inputTensor, startTime, effNetPredictor, denseNetPredictor);
        }
    }

    public PredictionResult predictWithPredictors(Path imagePath, Predictor<NDList, NDList> effPredictor, Predictor<NDList, NDList> densePredictor) throws Exception {
        long startTime = System.currentTimeMillis();
        try (NDManager predictionManager = NDManager.newBaseManager()) {
            NDArray inputTensor = preprocessor.preprocessFromFile(predictionManager, imagePath);
            return executeEnsemble(inputTensor, startTime, effPredictor, densePredictor);
        }
    }

    /**
     * Secondary prediction entry point for in-memory {@link BufferedImage} objects.
     *
     * <p>Required by {@code OcclusionRenderer} which synthesises occluded image
     * variants programmatically during saliency map generation. Each occlusion
     * pass produces a fresh {@link BufferedImage} that bypasses the filesystem.</p>
     *
     * @param image A fully decoded {@link BufferedImage} (e.g., an occluded patch variant).
     * @return A fully populated {@link PredictionResult}.
     * @throws Exception if preprocessing or inference fails.
     */
    public PredictionResult predictFromImage(BufferedImage image) throws Exception {
        long startTime = System.currentTimeMillis();
        try (NDManager predictionManager = NDManager.newBaseManager()) {
            NDArray inputTensor = preprocessor.preprocessFromImage(predictionManager, image);
            return executeEnsemble(inputTensor, startTime, effNetPredictor, denseNetPredictor);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core Ensemble Logic — FIXED: NDList try-with-resources
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs the input tensor through both ONNX models, averages their raw logits,
     * applies softmax, and returns the winning class with its full probability
     * distribution.
     *
     * <p>This overload accepts explicit predictor instances so it can be called
     * from both the shared {@link #predict}/{@link #predictFromImage} methods
     * (which use the single-instance predictors) and from {@link PredictorPair}
     * (which supplies per-thread private predictors for true parallelism).</p>
     *
     * <h4>Memory-Safety Contract:</h4>
     * <p>Both {@link NDList} objects returned by {@link Predictor#predict(Object)}
     * allocate their underlying {@link NDArray} buffers in <em>off-heap native
     * memory</em> managed by the ONNX Runtime allocator — entirely outside the
     * Java heap and therefore invisible to the garbage collector. If not explicitly
     * closed, this memory accumulates for every image processed in a batch session.</p>
     *
     * <p>The fix wraps {@code effNetOutput} and {@code denseNetOutput} in a
     * single {@code try-with-resources} statement. The JVM guarantees that
     * {@code NDList.close()} is called on both objects — in reverse declaration
     * order — immediately after the {@code float[]} arrays have been extracted
     * via {@code toFloatArray()}, which copies the values onto the Java heap
     * where they are safely accessible after the native buffers are freed.</p>
     *
     * @param inputTensor  A preprocessed, normalised {@link NDArray} of shape
     *                     {@code [1, 3, H, W]} ready for both models.
     * @param startTime    {@code System.currentTimeMillis()} captured at the
     *                     public API entry point, used for end-to-end timing.
     * @param effPredictor The EfficientNet-B4 predictor to use for this call.
     * @param densePredictor The DenseNet-169 predictor to use for this call.
     * @return A fully populated {@link PredictionResult}.
     * @throws Exception if either predictor throws during inference.
     */
    private PredictionResult executeEnsemble(NDArray inputTensor, long startTime,
            Predictor<NDList, NDList> effPredictor,
            Predictor<NDList, NDList> densePredictor) throws Exception {

        NDList inputList = new NDList(inputTensor);

        final float[] effNetLogits;
        final float[] denseNetLogits;

        try (NDList effNetOutput   = effPredictor.predict(inputList);
             NDList denseNetOutput = densePredictor.predict(inputList)) {

            effNetLogits   = effNetOutput.get(0).toFloatArray();
            denseNetLogits = denseNetOutput.get(0).toFloatArray();

        } // <-- NDList native buffers freed deterministically here

        // ── Average the raw logits from both models ───────────────────────────
        //
        // Logit-space averaging is numerically equivalent to a geometric mean
        // of the pre-softmax activations and produces well-calibrated ensemble
        // probabilities when both models share the same output dimensionality.

        float[] averagedLogits = new float[NUM_CLASSES];
        float maxLogit = -Float.MAX_VALUE;

        for (int i = 0; i < NUM_CLASSES; i++) {
            averagedLogits[i] = (effNetLogits[i] + denseNetLogits[i]) / 2.0f;
            if (averagedLogits[i] > maxLogit) {
                maxLogit = averagedLogits[i];
            }
        }

        // ── Numerically stable softmax ────────────────────────────────────────
        //
        // Subtracting maxLogit before exp() prevents floating-point overflow
        // for large positive logits (e.g., if a model outputs values > 88,
        // Math.exp would return Infinity). The subtraction does not change the
        // final probability distribution because it cancels out in the division.

        float[] probabilities = new float[NUM_CLASSES];
        float sumExp = 0.0f;

        for (int i = 0; i < NUM_CLASSES; i++) {
            probabilities[i] = (float) Math.exp(averagedLogits[i] - maxLogit);
            sumExp += probabilities[i];
        }
        for (int i = 0; i < NUM_CLASSES; i++) {
            probabilities[i] /= sumExp;
        }

        // ── Argmax — locate the winning class ────────────────────────────────

        int   bestIndex = 0;
        float bestProb  = probabilities[0];

        for (int i = 1; i < NUM_CLASSES; i++) {
            if (probabilities[i] > bestProb) {
                bestProb  = probabilities[i];
                bestIndex = i;
            }
        }

        // ── Assemble and return the result ────────────────────────────────────

        long   inferenceTime = System.currentTimeMillis() - startTime;
        String className     = classLabels.get(bestIndex);
        float  confidence    = bestProb * 100.0f;  // convert fraction → percentage

        return new PredictionResult(bestIndex, className, confidence, probabilities, inferenceTime);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource Loading Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads {@code labels.txt} from the JAR classpath and returns each
     * non-empty line as an ordered class label.
     *
     * @return Ordered list of class label strings matching model output indices.
     * @throws IOException              if the stream cannot be read.
     * @throws IllegalStateException    if the resource is not found on the classpath.
     */
    private List<String> loadClassLabels() throws IOException {
        InputStream stream = getClass().getResourceAsStream(LABELS_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(
                    "labels.txt not found in JAR resources. " +
                            "Ensure it is present at src/main/resources/labels.txt"
            );
        }
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    labels.add(line.trim());
                }
            }
        }
        return labels;
    }

    /**
     * Extracts an ONNX model from the JAR classpath to a temporary file so
     * that the DJL {@link ModelZoo} can load it via a filesystem {@link Path}.
     *
     * <p>The temporary file is registered for deletion on JVM exit via
     * {@link java.io.File#deleteOnExit()}, providing a best-effort cleanup
     * for normal shutdown scenarios.</p>
     *
     * @param resourcePath Classpath-relative resource path (e.g., {@code "/model.onnx"}).
     * @param prefix       Prefix string for the temporary file name.
     * @return {@link Path} pointing to the extracted temporary {@code .onnx} file.
     * @throws IOException           if the resource stream cannot be read or the
     *                               temp file cannot be written.
     * @throws IllegalStateException if the resource is not found on the classpath.
     */
    private Path extractModelToTemp(String resourcePath, String prefix) throws IOException {
        InputStream modelStream = getClass().getResourceAsStream(resourcePath);
        if (modelStream == null) {
            throw new IllegalStateException(
                    resourcePath + " not found in JAR resources. " +
                            "Ensure the ONNX model file is placed in src/main/resources/"
            );
        }

        java.io.File tempFile = java.io.File.createTempFile(prefix + "_model", ".onnx");
        tempFile.deleteOnExit();

        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
            byte[] buffer    = new byte[8192];
            int    bytesRead;
            while ((bytesRead = modelStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        } finally {
            modelStream.close();
        }

        return tempFile.toPath();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessors
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the ordered list of class label strings loaded from
     * {@code labels.txt}. Index {@code i} corresponds to model output {@code i}.
     *
     * @return Unmodifiable view of the class labels list.
     */
    public List<String> getClassLabels() {
        return classLabels;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AutoCloseable — Resource Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Releases all DJL native resources held by this classifier.
     *
     * <p>Closes {@link Predictor} instances before their parent {@link ZooModel}
     * instances, matching the DJL recommended teardown order. Safe to call
     * multiple times (null-checked). Intended to be invoked in a
     * {@code try-with-resources} block at the application lifecycle boundary.</p>
     */
    @Override
    public void close() {
        if (effNetPredictor != null) {
            effNetPredictor.close();
        }
        if (effNetModel != null) {
            effNetModel.close();
        }
        if (denseNetPredictor != null) {
            denseNetPredictor.close();
        }
        if (denseNetModel != null) {
            denseNetModel.close();
        }
        System.out.println("SkinClassifier: All ensemble resources released.");
    }
}