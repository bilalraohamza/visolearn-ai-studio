package com.visolearn;

import com.visolearn.SkinClassifier.PredictionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ensemble math inside SkinClassifier.
 *
 * No ONNX models are loaded. The algorithm from executeEnsemble() is
 * replicated here as a pure Java function and tested in isolation.
 * This lets CI run these tests instantly without the 116MB model files.
 *
 * What is tested:
 * - Numerically stable softmax correctness
 * - Probability sum and range invariants
 * - Logit averaging between two model outputs
 * - Argmax / winner selection
 * - Confidence percentage range
 * - CLASS_FULL_NAMES constant integrity
 */
@DisplayName("SkinClassifier — ensemble math")
class SkinClassifierEnsembleTest {

    private static final int NUM_CLASSES = 7;
    private static final float TOLERANCE = 1e-4f;

    // ─────────────────────────────────────────────────────────────────────────
    // Replica of executeEnsemble() math — pure Java, no DJL, no ONNX
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Replicates the exact algorithm from SkinClassifier.executeEnsemble().
     * Any change to that method must also be reflected here so tests stay honest.
     *
     * Steps:
     * 1. Average logits from both models
     * 2. Numerically stable softmax (subtract max before exp)
     * 3. Argmax to find winning class
     * 4. Convert winning probability to confidence percentage
     */
    private static PredictionResult ensemble(float[] effLogits, float[] denseLogits) {
        // Step 1 — logit-space averaging
        float[] avg = new float[NUM_CLASSES];
        float maxLogit = -Float.MAX_VALUE;
        for (int i = 0; i < NUM_CLASSES; i++) {
            avg[i] = (effLogits[i] + denseLogits[i]) / 2.0f;
            if (avg[i] > maxLogit)
                maxLogit = avg[i];
        }

        // Step 2 — numerically stable softmax
        float[] probs = new float[NUM_CLASSES];
        float sumExp = 0.0f;
        for (int i = 0; i < NUM_CLASSES; i++) {
            probs[i] = (float) Math.exp(avg[i] - maxLogit);
            sumExp += probs[i];
        }
        for (int i = 0; i < NUM_CLASSES; i++)
            probs[i] /= sumExp;

        // Step 3 — argmax
        int bestIdx = 0;
        float bestP = probs[0];
        for (int i = 1; i < NUM_CLASSES; i++) {
            if (probs[i] > bestP) {
                bestP = probs[i];
                bestIdx = i;
            }
        }

        float confidence = bestP * 100.0f;
        return new PredictionResult(
                bestIdx,
                SkinClassifier.CLASS_FULL_NAMES[bestIdx],
                confidence, probs, 0L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Softmax invariants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("softmax probabilities sum to 1.0 within floating-point tolerance")
    void softmax_probabilitiesSumToOne() {
        float[] logits = { 1.2f, 0.5f, -0.3f, 2.1f, 0.8f, -1.0f, 0.3f };
        float sum = 0f;
        for (float p : ensemble(logits, logits).allProbabilities)
            sum += p;
        assertEquals(1.0f, sum, TOLERANCE);
    }

    @Test
    @DisplayName("all probabilities are in [0, 1]")
    void softmax_allProbabilitiesInValidRange() {
        float[] logits = { 3.0f, -2.0f, 1.5f, 0.0f, -0.5f, 2.0f, 1.0f };
        for (float p : ensemble(logits, logits).allProbabilities) {
            assertTrue(p >= 0.0f, "Probability below 0: " + p);
            assertTrue(p <= 1.0f, "Probability above 1: " + p);
        }
    }

    @Test
    @DisplayName("uniform logits produce equal probabilities (~1/7 each)")
    void softmax_uniformLogits_equalProbabilities() {
        float[] uniform = { 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f };
        float expected = 1.0f / NUM_CLASSES;
        for (float p : ensemble(uniform, uniform).allProbabilities) {
            assertEquals(expected, p, TOLERANCE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Numerical stability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("large logits (>88) do not produce Infinity or NaN")
    void softmax_largeLogits_noOverflow() {
        float[] large = { 100.0f, 95.0f, 90.0f, 85.0f, 80.0f, 75.0f, 70.0f };
        for (float p : ensemble(large, large).allProbabilities) {
            assertFalse(Float.isInfinite(p), "Infinity in probabilities");
            assertFalse(Float.isNaN(p), "NaN in probabilities");
        }
    }

    @Test
    @DisplayName("very negative logits do not produce NaN or negative probabilities")
    void softmax_negativeLogits_noUnderflow() {
        float[] neg = { -100.0f, -95.0f, -90.0f, -85.0f, -80.0f, -75.0f, -70.0f };
        for (float p : ensemble(neg, neg).allProbabilities) {
            assertFalse(Float.isNaN(p), "NaN in probabilities");
            assertFalse(Float.isInfinite(p), "Infinity in probabilities");
            assertTrue(p >= 0.0f, "Negative probability: " + p);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Argmax / winner selection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("class with the highest logit becomes the predicted class")
    void argmax_highestLogitWins() {
        // Class 4 (Melanoma) has logit 5.0 — all others much lower
        float[] logits = { 0.1f, 0.2f, 0.1f, 0.3f, 5.0f, 0.2f, 0.1f };
        PredictionResult r = ensemble(logits, logits);
        assertEquals(4, r.classIndex);
        assertEquals("Melanoma", r.className);
    }

    @Test
    @DisplayName("ensemble picks the class with the higher AVERAGE logit")
    void argmax_ensembleUsesAverageNotSingleModel() {
        // Model 1 prefers class 0 (logit 5.0), Model 2 prefers class 6 (logit 5.0)
        // Averages: class 0 = (5.0+0.1)/2 = 2.55, class 6 = (0.2+5.0)/2 = 2.6
        float[] eff = { 5.0f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.2f };
        float[] dense = { 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 5.0f };
        assertEquals(6, ensemble(eff, dense).classIndex,
                "Class 6 has the higher average logit and must win");
    }

    @Test
    @DisplayName("identical models produce same result as single model")
    void argmax_identicalModels_sameAsOne() {
        float[] logits = { 0.5f, 1.5f, 0.3f, 2.8f, 0.9f, 1.1f, 0.6f };
        // Class 3 (Dermatofibroma) has logit 2.8
        assertEquals(3, ensemble(logits, logits).classIndex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confidence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confidence equals allProbabilities[classIndex] * 100")
    void confidence_matchesWinnerProbability() {
        float[] logits = { 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 2.5f, 1.5f };
        PredictionResult r = ensemble(logits, logits);
        assertEquals(r.allProbabilities[r.classIndex] * 100.0f,
                r.confidence, TOLERANCE);
    }

    @Test
    @DisplayName("confidence is always in [0, 100] percentage range")
    void confidence_isInPercentageRange() {
        float[] logits = { 0.5f, 1.0f, 2.0f, 0.3f, 4.5f, 1.2f, 0.8f };
        PredictionResult r = ensemble(logits, logits);
        assertTrue(r.confidence >= 0.0f, "Confidence below 0");
        assertTrue(r.confidence <= 100.0f, "Confidence above 100");
    }

    @Test
    @DisplayName("uncertain model reduces confidence vs two confident models")
    void confidence_uncertainModelReducesScore() {
        float[] confident = { 0.0f, 0.0f, 10.0f, 0.0f, 0.0f, 0.0f, 0.0f };
        float[] uncertain = { 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f };

        float confidentScore = ensemble(confident, confident).confidence;
        float ensembleScore = ensemble(confident, uncertain).confidence;

        assertEquals(2, ensemble(confident, uncertain).classIndex,
                "Ensemble must still predict class 2");
        assertTrue(ensembleScore < confidentScore,
                "Ensemble with one uncertain model must have lower confidence");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLASS_FULL_NAMES constant integrity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CLASS_FULL_NAMES has exactly 7 entries")
    void classNames_hasSevenEntries() {
        assertEquals(7, SkinClassifier.CLASS_FULL_NAMES.length);
    }

    @ParameterizedTest(name = "index {0} → {1}")
    @CsvSource({
            "0, Actinic Keratosis",
            "1, Basal Cell Carcinoma",
            "2, Benign Keratosis",
            "3, Dermatofibroma",
            "4, Melanoma",
            "5, Melanocytic Nevus",
            "6, Vascular Lesion"
    })
    @DisplayName("Each index maps to the correct clinical class name")
    void classNames_indexMapping(int index, String expected) {
        assertEquals(expected, SkinClassifier.CLASS_FULL_NAMES[index]);
    }

    @Test
    @DisplayName("No class name is null or blank")
    void classNames_noneNullOrBlank() {
        for (String name : SkinClassifier.CLASS_FULL_NAMES) {
            assertNotNull(name);
            assertFalse(name.isBlank());
        }
    }
}