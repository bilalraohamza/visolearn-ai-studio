package com.visolearn.utils;

import com.visolearn.SkinClassifier.PredictionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RiskAssessor}.
 *
 * <p>These tests are safety-critical: the risk level directly drives the
 * patient-facing safety message in the UI and in exported PDF reports.
 * Every classification boundary is covered, including exact edge values
 * (confidence == 60.0f) to guard against off-by-one errors in the
 * {@code > 60.0f} condition.</p>
 *
 * <h3>Class index mapping (from {@code SkinClassifier.CLASS_FULL_NAMES}):</h3>
 * <pre>
 *  0 – Actinic Keratosis      (AK)
 *  1 – Basal Cell Carcinoma   (BCC)  ← malignant
 *  2 – Benign Keratosis       (BKL)
 *  3 – Dermatofibroma         (DF)
 *  4 – Melanoma               (MEL)  ← malignant
 *  5 – Melanocytic Nevus      (NV)
 *  6 – Vascular Lesion        (VASC)
 * </pre>
 */
@DisplayName("RiskAssessor")
class RiskAssessorTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal {@link PredictionResult} with only the fields
     * {@link RiskAssessor} reads ({@code classIndex} and {@code confidence}).
     * Other fields are filled with neutral values.
     */
    private static PredictionResult result(int classIndex, float confidence) {
        float[] probs = new float[7];
        probs[classIndex] = confidence / 100.0f;
        return new PredictionResult(classIndex, "label", confidence, probs, 0L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Melanoma (class 4) — URGENT / MODERATE boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Melanoma at 90% confidence → URGENT")
    void melanoma_highConfidence_isUrgent() {
        assertEquals(RiskLevel.URGENT, RiskAssessor.assess(result(4, 90.0f)));
    }

    @Test
    @DisplayName("Melanoma just above threshold (60.1%) → URGENT")
    void melanoma_justAboveThreshold_isUrgent() {
        assertEquals(RiskLevel.URGENT, RiskAssessor.assess(result(4, 60.1f)));
    }

    @Test
    @DisplayName("Melanoma exactly at threshold (60.0%) → MODERATE (boundary: > not >=)")
    void melanoma_exactlyAtThreshold_isModerate() {
        // The condition is confidence > 60.0f, so 60.0 itself must NOT be URGENT
        assertEquals(RiskLevel.MODERATE, RiskAssessor.assess(result(4, 60.0f)));
    }

    @Test
    @DisplayName("Melanoma below threshold (59.9%) → MODERATE")
    void melanoma_lowConfidence_isModerate() {
        assertEquals(RiskLevel.MODERATE, RiskAssessor.assess(result(4, 59.9f)));
    }

    @Test
    @DisplayName("Melanoma at 0% confidence → MODERATE (always at least MODERATE)")
    void melanoma_zeroConfidence_isModerate() {
        assertEquals(RiskLevel.MODERATE, RiskAssessor.assess(result(4, 0.0f)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Basal Cell Carcinoma (class 1) — same URGENT / MODERATE boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BCC at 75% confidence → URGENT")
    void bcc_highConfidence_isUrgent() {
        assertEquals(RiskLevel.URGENT, RiskAssessor.assess(result(1, 75.0f)));
    }

    @Test
    @DisplayName("BCC just above threshold (60.1%) → URGENT")
    void bcc_justAboveThreshold_isUrgent() {
        assertEquals(RiskLevel.URGENT, RiskAssessor.assess(result(1, 60.1f)));
    }

    @Test
    @DisplayName("BCC exactly at threshold (60.0%) → MODERATE")
    void bcc_exactlyAtThreshold_isModerate() {
        assertEquals(RiskLevel.MODERATE, RiskAssessor.assess(result(1, 60.0f)));
    }

    @Test
    @DisplayName("BCC at 40% confidence → MODERATE")
    void bcc_lowConfidence_isModerate() {
        assertEquals(RiskLevel.MODERATE, RiskAssessor.assess(result(1, 40.0f)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actinic Keratosis (class 0) — MODERATE / LOW boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AK at 80% confidence → MODERATE")
    void ak_highConfidence_isModerate() {
        assertEquals(RiskLevel.MODERATE, RiskAssessor.assess(result(0, 80.0f)));
    }

    @Test
    @DisplayName("AK just above threshold (60.1%) → MODERATE")
    void ak_justAboveThreshold_isModerate() {
        assertEquals(RiskLevel.MODERATE, RiskAssessor.assess(result(0, 60.1f)));
    }

    @Test
    @DisplayName("AK exactly at threshold (60.0%) → LOW (boundary: > not >=)")
    void ak_exactlyAtThreshold_isLow() {
        // Same > 60.0f condition: 60.0 must not promote to MODERATE
        assertEquals(RiskLevel.LOW, RiskAssessor.assess(result(0, 60.0f)));
    }

    @Test
    @DisplayName("AK at 30% confidence → LOW")
    void ak_lowConfidence_isLow() {
        assertEquals(RiskLevel.LOW, RiskAssessor.assess(result(0, 30.0f)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Benign classes (2, 3, 5, 6) — always LOW regardless of confidence
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "classIndex={0}, confidence={1}% → LOW")
    @CsvSource({
        "2, 99.0",   // Benign Keratosis — max confidence
        "2,  0.0",   // Benign Keratosis — zero confidence
        "3, 95.0",   // Dermatofibroma
        "5, 88.0",   // Melanocytic Nevus
        "6, 72.0",   // Vascular Lesion
        "6,  0.0",   // Vascular Lesion — zero confidence
    })
    @DisplayName("Benign classes are always LOW")
    void benignClasses_alwaysLow(int classIndex, float confidence) {
        assertEquals(RiskLevel.LOW, RiskAssessor.assess(result(classIndex, confidence)),
                "classIndex=" + classIndex + " at " + confidence + "% should be LOW");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Boundary symmetry: MEL and BCC must behave identically
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MEL and BCC produce identical results at same confidence")
    void melanoma_and_bcc_symmetry() {
        float[] testConfidences = {0.0f, 30.0f, 60.0f, 60.1f, 80.0f, 100.0f};
        for (float conf : testConfidences) {
            assertEquals(
                    RiskAssessor.assess(result(4, conf)),
                    RiskAssessor.assess(result(1, conf)),
                    "MEL and BCC should give same RiskLevel at confidence=" + conf
            );
        }
    }
}
