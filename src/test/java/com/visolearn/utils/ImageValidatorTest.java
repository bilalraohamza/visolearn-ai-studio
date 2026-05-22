package com.visolearn.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ImageValidator}.
 *
 * <p>Each test targets one of the five validation checks in order:</p>
 * <ol>
 *   <li>Null / non-existent / unreadable file</li>
 *   <li>File size (too small, too large)</li>
 *   <li>Decodability</li>
 *   <li>Minimum pixel dimensions</li>
 *   <li>Extreme aspect ratio (warning, not failure)</li>
 * </ol>
 *
 * <p>Real image files are written with {@code @TempDir} using JPEG format.
 * JPEG files for even small images are reliably above 1 KB (the validator's
 * minimum file-size threshold), making tests hermetic and environment-independent.</p>
 */
@DisplayName("ImageValidator")
class ImageValidatorTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Check 1: null / missing / unreadable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null file → ERROR")
    void nullFile_isError() {
        var result = ImageValidator.validate(null);
        assertAll(
                () -> assertFalse(result.valid(),     "null should be invalid"),
                () -> assertFalse(result.isWarning(), "null should be ERROR not WARNING"),
                () -> assertNotNull(result.title()),
                () -> assertFalse(result.title().isBlank())
        );
    }

    @Test
    @DisplayName("non-existent file → ERROR")
    void missingFile_isError(@TempDir Path tmp) {
        File ghost = tmp.resolve("ghost.jpg").toFile();
        assertFalse(ImageValidator.validate(ghost).valid());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Check 2: file size
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("file below 1 KB → ERROR (too small)")
    void fileTooSmall_isError(@TempDir Path tmp) throws IOException {
        Path tiny = tmp.resolve("tiny.jpg");
        Files.write(tiny, new byte[512]); // 512 bytes < 1 KB
        assertFalse(ImageValidator.validate(tiny.toFile()).valid());
    }

    @Test
    @DisplayName("file exactly at 1 KB minimum boundary → passes size check")
    void fileAtMinBoundary_passesSizeCheck(@TempDir Path tmp) throws IOException {
        // 1024 bytes passes the size check; it will fail the decodability check
        // because it is not a real image, but that is a later check.
        Path minFile = tmp.resolve("min.jpg");
        Files.write(minFile, new byte[1024]);
        // The result may be ERROR (not decodable) but NOT because of file size.
        var result = ImageValidator.validate(minFile.toFile());
        assertFalse(result.title().toLowerCase().contains("too small"),
                "1 KB file should not be rejected for being 'too small'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Check 3: decodability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("file with valid size but non-image content → ERROR (cannot decode)")
    void nonImageContent_isError(@TempDir Path tmp) throws IOException {
        Path junk = tmp.resolve("junk.jpg");
        byte[] garbage = new byte[2048];
        for (int i = 0; i < garbage.length; i++) garbage[i] = (byte)(i % 128);
        Files.write(junk, garbage);
        assertFalse(ImageValidator.validate(junk.toFile()).valid(),
                "Non-image content should fail validation");
    }

    @Test
    @DisplayName("valid 200x200 JPEG → OK (all checks pass)")
    void validJpeg_isOk(@TempDir Path tmp) throws IOException {
        Path jpg = tmp.resolve("valid.jpg");
        Files.write(jpg, makeJpeg(200, 200));
        var result = ImageValidator.validate(jpg.toFile());
        assertTrue(result.valid(), "Valid 200x200 JPEG should pass: " + result.title());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Check 4: minimum pixel dimensions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10x10 JPEG (padded to >1KB) → ERROR (image too small)")
    void imageTooSmall_isError(@TempDir Path tmp) throws IOException {
        // 10x10 is a valid JPEG but below MIN_DIMENSION=50.
        // Padded to 2 KB so the size check does not fire first.
        Path jpg = tmp.resolve("tiny_image.jpg");
        Files.write(jpg, makeJpegPaddedTo2KB(10, 10));
        var result = ImageValidator.validate(jpg.toFile());
        assertFalse(result.valid(), "10x10 image should fail the dimension check");
        assertTrue(result.title().toLowerCase().contains("small"),
                "Title should mention 'small', got: " + result.title());
    }

    @Test
    @DisplayName("200x200 JPEG → OK (above 50px minimum)")
    void imageAtMinDimension_isOk(@TempDir Path tmp) throws IOException {
        Path jpg = tmp.resolve("above_min.jpg");
        Files.write(jpg, makeJpeg(200, 200));
        assertTrue(ImageValidator.validate(jpg.toFile()).valid(),
                "200x200 image should pass (above MIN_DIMENSION=50)");
    }

    @Test
    @DisplayName("49x49 JPEG (padded to >1KB) → ERROR (just below 50px minimum)")
    void imageJustBelowMinDimension_isError(@TempDir Path tmp) throws IOException {
        Path jpg = tmp.resolve("fortynine.jpg");
        Files.write(jpg, makeJpegPaddedTo2KB(49, 49));
        assertFalse(ImageValidator.validate(jpg.toFile()).valid(),
                "49x49 image should fail the dimension check");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Check 5: aspect ratio — WARNING (not ERROR, inference still allowed)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("800x100 JPEG (ratio=8.0, above 4.0 threshold) → WARNING")
    void extremeAspectRatio_isWarning(@TempDir Path tmp) throws IOException {
        Path jpg = tmp.resolve("wide.jpg");
        Files.write(jpg, makeJpeg(800, 100));
        var result = ImageValidator.validate(jpg.toFile());
        assertTrue(result.valid(),
                "Extreme aspect ratio is a warning — inference should still be allowed");
        assertTrue(result.isWarning(), "Result should be WARNING not ERROR");
    }

    @Test
    @DisplayName("400x100 JPEG (ratio=4.0 exactly) → OK (boundary is >, not >=)")
    void aspectRatioExactly4_isOk(@TempDir Path tmp) throws IOException {
        // 400 / 100 = 4.0; condition is ratio > 4.0, so this must be OK
        Path jpg = tmp.resolve("ratio4.jpg");
        Files.write(jpg, makeJpeg(400, 100));
        var result = ImageValidator.validate(jpg.toFile());
        assertTrue(result.valid(), "Ratio 4.0 should be valid: " + result.title());
        assertFalse(result.isWarning(), "Ratio == 4.0 should be OK (boundary is >, not >=)");
    }

    @Test
    @DisplayName("400x400 square JPEG → OK, no warning")
    void squareImage_isOk(@TempDir Path tmp) throws IOException {
        Path jpg = tmp.resolve("square.jpg");
        Files.write(jpg, makeJpeg(400, 400));
        var result = ImageValidator.validate(jpg.toFile());
        assertTrue(result.valid());
        assertFalse(result.isWarning());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ValidationResult contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ERROR result: valid()=false, isWarning()=false")
    void errorResult_contract() {
        var error = ImageValidator.validate(null);
        assertFalse(error.valid(),     "ERROR must not be valid()");
        assertFalse(error.isWarning(), "ERROR must not be isWarning()");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Image factory helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a noise-filled JPEG of the given dimensions.
     * JPEG files for any image ≥ 50×50 are reliably > 1 KB.
     * Random noise prevents run-length compression from shrinking small images.
     */
    private static byte[] makeJpeg(int width, int height) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rng = new Random(42); // fixed seed for reproducibility
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rng.nextInt(0xFFFFFF));
            }
        }
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    /**
     * Creates a JPEG with the given small dimensions and pads the raw bytes
     * to 2 KB so the file-size check does not fire before the dimension check.
     * JPEG readers stop at EOI marker, so the decoded image still has the
     * original pixel dimensions.
     */
    private static byte[] makeJpegPaddedTo2KB(int width, int height)
            throws IOException {
        byte[] jpeg = makeJpeg(width, height);
        if (jpeg.length >= 2048) return jpeg;
        byte[] padded = new byte[2048];
        System.arraycopy(jpeg, 0, padded, 0, jpeg.length);
        return padded;
    }
}
