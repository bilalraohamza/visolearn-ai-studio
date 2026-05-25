package com.visolearn.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ImageValidator}.
 *
 * All tests use real temp files written to a JUnit {@link TempDir} so
 * the validator's ImageIO.read() path is exercised genuinely.
 * No mocking is needed — ImageValidator has zero external dependencies.
 *
 * Boundary values tested match the private constants in ImageValidator:
 *   MIN_DIMENSION       = 50 px
 *   MIN_FILE_SIZE_BYTES = 1 024 bytes
 *   MAX_ASPECT_RATIO    = 4.0  (condition is strictly > 4.0)
 */
@DisplayName("ImageValidator")
class ImageValidatorTest {

    @TempDir
    Path tempDir;

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Writes a solid-color PNG of the given dimensions to the temp directory. */
    private File createPng(int width, int height, String name) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.CYAN);
        g.fillRect(0, 0, width, height);
        g.dispose();
        File f = tempDir.resolve(name).toFile();
        ImageIO.write(img, "png", f);
        return f;
    }

    /** Writes {@code sizeBytes} of non-image bytes to the temp directory. */
    private File createBinaryFile(int sizeBytes, String name) throws IOException {
        File f = tempDir.resolve(name).toFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            byte[] data = new byte[sizeBytes];
            for (int i = 0; i < sizeBytes; i++) data[i] = (byte) (i % 127);
            fos.write(data);
        }
        return f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Null input
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null file → failure, not a warning")
    void nullFile_isFailure() {
        var r = ImageValidator.validate(null);
        assertFalse(r.valid());
        assertFalse(r.isWarning());
        assertFalse(r.title().isBlank());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File existence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("non-existent file → failure")
    void nonExistentFile_isFailure() {
        File ghost = tempDir.resolve("does_not_exist.png").toFile();
        var r = ImageValidator.validate(ghost);
        assertFalse(r.valid());
        assertTrue(r.title().toLowerCase().contains("not found"),
                "Expected 'not found' in title but got: " + r.title());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File size — lower boundary (1 024 bytes)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("512-byte file (below 1 KB minimum) → failure with 'small' in title")
    void fileTooSmall_512bytes_isFailure() throws IOException {
        File f = createBinaryFile(512, "tiny.bin");
        var r = ImageValidator.validate(f);
        assertFalse(r.valid());
        assertTrue(r.title().toLowerCase().contains("small"),
                "Expected 'small' in title but got: " + r.title());
    }

    @Test
    @DisplayName("1023-byte file (one byte below minimum) → failure")
    void fileTooSmall_1023bytes_isFailure() throws IOException {
        File f = createBinaryFile(1023, "near.bin");
        var r = ImageValidator.validate(f);
        assertFalse(r.valid());
    }

    @Test
    @DisplayName("1024-byte file (exactly at minimum) passes size check (may fail decode)")
    void fileAtMinimumBoundary_passesSizeGate() throws IOException {
        // 1024 bytes passes the size check but random bytes fail decode.
        // We verify the title is NOT about file size.
        File f = createBinaryFile(1024, "boundary.bin");
        var r = ImageValidator.validate(f);
        assertFalse(r.title().toLowerCase().contains("small"),
                "A 1024-byte file must pass the size gate; got title: " + r.title());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Decodability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("file with random bytes (not a valid image) → failure")
    void corruptFile_isFailure() throws IOException {
        File f = createBinaryFile(8192, "corrupt.png");
        var r = ImageValidator.validate(f);
        assertFalse(r.valid());
    }

    @Test
    @DisplayName("corrupt file title mentions decode or format problem")
    void corruptFile_titleMentionsDecode() throws IOException {
        File f = createBinaryFile(8192, "corrupt2.png");
        var r = ImageValidator.validate(f);
        String title = r.title().toLowerCase();
        assertTrue(title.contains("decode") || title.contains("format") || title.contains("unrecognised"),
                "Expected decode/format message but got: " + r.title());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Minimum pixel dimensions (threshold = 50 px, condition: < 50)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("49x49 image (below 50px minimum) → failure")
    void imageDimensions_49x49_isFailure() throws IOException {
        File f = createPng(49, 49, "tiny49.png");
        var r = ImageValidator.validate(f);
        assertFalse(r.valid());
        assertTrue(r.title().toLowerCase().contains("small"),
                "Expected 'small' in title but got: " + r.title());
    }

    @Test
    @DisplayName("49x200 image (one axis below minimum) → failure")
    void imageDimensions_oneAxisTooSmall_isFailure() throws IOException {
        File f = createPng(49, 200, "narrow.png");
        var r = ImageValidator.validate(f);
        assertFalse(r.valid());
    }

    @Test
    @DisplayName("200x49 image (height below minimum) → failure")
    void imageDimensions_heightTooSmall_isFailure() throws IOException {
        File f = createPng(200, 49, "short.png");
        var r = ImageValidator.validate(f);
        assertFalse(r.valid());
    }

    @Test
    @DisplayName("50x50 image (exactly at minimum boundary) → valid")
    void imageDimensions_50x50_isValid() throws IOException {
        // condition is < 50, so exactly 50 must pass
        File f = createPng(50, 50, "min50.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid(),
                "A 50x50 image must pass (condition is < 50, not <= 50)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Aspect ratio (threshold = 4.0, condition: strictly > 4.0)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("800x200 image (ratio exactly 4.0) → valid, NOT a warning")
    void aspectRatio_exactly4_isNotWarning() throws IOException {
        // ratio = 800/200 = 4.0 — condition is > 4.0, so 4.0 must NOT trigger warning
        File f = createPng(800, 200, "ratio4.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid());
        assertFalse(r.isWarning(),
                "Aspect ratio of exactly 4.0 must not be a warning (threshold is > 4.0)");
    }

    @Test
    @DisplayName("801x200 image (ratio 4.005, just above 4.0) → warning, still valid")
    void aspectRatio_justAbove4_isWarning() throws IOException {
        File f = createPng(801, 200, "ratio4plus.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid(),   "Warning must still allow inference (valid = true)");
        assertTrue(r.isWarning(), "Ratio > 4.0 must produce a warning");
    }

    @Test
    @DisplayName("1000x100 image (10:1 ratio) → warning, still valid")
    void aspectRatio_extreme_isWarning() throws IOException {
        File f = createPng(1000, 100, "extreme.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid());
        assertTrue(r.isWarning());
    }

    @Test
    @DisplayName("100x1000 portrait image (1:10 ratio) → warning (ratio check is symmetric)")
    void aspectRatio_extremePortrait_isWarning() throws IOException {
        File f = createPng(100, 1000, "portrait.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid());
        assertTrue(r.isWarning());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("380x380 dermoscopy-sized image → ok (valid, not warning)")
    void validSquareImage_isOk() throws IOException {
        File f = createPng(380, 380, "dermo.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid());
        assertFalse(r.isWarning());
    }

    @Test
    @DisplayName("1024x768 standard photo → ok")
    void validPhotoSized_isOk() throws IOException {
        File f = createPng(1024, 768, "photo.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid());
        assertFalse(r.isWarning());
    }

    @Test
    @DisplayName("ok result has empty title and detail")
    void okResult_hasBlankTitleAndDetail() throws IOException {
        File f = createPng(200, 200, "ok.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid());
        assertEquals("", r.title());
        assertEquals("", r.detail());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("failure: valid()=false, isWarning()=false, title not blank")
    void failureResult_contractHolds() {
        var r = ImageValidator.validate(null);
        assertFalse(r.valid());
        assertFalse(r.isWarning());
        assertFalse(r.title().isBlank());
        assertFalse(r.detail().isBlank());
    }

    @Test
    @DisplayName("warning: valid()=true, isWarning()=true, title not blank")
    void warningResult_contractHolds() throws IOException {
        File f = createPng(500, 100, "wide.png");
        var r = ImageValidator.validate(f);
        assertTrue(r.valid());
        assertTrue(r.isWarning());
        assertFalse(r.title().isBlank());
        assertFalse(r.detail().isBlank());
    }
}