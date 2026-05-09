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
 * @author Rao Hamza Bilal
 * @version 2.1 (Ensemble + Occlusion Support)
 */
public class SkinClassifier implements AutoCloseable {

    private static final String EFFNET_RESOURCE = "/efficientnet_b4_v3.onnx";
    private static final String DENSENET_RESOURCE = "/densenet169_v2.onnx";
    private static final String LABELS_RESOURCE = "/labels.txt";
    private static final int NUM_CLASSES = 7;

    private ZooModel<NDList, NDList> effNetModel;
    private Predictor<NDList, NDList> effNetPredictor;

    private ZooModel<NDList, NDList> denseNetModel;
    private Predictor<NDList, NDList> denseNetPredictor;

    private final ImagePreprocessor preprocessor;
    private List<String> classLabels;

    public static class PredictionResult {
        public final int classIndex;
        public final String className;
        public final float confidence;
        public final float[] allProbabilities;
        public final long inferenceTimeMs;

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

    public SkinClassifier() {
        this.preprocessor = new ImagePreprocessor();
    }

    public void initialize() throws Exception {
        System.out.println("SkinClassifier: loading ensemble models...");
        classLabels = loadClassLabels();

        // Load EfficientNet
        Path effNetPath = extractModelToTemp(EFFNET_RESOURCE, "effnet");
        Criteria<NDList, NDList> effNetCriteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(effNetPath)
                .optEngine("OnnxRuntime")
                .optDevice(Device.cpu())
                .build();
        effNetModel = ModelZoo.loadModel(effNetCriteria);
        effNetPredictor = effNetModel.newPredictor();

        // Load DenseNet
        Path denseNetPath = extractModelToTemp(DENSENET_RESOURCE, "densenet");
        Criteria<NDList, NDList> denseNetCriteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(denseNetPath)
                .optEngine("OnnxRuntime")
                .optDevice(Device.cpu())
                .build();
        denseNetModel = ModelZoo.loadModel(denseNetCriteria);
        denseNetPredictor = denseNetModel.newPredictor();

        System.out.println("SkinClassifier: Both models loaded successfully.");
    }

    /** Primary method for standard file predictions */
    public PredictionResult predict(Path imagePath) throws Exception {
        long startTime = System.currentTimeMillis();
        try (NDManager predictionManager = NDManager.newBaseManager()) {
            NDArray inputTensor = preprocessor.preprocessFromFile(predictionManager, imagePath);
            return executeEnsemble(inputTensor, startTime);
        }
    }

    /** Method required by GradCamRenderer for in-memory BufferedImage occlusion passes */
    public PredictionResult predictFromImage(BufferedImage image) throws Exception {
        long startTime = System.currentTimeMillis();
        try (NDManager predictionManager = NDManager.newBaseManager()) {
            NDArray inputTensor = preprocessor.preprocessFromImage(predictionManager, image);
            return executeEnsemble(inputTensor, startTime);
        }
    }

    /** Core ensemble logic shared by both predict methods */
    private PredictionResult executeEnsemble(NDArray inputTensor, long startTime) throws Exception {
        NDList inputList = new NDList(inputTensor);

        // Run inference on both models
        NDList effNetOutput = effNetPredictor.predict(inputList);
        NDList denseNetOutput = denseNetPredictor.predict(inputList);

        float[] effNetLogits = effNetOutput.get(0).toFloatArray();
        float[] denseNetLogits = denseNetOutput.get(0).toFloatArray();

        // Average the raw logits
        float[] averagedLogits = new float[NUM_CLASSES];
        float maxLogit = -Float.MAX_VALUE;

        for (int i = 0; i < NUM_CLASSES; i++) {
            averagedLogits[i] = (effNetLogits[i] + denseNetLogits[i]) / 2.0f;
            if (averagedLogits[i] > maxLogit) {
                maxLogit = averagedLogits[i];
            }
        }

        // Apply softmax
        float[] probabilities = new float[NUM_CLASSES];
        float sumExp = 0f;
        for (int i = 0; i < NUM_CLASSES; i++) {
            probabilities[i] = (float) Math.exp(averagedLogits[i] - maxLogit);
            sumExp += probabilities[i];
        }
        for (int i = 0; i < NUM_CLASSES; i++) {
            probabilities[i] /= sumExp;
        }

        // Find highest probability class
        int bestIndex = 0;
        float bestProb = probabilities[0];
        for (int i = 1; i < NUM_CLASSES; i++) {
            if (probabilities[i] > bestProb) {
                bestProb = probabilities[i];
                bestIndex = i;
            }
        }

        long inferenceTime = System.currentTimeMillis() - startTime;
        String className = classLabels.get(bestIndex);
        float confidence = bestProb * 100f;

        return new PredictionResult(bestIndex, className, confidence, probabilities, inferenceTime);
    }

    private List<String> loadClassLabels() throws IOException {
        InputStream stream = getClass().getResourceAsStream(LABELS_RESOURCE);
        if (stream == null) throw new IllegalStateException("labels.txt not found in resources.");
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) labels.add(line.trim());
            }
        }
        return labels;
    }

    private Path extractModelToTemp(String resourcePath, String prefix) throws IOException {
        InputStream modelStream = getClass().getResourceAsStream(resourcePath);
        if (modelStream == null) throw new IllegalStateException(resourcePath + " not found.");
        java.io.File tempFile = java.io.File.createTempFile(prefix + "_model", ".onnx");
        tempFile.deleteOnExit();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = modelStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
        return tempFile.toPath();
    }

    public List<String> getClassLabels() {
        return classLabels;
    }

    @Override
    public void close() {
        if (effNetPredictor != null) effNetPredictor.close();
        if (effNetModel != null) effNetModel.close();
        if (denseNetPredictor != null) denseNetPredictor.close();
        if (denseNetModel != null) denseNetModel.close();
        System.out.println("SkinClassifier: All ensemble resources released.");
    }
}