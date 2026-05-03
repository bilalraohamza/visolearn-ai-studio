package com.visolearn;

import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SkinClassifier loads the EfficientNet-B4 ONNX model and runs
 * inference on preprocessed skin lesion images.
 *
 * It returns raw probability scores for all 7 skin lesion classes
 * after applying softmax to the model's raw logit outputs.
 *
 * Model input:  [1, 3, 380, 380] float32 tensor
 * Model output: [1, 7] float32 logits → converted to probabilities
 *
 * Class order (must match labels.txt):
 * 0=akiec, 1=bcc, 2=bkl, 3=df, 4=mel, 5=nv, 6=vasc
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class SkinClassifier implements AutoCloseable {

    /** Path to ONNX model file inside resources folder. */
    private static final String MODEL_RESOURCE = "/skin_model.onnx";

    /** Path to class labels file inside resources folder. */
    private static final String LABELS_RESOURCE = "/labels.txt";

    /** Number of output classes the model predicts. */
    private static final int NUM_CLASSES = 7;

    /** DJL model loaded from ONNX file. */
    private ZooModel<NDList, NDList> model;

    /** DJL predictor that runs inference. */
    private Predictor<NDList, NDList> predictor;

    /** NDManager for tensor memory management. */
    private NDManager manager;

    /** Image preprocessor for resizing and normalizing inputs. */
    private final ImagePreprocessor preprocessor;

    /** Class label names loaded from labels.txt. */
    private List<String> classLabels;

    /**
     * Holds the result of one inference call.
     * Contains predicted class index, name, confidence,
     * and probabilities for all 7 classes.
     */
    public static class PredictionResult {

        /** Index of the predicted class (0-6). */
        public final int classIndex;

        /** Name of the predicted class (e.g. "mel"). */
        public final String className;

        /** Confidence percentage of the top prediction (0-100). */
        public final float confidence;

        /** Probability scores for all 7 classes (0-1 range). */
        public final float[] allProbabilities;

        /** Time taken for inference in milliseconds. */
        public final long inferenceTimeMs;

        /**
         * Constructs a prediction result.
         *
         * @param classIndex       index of predicted class
         * @param className        name of predicted class
         * @param confidence       top class confidence (0-100)
         * @param allProbabilities probabilities for all 7 classes
         * @param inferenceTimeMs  inference duration in milliseconds
         */
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

    /**
     * Constructs the SkinClassifier.
     * Call initialize() after construction to load the model.
     */
    public SkinClassifier() {
        this.preprocessor = new ImagePreprocessor();
    }

    /**
     * Loads the ONNX model and class labels from resources.
     * Must be called once before running any predictions.
     * This method is slow (2-5 seconds) — call it on a
     * background thread, never on the JavaFX Application Thread.
     *
     * @throws Exception if the model or labels cannot be loaded
     */
    public void initialize() throws Exception {

        System.out.println("SkinClassifier: loading model...");

        // Load class labels from labels.txt
        classLabels = loadClassLabels();
        System.out.println("SkinClassifier: loaded " +
                classLabels.size() + " class labels: " + classLabels);


        // Extract ONNX model from resources to a temp file
        // DJL requires a file path — it cannot load from InputStream
        Path modelPath = extractModelToTemp();

        // Build DJL Criteria to load the ONNX model
        // NoBatchifyTranslator passes NDList directly without batching
        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelPath)
                .optEngine("OnnxRuntime")
                .optDevice(Device.cpu())
                .build();

        // Load the model — this is the slow step
        model     = ModelZoo.loadModel(criteria);
        predictor = model.newPredictor();

        System.out.println("SkinClassifier: model loaded successfully.");
    }

    /**
     * Runs inference on an image file and returns prediction results.
     * This method handles the complete pipeline:
     * load → preprocess → infer → softmax → return result
     *
     * Always call this on a background thread using Task<Void>.
     * Never call from the JavaFX Application Thread.
     *
     * @param imagePath path to the image file on disk
     * @return PredictionResult with class, confidence and all scores
     * @throws Exception if inference fails
     */
    public PredictionResult predict(Path imagePath) throws Exception {

        long startTime = System.currentTimeMillis();

        // Use try-with-resources to create a child NDManager
        // scoped to this single prediction call.
        // This ensures tensors are released after inference.
        try (NDManager predictionManager = NDManager.newBaseManager()) {

            // Step 1 — Preprocess image to float tensor [1, 3, 380, 380]
            NDArray inputTensor = preprocessor.preprocessFromFile(
                    predictionManager, imagePath);

            // Step 2 — Wrap in NDList for DJL predictor
            NDList inputList = new NDList(inputTensor);

            // Step 3 — Run ONNX model inference
            // Output is raw logits [1, 7]
            NDList outputList = predictor.predict(inputList);
            NDArray logits    = outputList.get(0);

            // Step 4 — Convert logits to float array
            float[] rawLogits = logits.toFloatArray();

            // Step 5 — Apply softmax manually in Java
            // softmax(x_i) = exp(x_i - max) / sum(exp(x_j - max))
            // Subtracting max prevents floating point overflow
            float maxLogit = rawLogits[0];
            for (float v : rawLogits) {
                if (v > maxLogit) maxLogit = v;
            }

            float[] probabilities = new float[NUM_CLASSES];
            float   sumExp        = 0f;
            for (int i = 0; i < NUM_CLASSES; i++) {
                probabilities[i] = (float) Math.exp(rawLogits[i] - maxLogit);
                sumExp += probabilities[i];
            }
            for (int i = 0; i < NUM_CLASSES; i++) {
                probabilities[i] /= sumExp;
            }

            // Step 6 — Find highest probability class
            int   bestIndex = 0;
            float bestProb  = probabilities[0];
            for (int i = 1; i < NUM_CLASSES; i++) {
                if (probabilities[i] > bestProb) {
                    bestProb  = probabilities[i];
                    bestIndex = i;
                }
            }

            // Step 7 — Build and return result
            long   inferenceTime = System.currentTimeMillis() - startTime;
            String className     = classLabels.get(bestIndex);
            float  confidence    = bestProb * 100f;

            System.out.printf("SkinClassifier: predicted %s " +
                            "(%.2f%%) in %dms%n",
                    className, confidence, inferenceTime);

            return new PredictionResult(
                    bestIndex,
                    className,
                    confidence,
                    probabilities,
                    inferenceTime
            );
        }
    }

    /**
     * Loads class label names from labels.txt in resources.
     * File format: one class name per line, in index order.
     * Line 0 = class 0 (akiec), Line 1 = class 1 (bcc), etc.
     *
     * @return ordered list of class label strings
     * @throws IOException if labels.txt cannot be read
     */
    private List<String> loadClassLabels() throws IOException {
        InputStream stream = getClass()
                .getResourceAsStream(LABELS_RESOURCE);

        if (stream == null) {
            throw new IllegalStateException(
                    "labels.txt not found in resources. " +
                            "Make sure it is in src/main/resources/"
            );
        }

        List<String> labels = new ArrayList<>();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    labels.add(line);
                }
            }
        }

        return labels;
    }

    /**
     * Extracts the ONNX model from the JAR resources to a
     * temporary file on disk. DJL's ModelZoo requires a file
     * path and cannot load models directly from an InputStream.
     *
     * The temp file is deleted automatically when the JVM exits.
     *
     * @return Path to the extracted temporary ONNX file
     * @throws IOException if extraction fails
     */
    private Path extractModelToTemp() throws IOException {
        InputStream modelStream = getClass()
                .getResourceAsStream(MODEL_RESOURCE);

        if (modelStream == null) {
            throw new IllegalStateException(
                    "skin_model.onnx not found in resources. " +
                            "Make sure it is in src/main/resources/"
            );
        }

        // Create a temp file to hold the ONNX model
        java.io.File tempFile = java.io.File.createTempFile(
                "skin_model", ".onnx"
        );

        // Delete temp file when JVM exits
        tempFile.deleteOnExit();

        // Copy model bytes from resources to temp file
        try (java.io.FileOutputStream fos =
                     new java.io.FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int    bytesRead;
            while ((bytesRead = modelStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        System.out.println("SkinClassifier: model extracted to " +
                tempFile.getAbsolutePath() +
                " (" + tempFile.length() / (1024 * 1024) + " MB)");

        return tempFile.toPath();
    }

    /**
     * Returns the list of class label names in index order.
     * Used by the GUI to display class names next to confidence bars.
     *
     * @return unmodifiable list of class label strings
     */
    public List<String> getClassLabels() {
        return classLabels;
    }

    /**
     * Releases all DJL resources held by this classifier.
     * Must be called when the application closes to prevent
     * memory leaks. Called automatically if used in try-with-resources.
     */
    @Override
    public void close() {
        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }
        System.out.println("SkinClassifier: resources released.");
    }
}