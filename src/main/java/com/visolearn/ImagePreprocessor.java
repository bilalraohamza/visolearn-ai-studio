package com.visolearn;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * ImagePreprocessor handles all image preparation steps before
 * passing an image to the EfficientNet-B4 ONNX model.
 *
 * Pipeline:
 * 1. Load image from file path
 * 2. Resize to 380x380 using bilinear interpolation
 * 3. Extract RGB pixel values normalized to [0, 1]
 * 4. Apply ImageNet normalization per channel
 *    mean = [0.485, 0.456, 0.406]
 *    std  = [0.229, 0.224, 0.225]
 * 5. Arrange into [1, 3, 380, 380] float array
 * 6. Wrap in NDArray for DJL ONNX inference
 *
 * Note: Preprocessing is done manually using Java AWT
 * because ONNX Runtime NDManager does not support
 * math operations like sub() and div().
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class ImagePreprocessor {

    /** EfficientNet-B4 native input resolution. */
    public static final int IMAGE_SIZE = 380;

    /**
     * ImageNet mean per channel (RGB order).
     * Must match Python training exactly.
     */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};

    /**
     * ImageNet standard deviation per channel (RGB order).
     * Must match Python training exactly.
     */
    private static final float[] STD  = {0.229f, 0.224f, 0.225f};

    /**
     * Preprocesses an already-loaded BufferedImage and returns
     * an NDArray tensor ready for ONNX model inference.
     *
     * This overload is used by OcclusionRenderer's occlusion loop
     * to avoid re-reading from disk for each of the 49 occluded
     * variants. The normalization pipeline is identical to
     * preprocessFromFile — same mean, std, channel order, and size.
     *
     * Output tensor shape: [1, 3, 380, 380]
     *
     * @param manager DJL NDManager for tensor creation
     * @param image   already-loaded image (any size)
     * @return NDArray tensor [1, 3, 380, 380] float32
     * @throws Exception if tensor creation fails
     */
    public NDArray preprocessFromImage(NDManager manager,
                                       BufferedImage image)
            throws Exception {

        if (image == null) {
            throw new IllegalArgumentException(
                    "Input image must not be null."
            );
        }

        // Resize to 380x380 if not already that size
        BufferedImage resized;
        if (image.getWidth() == IMAGE_SIZE &&
                image.getHeight() == IMAGE_SIZE &&
                image.getType() == BufferedImage.TYPE_INT_RGB) {
            resized = image;
        } else {
            resized = new BufferedImage(
                    IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB
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
            g2d.drawImage(image, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
            g2d.dispose();
        }

        // Same normalization as preprocessFromFile
        int     channelSize  = IMAGE_SIZE * IMAGE_SIZE;
        float[] floatArray   = new float[3 * channelSize];

        for (int y = 0; y < IMAGE_SIZE; y++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                int rgb        = resized.getRGB(x, y);
                int pixelIndex = y * IMAGE_SIZE + x;

                float r = ((rgb >> 16) & 0xFF) / 255.0f;
                float g = ((rgb >> 8)  & 0xFF) / 255.0f;
                float b = (rgb & 0xFF)          / 255.0f;

                floatArray[0 * channelSize + pixelIndex] =
                        (r - MEAN[0]) / STD[0];
                floatArray[1 * channelSize + pixelIndex] =
                        (g - MEAN[1]) / STD[1];
                floatArray[2 * channelSize + pixelIndex] =
                        (b - MEAN[2]) / STD[2];
            }
        }

        return manager.create(
                floatArray,
                new ai.djl.ndarray.types.Shape(1, 3, IMAGE_SIZE, IMAGE_SIZE)
        );
    }

    /**
     * Loads an image from disk, preprocesses it, and returns
     * an NDArray tensor ready for ONNX model inference.
     *
     * Output tensor shape: [1, 3, 380, 380]
     * (batch=1, channels=3, height=380, width=380)
     *
     * @param manager   DJL NDManager for tensor creation
     * @param imagePath path to the image file on disk
     * @return NDArray tensor [1, 3, 380, 380] float32
     * @throws Exception if image cannot be read or processed
     */
    public NDArray preprocessFromFile(NDManager manager,
                                      Path imagePath)
            throws Exception {

        // Step 1 — Load image from disk
        BufferedImage original = ImageIO.read(imagePath.toFile());
        if (original == null) {
            throw new IllegalArgumentException(
                    "Cannot read image: " + imagePath +
                            ". Make sure it is a valid JPG or PNG file."
            );
        }

        // Step 2 — Resize to 380x380 using bilinear interpolation
        // Matches Python: transforms.Resize((380, 380))
        BufferedImage resized = new BufferedImage(
                IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB
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
        g2d.drawImage(original, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
        g2d.dispose();

        // Step 3 — Convert pixels to normalized float array
        // Layout: [batch, channel, height, width] = [1, 3, 380, 380]
        // Total elements: 1 × 3 × 380 × 380 = 433,200
        int totalElements = 3 * IMAGE_SIZE * IMAGE_SIZE;
        float[] floatArray = new float[totalElements];

        // Offset for each channel in the flat array
        // Channel 0 (Red):   indices 0         to 380*380-1
        // Channel 1 (Green): indices 380*380   to 2*380*380-1
        // Channel 2 (Blue):  indices 2*380*380 to 3*380*380-1
        int channelSize = IMAGE_SIZE * IMAGE_SIZE;

        for (int y = 0; y < IMAGE_SIZE; y++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                int pixelIndex = y * IMAGE_SIZE + x;

                // Extract RGB components from packed int
                // Each component is in range [0, 255]
                float r = ((rgb >> 16) & 0xFF) / 255.0f;
                float g = ((rgb >> 8)  & 0xFF) / 255.0f;
                float b = (rgb & 0xFF)          / 255.0f;

                // Apply ImageNet normalization per channel
                // Formula: normalized = (pixel - mean) / std
                // This matches Python:
                //   transforms.Normalize(mean, std)
                floatArray[0 * channelSize + pixelIndex] =
                        (r - MEAN[0]) / STD[0];  // Red channel
                floatArray[1 * channelSize + pixelIndex] =
                        (g - MEAN[1]) / STD[1];  // Green channel
                floatArray[2 * channelSize + pixelIndex] =
                        (b - MEAN[2]) / STD[2];  // Blue channel
            }
        }

        // Step 4 — Wrap float array in NDArray
        // Shape [1, 3, 380, 380] matches ONNX model input spec
        NDArray tensor = manager.create(
                floatArray,
                new Shape(1, 3, IMAGE_SIZE, IMAGE_SIZE)
        );

        return tensor;
    }
}