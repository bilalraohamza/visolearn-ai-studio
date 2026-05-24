package com.visolearn.service;

import com.visolearn.SkinClassifier;
import com.visolearn.data.PredictionDAO;
import com.visolearn.data.model.Prediction;
import com.visolearn.utils.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClassificationService}.
 *
 * <p>The DAO is replaced with an in-memory stub for every test so there is
 * no database, no disk I/O, and no JavaFX runtime required. Each stub
 * captures the arguments it received so assertions can verify that the
 * service constructs the correct domain object and delegates properly.</p>
 */
@DisplayName("ClassificationService")
class ClassificationServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Stubs
    // ─────────────────────────────────────────────────────────────────────────

    /** Captures every call to insertPrediction and returns a fixed id. */
    private static class StubPredictionDAO extends PredictionDAO {
        // insertPrediction captures
        Prediction   capturedPrediction;
        File         capturedImageFile;
        int          insertReturnId = 55;
        // deleteById captures
        int          lastDeletedId  = -1;
        // updateNotes captures
        int          lastNotesId    = -1;
        String       lastNotes      = "NOT_CALLED";
        // getPredictionsByPatientId
        int          lastLoadedPatientId = -1;

        @Override
        public int insertPrediction(Prediction prediction, File originalImage) {
            capturedPrediction = prediction;
            capturedImageFile  = originalImage;
            return insertReturnId;
        }

        @Override
        public List<Prediction> getPredictionsByPatientId(int patientId) {
            lastLoadedPatientId = patientId;
            return List.of(
                    new Prediction(1, patientId, "img.jpg", "Melanoma", 85.0, 120, "2024-01-01", null)
            );
        }

        @Override
        public void deleteById(int predictionId) {
            lastDeletedId = predictionId;
        }

        @Override
        public void updateNotes(int predictionId, String notes) {
            lastNotesId = predictionId;
            lastNotes   = notes;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor guard
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null DAO in constructor → IllegalArgumentException")
    void constructor_nullDao_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClassificationService(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // savePrediction()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("savePrediction() returns the DAO-assigned record id")
    void savePrediction_returnsDaoId() throws Exception {
        StubPredictionDAO stub = new StubPredictionDAO();
        stub.insertReturnId = 77;
        ClassificationService service = new ClassificationService(stub);

        File dummyFile = new File("dummy.jpg");
        int id = service.savePrediction(1, "Melanoma", 87.5, 142, dummyFile, "notes");

        assertEquals(77, id, "savePrediction() must return the DAO-assigned id");
    }

    @Test
    @DisplayName("savePrediction() passes correct field values to the DAO")
    void savePrediction_buildsPredictionCorrectly() throws Exception {
        StubPredictionDAO stub = new StubPredictionDAO();
        ClassificationService service = new ClassificationService(stub);
        File imageFile = new File("lesion.png");

        service.savePrediction(5, "Melanoma", 91.3, 200, imageFile, "Urgent review needed");

        Prediction p = stub.capturedPrediction;
        assertNotNull(p, "The service must pass a Prediction object to the DAO");
        assertEquals(5,                    p.patientId,      "patientId");
        assertEquals("Melanoma",           p.predictedClass, "predictedClass");
        assertEquals(91.3,                 p.confidence, 0.001, "confidence");
        assertEquals(200,                  p.inferenceTime,  "inferenceTime");
        assertEquals("Urgent review needed", p.notes,         "clinicianNotes");
        assertSame(imageFile, stub.capturedImageFile,          "imageFile");
    }

    @Test
    @DisplayName("savePrediction() with null notes passes null through")
    void savePrediction_nullNotes() throws Exception {
        StubPredictionDAO stub = new StubPredictionDAO();
        ClassificationService service = new ClassificationService(stub);

        service.savePrediction(1, "Nevus", 70.0, 100, new File("x.jpg"), null);

        assertNull(stub.capturedPrediction.notes,
                "null clinicianNotes must be passed to the DAO as null");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getPredictionsForPatient()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getPredictionsForPatient() forwards patientId to the DAO")
    void getPredictionsForPatient_delegatesToDao() throws SQLException {
        StubPredictionDAO stub = new StubPredictionDAO();
        ClassificationService service = new ClassificationService(stub);

        List<Prediction> result = service.getPredictionsForPatient(42);

        assertEquals(42, stub.lastLoadedPatientId,
                "getPredictionsForPatient() must forward the patientId");
        assertEquals(1, result.size());
        assertEquals("Melanoma", result.get(0).predictedClass);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // deletePrediction()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deletePrediction(id) forwards id to the DAO")
    void deletePrediction_delegatesToDao() throws SQLException {
        StubPredictionDAO stub = new StubPredictionDAO();
        ClassificationService service = new ClassificationService(stub);

        service.deletePrediction(13);

        assertEquals(13, stub.lastDeletedId,
                "deletePrediction() must forward the id to DAO.deleteById");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // updateNotes()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateNotes() forwards id and text to the DAO")
    void updateNotes_delegatesToDao() throws SQLException {
        StubPredictionDAO stub = new StubPredictionDAO();
        ClassificationService service = new ClassificationService(stub);

        service.updateNotes(8, "Follow up in 2 weeks");

        assertEquals(8,                       stub.lastNotesId);
        assertEquals("Follow up in 2 weeks",  stub.lastNotes);
    }

    @Test
    @DisplayName("updateNotes() with blank string passes null to the DAO (clears notes)")
    void updateNotes_blankIsNull() throws SQLException {
        StubPredictionDAO stub = new StubPredictionDAO();
        ClassificationService service = new ClassificationService(stub);

        service.updateNotes(3, "   ");

        assertNull(stub.lastNotes,
                "A blank notes string must be normalised to null before passing to the DAO");
    }

    @Test
    @DisplayName("updateNotes() with null passes null to the DAO")
    void updateNotes_nullIsNull() throws SQLException {
        StubPredictionDAO stub = new StubPredictionDAO();
        ClassificationService service = new ClassificationService(stub);

        service.updateNotes(3, null);

        assertNull(stub.lastNotes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // assessRisk() — delegates to RiskAssessor
    // ─────────────────────────────────────────────────────────────────────────

    private static SkinClassifier.PredictionResult result(int classIndex, float confidence) {
        float[] probs = new float[7];
        probs[classIndex] = confidence / 100.0f;
        return new SkinClassifier.PredictionResult(classIndex, "label", confidence, probs, 0L);
    }

    @Test
    @DisplayName("assessRisk() returns URGENT for high-confidence Melanoma (class 4)")
    void assessRisk_melanoma_highConfidence_isUrgent() {
        ClassificationService service = new ClassificationService(new StubPredictionDAO());
        assertEquals(RiskLevel.URGENT, service.assessRisk(result(4, 90.0f)));
    }

    @Test
    @DisplayName("assessRisk() returns MODERATE for low-confidence Melanoma (class 4)")
    void assessRisk_melanoma_lowConfidence_isModerate() {
        ClassificationService service = new ClassificationService(new StubPredictionDAO());
        assertEquals(RiskLevel.MODERATE, service.assessRisk(result(4, 50.0f)));
    }

    @Test
    @DisplayName("assessRisk() returns LOW for benign Nevus (class 5)")
    void assessRisk_nevus_isLow() {
        ClassificationService service = new ClassificationService(new StubPredictionDAO());
        assertEquals(RiskLevel.LOW, service.assessRisk(result(5, 95.0f)));
    }

    @ParameterizedTest(name = "classIndex={0}, confidence={1}% → LOW")
    @CsvSource({"2, 99.0", "3, 80.0", "5, 70.0", "6, 60.0"})
    @DisplayName("assessRisk() returns LOW for all benign classes regardless of confidence")
    void assessRisk_benignClasses_alwaysLow(int classIndex, float confidence) {
        ClassificationService service = new ClassificationService(new StubPredictionDAO());
        assertEquals(RiskLevel.LOW, service.assessRisk(result(classIndex, confidence)));
    }
}
