package com.visolearn;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * ScoreCamRenderer generates Score-CAM saliency maps
 * for skin lesion images.
 *
 * Score-CAM reference:
 * Wang et al., "Score-CAM: Score-Weighted Visual
 * Explanations for Convolutional Neural Networks",
 * CVPR Workshops 2020.
 *
 * Algorithm:
 * 1. Run a baseline forward pass on the original
 *    image. Record baseline confidence S0.
 * 2. Generate N spatial Gaussian masks arranged
 *    in a 5x5 grid across the image. Each mask is
 *    a soft spotlight centered on a different region.
 *    Gaussian masks produce smooth gradients unlike
 *    the hard rectangular patches of occlusion.
 * 3. For each mask M_i:
 *    a. Multiply original image by M_i element-wise
 *       (masked pixels retain their color; background
 *       fades toward ImageNet mean gray)
 *    b. Run inference → get score S_i for target class
 *    c. Importance weight w_i = max(0, S_i - S0)
 *       Positive weight = this region helped the
 *       prediction. Negative = this region hurt it.
 * 4. Saliency map = sum(w_i * M_i) for all i
 * 5. Normalize, smooth with Gaussian blur, colorize
 *    using the same jet colormap as OcclusionRenderer.
 *
 * Advantages over OcclusionRenderer:
 * - 25 passes instead of 49 (~2x faster)
 * - Gaussian masks produce smoother saliency maps
 * - Score weighting is more precise than confidence drop
 * - No hard patch boundaries in the output
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class ScoreCamRenderer {

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int completed, int total);
    }

    private static final int OUTPUT_SIZE  = 380;
    private static final int GRID_SIZE    = 5;
    private static final int TOTAL_MASKS  = GRID_SIZE * GRID_SIZE;
    private static final float BLUR_RADIUS = 40f;
    private static final float HEATMAP_OPACITY = 1.0f;

    /**
     * ImageNet mean color used as the background fill
     * for masked regions. Same values as OcclusionRenderer.
     * R=124, G=116, B=104
     */
    private static final int MEAN_R = 124;
    private static final int MEAN_G = 116;
    private static final int MEAN_B = 104;

    private final SkinClassifier classifier;

    public ScoreCamRenderer(SkinClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * Generates a Score-CAM saliency map and blends
     * it onto the original image.
     * Always call this on a background thread.
     *
     * @param originalImagePath path to the source image
     * @param result            baseline prediction result
     * @param callback          optional progress callback
     * @return blended saliency map image
     * @throws Exception if image loading or inference fails
     */
    public BufferedImage generateHeatmap(
            Path originalImagePath,
            SkinClassifier.PredictionResult result,
            ProgressCallback callback) throws Exception {

        // Load and resize original image
        BufferedImage original =
            ImageIO.read(originalImagePath.toFile());
        if (original == null) {
            throw new IllegalArgumentException(
                "Cannot read image: " + originalImagePath);
        }
        BufferedImage resized =
            resizeImage(original, OUTPUT_SIZE);

        // Baseline confidence for the target class
        float baselineConf =
            result.allProbabilities[result.classIndex];
        int targetClass = result.classIndex;

        // Accumulate the saliency map
        float[][] saliencyMap =
            new float[OUTPUT_SIZE][OUTPUT_SIZE];

        int passIdx = 0;

        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {

                // Gaussian mask center coordinates
                float cx = (col + 0.5f) *
                    (OUTPUT_SIZE / (float) GRID_SIZE);
                float cy = (row + 0.5f) *
                    (OUTPUT_SIZE / (float) GRID_SIZE);

                // Sigma = 1.5 grid cells wide
                float sigma = OUTPUT_SIZE /
                    (float) GRID_SIZE * 1.5f;

                // Generate soft Gaussian mask M_i
                float[][] mask = generateGaussianMask(
                    cx, cy, sigma);

                // Apply mask to image:
                // masked pixel = original * M_i +
                //                mean_color * (1 - M_i)
                BufferedImage masked =
                    applyMask(resized, mask);

                // Run inference on masked image
                SkinClassifier.PredictionResult r =
                    classifier.predictFromImage(masked);

                // Importance weight =
                //   max(0, score_i - baseline)
                float weight = Math.max(0f,
                    r.allProbabilities[targetClass]
                    - baselineConf);

                // Accumulate: saliency += weight * mask
                for (int y = 0; y < OUTPUT_SIZE; y++) {
                    for (int x = 0; x < OUTPUT_SIZE; x++) {
                        saliencyMap[y][x] +=
                            weight * mask[y][x];
                    }
                }

                passIdx++;
                if (callback != null) {
                    callback.onProgress(
                        passIdx, TOTAL_MASKS);
                }
            }
        }

        // ReLU: keep only positive contributions
        applyReLU(saliencyMap);

        // Normalize to [0, 1]
        normalizeMap(saliencyMap);

        // Smooth the saliency map
        float[][] smoothed = gaussianBlur(
            saliencyMap, OUTPUT_SIZE, OUTPUT_SIZE,
            (int) BLUR_RADIUS);

        // Re-normalize after blur
        normalizeMap(smoothed);

        // Colorize and blend onto original image
        BufferedImage heatmap = colorizeHeatmap(smoothed);
        return blendImages(resized, heatmap,
            HEATMAP_OPACITY);
    }

    /**
     * Generates a 2D Gaussian mask centered at (cx, cy)
     * with the given sigma. Values are in [0, 1].
     * Higher values = center of the spotlight region.
     */
    private float[][] generateGaussianMask(
            float cx, float cy, float sigma) {

        float[][] mask =
            new float[OUTPUT_SIZE][OUTPUT_SIZE];
        float twoSigmaSq = 2.0f * sigma * sigma;

        for (int y = 0; y < OUTPUT_SIZE; y++) {
            for (int x = 0; x < OUTPUT_SIZE; x++) {
                float dx = x - cx;
                float dy = y - cy;
                mask[y][x] = (float) Math.exp(
                    -(dx * dx + dy * dy) / twoSigmaSq);
            }
        }
        return mask;
    }

    /**
     * Applies a soft mask to an image.
     * Each pixel is blended between its original color
     * and the ImageNet mean color based on mask value:
     * result = original * mask + mean * (1 - mask)
     *
     * @param image source image (unmodified)
     * @param mask  Gaussian mask values in [0, 1]
     * @return new masked image
     */
    private BufferedImage applyMask(
            BufferedImage image, float[][] mask) {

        BufferedImage result = new BufferedImage(
            OUTPUT_SIZE, OUTPUT_SIZE,
            BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < OUTPUT_SIZE; y++) {
            for (int x = 0; x < OUTPUT_SIZE; x++) {
                float m = mask[y][x];
                int rgb = image.getRGB(x, y);

                int origR = (rgb >> 16) & 0xFF;
                int origG = (rgb >> 8) & 0xFF;
                int origB = rgb & 0xFF;

                int r = Math.round(
                    origR * m + MEAN_R * (1f - m));
                int g = Math.round(
                    origG * m + MEAN_G * (1f - m));
                int b = Math.round(
                    origB * m + MEAN_B * (1f - m));

                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                result.setRGB(x, y,
                    (r << 16) | (g << 8) | b);
            }
        }
        return result;
    }

    // ── Post-processing ───────────────────────────────

    private void applyReLU(float[][] data) {
        for (float[] row : data)
            for (int x = 0; x < row.length; x++)
                if (row[x] < 0f) row[x] = 0f;
    }

    private void normalizeMap(float[][] data) {
        float max = 0f;
        for (float[] row : data)
            for (float v : row)
                if (v > max) max = v;
        if (max == 0f) return;
        for (float[] row : data)
            for (int x = 0; x < row.length; x++)
                row[x] /= max;
    }

    private float[][] gaussianBlur(
            float[][] data, int width, int height,
            int radius) {
        float[][] temp = new float[height][width];
        float[][] out  = new float[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0; int count = 0;
                for (int kx = Math.max(0, x - radius);
                     kx <= Math.min(width-1, x+radius);
                     kx++) {
                    sum += data[y][kx]; count++;
                }
                temp[y][x] = sum / count;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0; int count = 0;
                for (int ky = Math.max(0, y - radius);
                     ky <= Math.min(height-1, y+radius);
                     ky++) {
                    sum += temp[ky][x]; count++;
                }
                out[y][x] = sum / count;
            }
        }
        return out;
    }

    // ── Visualization ─────────────────────────────────
    // These methods are identical to OcclusionRenderer.
    // Using same jet colormap and blend for visual
    // consistency between the two modes.

    private BufferedImage colorizeHeatmap(float[][] data) {
        int height = data.length;
        int width  = data[0].length;
        BufferedImage heatmap = new BufferedImage(
            width, height, BufferedImage.TYPE_INT_ARGB);
        final float THRESHOLD = 0.28f;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float v = data[y][x];
                if (v < THRESHOLD) {
                    heatmap.setRGB(x, y, 0x00000000);
                    continue;
                }
                float t = (v - THRESHOLD) /
                    (1.0f - THRESHOLD);
                t = Math.min(1.0f, Math.max(0.0f, t));
                int r = clamp255(jetR(t));
                int g = clamp255(jetG(t));
                int b = clamp255(jetB(t));
                int a = 120 + (int)(t * 110f);
                a = Math.min(230, Math.max(0, a));
                heatmap.setRGB(x, y,
                    (a<<24)|(r<<16)|(g<<8)|b);
            }
        }
        return heatmap;
    }

    private float jetR(float t) {
        if (t < 0.375f) return 0f;
        if (t < 0.625f) return (t-0.375f)*4f;
        return 1f;
    }
    private float jetG(float t) {
        if (t < 0.125f) return 0f;
        if (t < 0.375f) return (t-0.125f)*4f;
        if (t < 0.625f) return 1f;
        if (t < 0.875f) return 1f-(t-0.625f)*4f;
        return 0f;
    }
    private float jetB(float t) {
        if (t < 0.125f) return 0.5f+t*4f;
        if (t < 0.375f) return 1f;
        if (t < 0.625f) return 1f-(t-0.375f)*4f;
        return 0f;
    }
    private int clamp255(float v) {
        return Math.min(255, Math.max(0, (int)(v*255f)));
    }

    private BufferedImage resizeImage(
            BufferedImage image, int targetSize) {
        BufferedImage resized = new BufferedImage(
            targetSize, targetSize,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(image, 0, 0,
            targetSize, targetSize, null);
        g2d.dispose();
        return resized;
    }

    private BufferedImage blendImages(
            BufferedImage original,
            BufferedImage heatmap,
            float opacity) {
        int w = original.getWidth();
        int h = original.getHeight();
        BufferedImage blended = new BufferedImage(
            w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = blended.createGraphics();
        g2d.drawImage(original, 0, 0, null);
        g2d.setComposite(AlphaComposite.getInstance(
            AlphaComposite.SRC_OVER, opacity));
        g2d.drawImage(heatmap, 0, 0, null);
        g2d.dispose();
        return blended;
    }
}
