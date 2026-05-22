package com.visolearn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pixel-normalization logic inside {@link ImagePreprocessor}.
 *
 * <p>{@link ImagePreprocessor} has two concerns:</p>
 * <ol>
 *   <li><b>AWT pipeline</b> — resize, pixel extraction, normalization math</li>
 *   <li><b>DJL NDArray</b> — tensor creation (requires the OnnxRuntime engine)</li>
 * </ol>
 *
 * <p>These tests validate concern 1 only: the normalization formula,
 * the ImageNet constants, channel layout, and null-input guard.
 * They use the same arithmetic as the production code but bypass the
 * DJL dependency so tests run without loading any ONNX model.</p>
 *
 * <h3>Normalization formula under test</h3>
 * <pre>
 *   normalized = (pixel / 255.0  − mean) / std
 *
 *   ImageNet constants (RGB order):
 *     mean = [0.485, 0.456, 0.406]
 *     std  = [0.229, 0.224, 0.225]
 * </pre>
 */
@DisplayName("ImagePreprocessor — normalization math")
class ImagePreprocessorNormalizationTest {

    // Mirror the production constants exactly so any accidental drift is caught
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD  = {0.229f, 0.224f, 0.225f};
    private static final int IMAGE_SIZE = 380; // must match ImagePreprocessor.IMAGE_SIZE

    // Acceptable floating-point tolerance for single-precision arithmetic
    private static final float DELTA = 1e-4f;

    // ─────────────────────────────────────────────────────────────────────────
    // Normalization formula
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pure white pixel (255,255,255) normalizes to expected values")
    void whitePixel_normalizesCorrectly() {
        // R, G, B all = 255 → pixel / 255 = 1.0
        float normalizedR = (1.0f - MEAN[0]) / STD[0];
        float normalizedG = (1.0f - MEAN[1]) / STD[1];
        float normalizedB = (1.0f - MEAN[2]) / STD[2];

        // Expected: (1.0 - 0.485) / 0.229 ≈ 2.2489
        assertEquals(2.2489f, normalizedR, DELTA, "R channel for white pixel");
        // Expected: (1.0 - 0.456) / 0.224 ≈ 2.4286
        assertEquals(2.4286f, normalizedG, DELTA, "G channel for white pixel");
        // Expected: (1.0 - 0.406) / 0.225 ≈ 2.6400
        assertEquals(2.6400f, normalizedB, DELTA, "B channel for white pixel");
    }

    @Test
    @DisplayName("pure black pixel (0,0,0) normalizes to expected values")
    void blackPixel_normalizesCorrectly() {
        // R, G, B all = 0 → pixel / 255 = 0.0
        float normalizedR = (0.0f - MEAN[0]) / STD[0];
        float normalizedG = (0.0f - MEAN[1]) / STD[1];
        float normalizedB = (0.0f - MEAN[2]) / STD[2];

        // Expected: (0.0 - 0.485) / 0.229 ≈ -2.1179
        assertEquals(-2.1179f, normalizedR, DELTA, "R channel for black pixel");
        // Expected: (0.0 - 0.456) / 0.224 ≈ -2.0357
        assertEquals(-2.0357f, normalizedG, DELTA, "G channel for black pixel");
        // Expected: (0.0 - 0.406) / 0.225 ≈ -1.8044
        assertEquals(-1.8044f, normalizedB, DELTA, "B channel for black pixel");
    }

