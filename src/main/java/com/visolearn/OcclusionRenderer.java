package com.visolearn;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * OcclusionRenderer generates occlusion sensitivity saliency maps
 * for skin lesion images classified by the EfficientNet-B4 model.
 *
 * <h2>Method: Occlusion Sensitivity Analysis</h2>
 * Reference: Zeiler and Fergus, "Visualizing and Understanding
 * Convolutional Networks", ECCV 2014, Section 3.2.
 *
 * Algorithm:
 * 1. Record the model's baseline confidence for the predicted class.
 * 2. Divide the 380x380 image into a 7x7 grid of patches (~54x54 px).
 * 3. For each patch, replace it with the ImageNet mean color (neutral
 * gray) and run a fresh inference pass.
 * 4. Compute the confidence drop: baseline - occluded_confidence.
 * Large drop = this region was important to the prediction.
 * No drop (or gain) = the model did not rely on this region.
 * 5. Apply ReLU to keep only regions that positively supported
 * the prediction.
 * 6. Normalize to [0, 1], apply Gaussian smoothing, then colorize
 * with a jet colormap (blue -> cyan -> yellow -> red).
 * 7. Blend the colorized saliency map over the original image.
 *
 * <h2>Why this is better than the previous version</h2>
 * The previous implementation used HSV hue matching against
 * hard-coded color signatures per class. It highlighted pixels
 * that matched the programmer's color assumptions, not what the
 * model actually learned. The result was decoupled from model behavior.
 *
 * Occlusion sensitivity is model-agnostic and genuinely measures
 * which image regions the model depends on. Hiding an important
 * region causes a measurable confidence drop. The heatmap reflects
 * actual model behavior.
 *
 * <h2>Cost</h2>
 * 7x7 = 49 forward passes, each ~100-160 ms on CPU.
 * Total: approximately 5-8 seconds. Runs on a background thread.
 *
 * @author Rao Hamza Bilal
 * @version 2.1 (renamed from GradCamRenderer — method is occlusion sensitivity, not Grad-CAM)
 */
public class OcclusionRenderer {

    /**
     * Callback interface for reporting per-pass progress.
     * Invoked after each of the 49 inference passes completes.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * @param completed number of passes completed so far
         * @param total     total number of passes (49 for a 7x7 grid)
         */
        void onProgress(int completed, int total);
    }

    /** Input/output size matching EfficientNet-B4's native resolution. */
    private static final int OUTPUT_SIZE = 380;

    /**
     * Grid dimension for occlusion patches.
     * 7x7 = 49 patches, each approximately 54x54 pixels.
     */
    private static final int GRID_SIZE = 7;

    /** Total inference passes = GRID_SIZE squared. */
    private static final int TOTAL_PATCHES = GRID_SIZE * GRID_SIZE;

    /**
     * Base composite opacity for the generated heatmap image.
     * The visible user preference is applied live to the JavaFX ImageView.
     */
    private static final float HEATMAP_OPACITY = 1.0f;

    /**
     * ImageNet mean color in integer pixel space, used as the
     * neutral fill for occluded patches.
     * R = round(0.485 * 255) = 124
     * G = round(0.456 * 255) = 116
     * B = round(0.406 * 255) = 104
     * After normalization these become 0.0 in all channels.
     */

    /** Gaussian blur kernel radius for smoothing the saliency map. */
    private static final int BLUR_RADIUS = 45;

    /** Classifier used to run inference on occluded image variants. */
    private final SkinClassifier classifier;

    /**
     * Constructs an OcclusionRenderer backed by the given classifier.
     *
     * @param classifier the shared SkinClassifier from MainApp
     */
    public OcclusionRenderer(SkinClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * Generates an occlusion sensitivity saliency map and blends it
     * onto the original image. Always call this on a background thread.
     *
     * @param originalImagePath path to the skin lesion image
     * @param result            the baseline prediction from SkinClassifier
     * @param callback          optional progress listener (may be null)
     * @return BufferedImage with saliency heatmap blended onto original
     * @throws Exception if image loading or any inference pass fails
     */
    public BufferedImage generateHeatmap(
            Path originalImagePath,
            SkinClassifier.PredictionResult result,
            ProgressCallback callback) throws Exception {

        try (SkinClassifier.PredictorPair pair =
                classifier.newPredictorPair()) {
            return generateHeatmapWithPair(
                originalImagePath, result, callback, pair);
        }
    }

    private BufferedImage generateHeatmapWithPair(
            Path originalImagePath,
            SkinClassifier.PredictionResult result,
            ProgressCallback callback,
            SkinClassifier.PredictorPair pair) throws Exception {

        // Load and resize original image to 380x380
        BufferedImage original = ImageIO.read(originalImagePath.toFile());
        if (original == null) {
            throw new IllegalArgumentException(
                    "Cannot read image: " + originalImagePath
            );
        }
        BufferedImage resized = resizeImage(original, OUTPUT_SIZE);

        // Run 49 occlusion inference passes
        float[][] importanceMap = computeOcclusionSensitivity(
                resized, result, callback, pair
        );

        // ReLU: keep only positive drops (regions that helped the prediction)
        applyReLU(importanceMap);

        // Normalize to [0, 1] for colormap input
        normalizeMap(importanceMap);

        // Smooth the blocky patch values into a continuous gradient
        float[][] smoothed = gaussianBlur(
                importanceMap, OUTPUT_SIZE, OUTPUT_SIZE, BLUR_RADIUS
        );

        // Re-normalize after blur (smoothing shifts the range)
        normalizeMap(smoothed);

        // Colorize and blend
        BufferedImage heatmapImage = colorizeHeatmap(smoothed);
        return blendImages(resized, heatmapImage, HEATMAP_OPACITY);
    }

    // ===== Core algorithm =====

    /**
     * Runs 49 inference passes with different patches occluded.
     * Records the confidence drop per patch into a 380x380 map.
     *
     * @param resized  380x380 image to analyze
     * @param baseline the original un-occluded prediction
     * @param callback optional progress listener (may be null)
     * @return 380x380 importance map with patch-resolution values
     * @throws Exception if any inference pass fails
     */
    private float[][] computeOcclusionSensitivity(
            BufferedImage resized,
            SkinClassifier.PredictionResult baseline,
            ProgressCallback callback,
            SkinClassifier.PredictorPair pair) throws Exception {

        float[][] importance = new float[OUTPUT_SIZE][OUTPUT_SIZE];

        float baselineConf = baseline.allProbabilities[baseline.classIndex];
        int   targetClass  = baseline.classIndex;
        int   baseStep     = OUTPUT_SIZE / GRID_SIZE;
        int   patchIdx     = 0;

        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {

                // Patch bounds — last row/col absorbs remainder pixels
                int x0 = col * baseStep;
                int y0 = row * baseStep;
                int x1 = (col == GRID_SIZE - 1) ? OUTPUT_SIZE : x0 + baseStep;
                int y1 = (row == GRID_SIZE - 1) ? OUTPUT_SIZE : y0 + baseStep;

                // Create occluded copy and fill the patch with mean color
                BufferedImage occluded = copyImage(resized);
                fillPatch(occluded, x0, y0, x1, y1);

                // Run inference on the occluded image using the dedicated pair
                SkinClassifier.PredictionResult occResult =
                        pair.predictFromImage(occluded);

                // Confidence drop for the target class
                float drop = baselineConf -
                        occResult.allProbabilities[targetClass];

                // Write drop value into every pixel in this patch
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        importance[y][x] = drop;
                    }
                }

                patchIdx++;
                if (callback != null) {
                    callback.onProgress(patchIdx, TOTAL_PATCHES);
                }
            }
        }

        return importance;
    }

    // ===== Image manipulation =====

    /**
     * Creates a deep pixel copy of the given BufferedImage.
     *
     * @param source image to copy
     * @return independent RGB copy
     */
    private BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g2d = copy.createGraphics();
        g2d.drawImage(source, 0, 0, null);
        g2d.dispose();
        return copy;
    }

    /**
     * Fills a rectangular patch with the ImageNet mean color.
     * After normalization this maps to 0.0 in all channels —
     * the center of the model's expected input distribution.
     *
     * @param image image to modify in place
     * @param x0    left edge (inclusive)
     * @param y0    top edge (inclusive)
     * @param x1    right edge (exclusive)
     * @param y1    bottom edge (exclusive)
     */
    private void fillPatch(BufferedImage image, int x0, int y0, int x1, int y1) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(124, 116, 104));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        g.dispose();
    }

    /**
     * Resizes an image to a square target size using bilinear interpolation.
     *
     * @param image      source image
     * @param targetSize target width and height in pixels
     * @return resized TYPE_INT_RGB copy
     */
    private BufferedImage resizeImage(BufferedImage image, int targetSize) {
        BufferedImage resized = new BufferedImage(
                targetSize, targetSize, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
        );
        g2d.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
        );
        g2d.drawImage(image, 0, 0, targetSize, targetSize, null);
        g2d.dispose();
        return resized;
    }

    // ===== Post-processing =====

    /**
     * ReLU: sets all negative values to zero.
     * Negative importance means hiding the patch increased confidence,
     * indicating the region was suppressing the prediction — not
     * meaningful for our visualization.
     *
     * @param data 2D map to modify in place
     */
    private void applyReLU(float[][] data) {
        for (float[] row : data) {
            for (int x = 0; x < row.length; x++) {
                if (row[x] < 0f) row[x] = 0f;
            }
        }
    }

    /**
     * Normalizes all values in the map to [0, 1].
     * No-op if all values are zero (flat prediction).
     *
     * @param data 2D map to normalize in place
     */
    private void normalizeMap(float[][] data) {
        float max = 0f;
        for (float[] row : data) {
            for (float v : row) {
                if (v > max) max = v;
            }
        }
        if (max == 0f) return;
        for (float[] row : data) {
            for (int x = 0; x < row.length; x++) {
                row[x] /= max;
            }
        }
    }

    /**
     * Applies box blur approximating Gaussian smoothing.
     * Converts blocky patch-resolution values into a smooth gradient.
     *
     * @param data   input 2D map
     * @param width  map width
     * @param height map height
     * @param radius blur half-kernel radius
     * @return smoothed copy (input unchanged)
     */
    private float[][] gaussianBlur(float[][] data, int width, int height, int radius) {
        float[][] temp = new float[height][width];
        float[][] out  = new float[height][width];

        // Pass 1: horizontal
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0; int count = 0;
                for (int kx = Math.max(0, x - radius); kx <= Math.min(width - 1, x + radius); kx++) {
                    sum += data[y][kx]; count++;
                }
                temp[y][x] = sum / count;
            }
        }

        // Pass 2: vertical
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0; int count = 0;
                for (int ky = Math.max(0, y - radius); ky <= Math.min(height - 1, y + radius); ky++) {
                    sum += temp[ky][x]; count++;
                }
                out[y][x] = sum / count;
            }
        }
        return out;
    }

    // ===== Visualization =====

    /**
     * Converts the normalized importance map to a colorized ARGB image
     * using the jet colormap: blue -> cyan -> green -> yellow -> red.
     *
     * Values below 0.10 are rendered transparent to suppress noise
     * in low-importance background regions. Alpha increases linearly
     * with importance.
     *
     * @param data normalized 2D importance values [0, 1]
     * @return ARGB colorized saliency image
     */
    private BufferedImage colorizeHeatmap(float[][] data) {
        int height = data.length;
        int width  = data[0].length;

        BufferedImage heatmap = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB
        );

        final float THRESHOLD = 0.28f;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float v = data[y][x];

                if (v < THRESHOLD) {
                    heatmap.setRGB(x, y, 0x00000000);
                    continue;
                }

                // Remap above-threshold values to [0, 1]
                float t = (v - THRESHOLD) / (1.0f - THRESHOLD);
                t = Math.min(1.0f, Math.max(0.0f, t));

                // Jet colormap
                int r = clamp255(jetR(t));
                int g = clamp255(jetG(t));
                int b = clamp255(jetB(t));

                // Alpha: 120-230 range so background context stays visible
                int a = 120 + (int)(t * 110f);
                a = Math.min(230, Math.max(0, a));

                heatmap.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }

        return heatmap;
    }

    /**
     * Jet colormap red channel. Rises from 0 to 1 in the 0.375-0.625 range.
     * @param t [0, 1]
     * @return red float [0, 1]
     */
    private float jetR(float t) {
        if (t < 0.375f) return 0f;
        if (t < 0.625f) return (t - 0.375f) * 4f;
        return 1f;
    }

    /**
     * Jet colormap green channel. Peaks at t=0.5 (full green).
     * @param t [0, 1]
     * @return green float [0, 1]
     */
    private float jetG(float t) {
        if (t < 0.125f) return 0f;
        if (t < 0.375f) return (t - 0.125f) * 4f;
        if (t < 0.625f) return 1f;
        if (t < 0.875f) return 1f - (t - 0.625f) * 4f;
        return 0f;
    }

    /**
     * Jet colormap blue channel. Full at t=0.125-0.375, fades to 0 at t=0.625.
     * @param t [0, 1]
     * @return blue float [0, 1]
     */
    private float jetB(float t) {
        if (t < 0.125f) return 0.5f + t * 4f;
        if (t < 0.375f) return 1f;
        if (t < 0.625f) return 1f - (t - 0.375f) * 4f;
        return 0f;
    }

    /**
     * Converts a [0, 1] float to an integer [0, 255] clamped byte.
     * @param v float [0, 1]
     * @return integer [0, 255]
     */
    private int clamp255(float v) {
        return Math.min(255, Math.max(0, (int)(v * 255f)));
    }

    /**
     * Blends the ARGB heatmap onto the original RGB image.
     * Uses SRC_OVER alpha compositing.
     *
     * @param original original 380x380 skin lesion image
     * @param heatmap  ARGB colorized saliency map
     * @param opacity  global scale factor applied on top of per-pixel alpha
     * @return blended RGB image
     */
    private BufferedImage blendImages(BufferedImage original,
                                      BufferedImage heatmap,
                                      float opacity) {
        int width  = original.getWidth();
        int height = original.getHeight();

        BufferedImage blended = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g2d = blended.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);

        g2d.drawImage(original, 0, 0, null);

        g2d.setComposite(AlphaComposite.getInstance(
                AlphaComposite.SRC_OVER, opacity));
        g2d.drawImage(heatmap, 0, 0, null);

        g2d.dispose();
        return blended;
    }
}