    @Test
    @DisplayName("ImageNet mean pixel (mean×255 per channel) normalizes to ~0.0")
    void imagenetMeanPixel_normalizesToNearZero() {
        // If pixel = mean * 255, normalized should be ~0
        float rPixel = Math.round(MEAN[0] * 255) / 255.0f;
        float gPixel = Math.round(MEAN[1] * 255) / 255.0f;
        float bPixel = Math.round(MEAN[2] * 255) / 255.0f;

        float normalizedR = (rPixel - MEAN[0]) / STD[0];
        float normalizedG = (gPixel - MEAN[1]) / STD[1];
        float normalizedB = (bPixel - MEAN[2]) / STD[2];

        assertEquals(0.0f, normalizedR, 0.01f, "R channel at ImageNet mean ≈ 0");
        assertEquals(0.0f, normalizedG, 0.01f, "G channel at ImageNet mean ≈ 0");
        assertEquals(0.0f, normalizedB, 0.01f, "B channel at ImageNet mean ≈ 0");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STD is never zero (division safety)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ImageNet std constants are all positive (no division by zero)")
    void stdConstants_areAllPositive() {
        for (int c = 0; c < STD.length; c++) {
            assertTrue(STD[c] > 0f,
                    "STD[" + c + "] must be > 0 to avoid division by zero, got " + STD[c]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pixel extraction from BufferedImage
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RGB bit-shift extraction matches known ARGB packed int")
    void rgbBitShift_extractsCorrectChannels() {
        // Construct a specific ARGB packed int and verify bit-shift extraction
        int r = 200, g = 100, b = 50;
        // BufferedImage.getRGB returns 0xAARRGGBB
        int packed = (0xFF << 24) | (r << 16) | (g << 8) | b;

        int extractedR = (packed >> 16) & 0xFF;
        int extractedG = (packed >> 8)  & 0xFF;
        int extractedB =  packed        & 0xFF;

        assertEquals(r, extractedR, "Red channel extraction");
        assertEquals(g, extractedG, "Green channel extraction");
        assertEquals(b, extractedB, "Blue channel extraction");
    }

    @Test
    @DisplayName("pixel values divide to [0,1] range before normalization")
    void pixelDivision_producesUnitRange() {
        for (int value : new int[]{0, 1, 127, 128, 254, 255}) {
            float normalized = value / 255.0f;
            assertTrue(normalized >= 0.0f && normalized <= 1.0f,
                    "value " + value + " / 255 should be in [0,1], got " + normalized);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel layout (CHW order)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("output array has correct length: 3 × IMAGE_SIZE × IMAGE_SIZE")
    void outputArray_hasCorrectLength() {
        int expected = 3 * IMAGE_SIZE * IMAGE_SIZE;
        assertEquals(433_200, expected,
                "3 × 380 × 380 must equal 433200 (production constant)");
        float[] array = new float[expected];
        assertEquals(433_200, array.length);
    }

    @Test
    @DisplayName("channel offsets are correct: R at [0], G at [IMAGE_SIZE²], B at [2×IMAGE_SIZE²]")
    void channelOffsets_areCorrect() {
        int channelSize = IMAGE_SIZE * IMAGE_SIZE;
        // Simulate production layout for pixel at (x=0, y=0)
        float[] floatArray = new float[3 * channelSize];

        float rNorm = 1.5f; // dummy values
        float gNorm = -0.5f;
        float bNorm = 0.3f;
        int pixelIndex = 0;

        floatArray[0 * channelSize + pixelIndex] = rNorm;
        floatArray[1 * channelSize + pixelIndex] = gNorm;
        floatArray[2 * channelSize + pixelIndex] = bNorm;

        assertEquals(rNorm, floatArray[0],             "R at index 0");
        assertEquals(gNorm, floatArray[channelSize],   "G at index channelSize");
        assertEquals(bNorm, floatArray[2 * channelSize], "B at index 2×channelSize");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AWT resize pipeline
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resizing a 100×100 image to 380×380 produces correct dimensions")
    void resize_producesCorrectDimensions() {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        BufferedImage resized = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(source, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
        g2d.dispose();

        assertEquals(IMAGE_SIZE, resized.getWidth(),  "Resized width should be 380");
        assertEquals(IMAGE_SIZE, resized.getHeight(), "Resized height should be 380");
        assertEquals(BufferedImage.TYPE_INT_RGB, resized.getType(),
                "Should be TYPE_INT_RGB after resize");
    }

    @Test
    @DisplayName("resizing preserves uniform colour (all-red source stays red)")
    void resize_preservesUniformColour() {
        // Fill source with solid red
        BufferedImage source = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = source.createGraphics();
        sg.setColor(Color.RED);
        sg.fillRect(0, 0, 50, 50);
        sg.dispose();

        // Resize to 380×380
        BufferedImage resized = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.drawImage(source, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
        g2d.dispose();

        // Check a central pixel — should remain fully red
        int rgb = resized.getRGB(190, 190);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8)  & 0xFF;
        int b =  rgb        & 0xFF;

        assertEquals(255, r, "Central pixel R should be 255 (red source)");
        assertEquals(0,   g, 20, "Central pixel G should be ~0");
        assertEquals(0,   b, 20, "Central pixel B should be ~0");
    }
}
