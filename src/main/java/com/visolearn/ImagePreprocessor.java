package com.visolearn;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Pipeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * ImagePreprocessor handles all image preparation steps before
 * passing an image to the EfficientNet-B4 ONNX model.
 *
 * Pipeline:
 * 1. Load image from file path or BufferedImage
 * 2. Resize to 380x380 (EfficientNet-B4 native resolution)
 * 3. Convert to float tensor with values in [0, 1]
 * 4. Normalize using ImageNet mean and std
 *    mean = [0.485, 0.456, 0.406]
 *    std  = [0.229, 0.224, 0.225]
 *
 * These values must match exactly what was used during Python training.
 * Any mismatch will cause incorrect predictions.
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class ImagePreprocessor {

    /** EfficientNet-B4 native input resolution. */
    public static final int IMAGE_SIZE = 380;

    /**
     * ImageNet mean values per channel (RGB order).
     * Used to normalize pixel values after converting to [0,1].
     * Must match Python training: mean=[0.485, 0.456, 0.406]
     */
    private static final float[] IMAGENET_MEAN =
            {0.485f, 0.456f, 0.406f};

    /**
     * ImageNet standard deviation values per channel (RGB order).
     * Used to normalize pixel values after converting to [0,1].
     * Must match Python training: std=[0.229, 0.224, 0.225]
     */
    private static final float[] IMAGENET_STD =
            {0.229f, 0.224f, 0.225f};

    /**
     * Loads a DJL Image from a file path on disk.
     * Converts to RGB automatically — handles both JPG and PNG.
     *
     * @param imagePath path to the image file
     * @return DJL Image object ready for preprocessing
     * @throws Exception if the file cannot be read
     */
    public Image loadFromFile(Path imagePath) throws Exception {
        return ImageFactory.getInstance().fromFile(imagePath);
    }

    /**
     * Loads a DJL Image from a Java BufferedImage.
     * Used when receiving frames from the webcam or
     * drag-and-drop events in the JavaFX GUI.
     *
     * @param bufferedImage Java AWT BufferedImage
     * @return DJL Image object ready for preprocessing
     */
    public Image loadFromBufferedImage(BufferedImage bufferedImage) {
        return ImageFactory.getInstance().fromImage(bufferedImage);
    }

    /**
     * Preprocesses a DJL Image into a normalized NDArray tensor.
     * This is the exact same preprocessing applied during Python training.
     *
     * Steps performed:
     * 1. Resize image to IMAGE_SIZE x IMAGE_SIZE (380x380)
     * 2. Convert pixels from [0,255] integers to [0,1] floats
     * 3. Normalize: output = (pixel - mean) / std per channel
     *
     * Output tensor shape: [1, 3, 380, 380]
     * (batch=1, channels=3, height=380, width=380)
     *
     * @param manager DJL NDManager for tensor allocation
     * @param image   DJL Image to preprocess
     * @return NDArray tensor ready for ONNX model inference
     * @throws Exception if preprocessing fails
     */
    public NDArray preprocess(NDManager manager, Image image)
            throws Exception {

        // Step 1 — Resize to 380x380
        // Uses bilinear interpolation matching Python's transforms.Resize
        image = image.resize(IMAGE_SIZE, IMAGE_SIZE, false);

        // Step 2 — Convert image to NDArray tensor
        // DJL converts pixel values from [0,255] to [0,1] automatically
        // Output shape: [3, 380, 380] (channels, height, width)
        NDArray tensor = image.toNDArray(manager, Image.Flag.COLOR);

        // Step 3 — Normalize using ImageNet mean and std
        // Formula per channel: normalized = (pixel - mean) / std
        // This matches Python: transforms.Normalize(mean, std)
        NDArray mean = manager.create(IMAGENET_MEAN)
                .reshape(3, 1, 1);
        NDArray std  = manager.create(IMAGENET_STD)
                .reshape(3, 1, 1);

        // Apply normalization: (tensor - mean) / std
        tensor = tensor.sub(mean).div(std);

        // Step 4 — Add batch dimension
        // Model expects [batch, channels, height, width]
        // expandDims(0) converts [3, 380, 380] to [1, 3, 380, 380]
        tensor = tensor.expandDims(0);

        return tensor;
    }

    /**
     * Convenience method: loads a file and preprocesses it in one call.
     * Used by ClassifyController when user uploads an image.
     *
     * @param manager   DJL NDManager for tensor allocation
     * @param imagePath path to the image file on disk
     * @return preprocessed NDArray tensor [1, 3, 380, 380]
     * @throws Exception if loading or preprocessing fails
     */
    public NDArray preprocessFromFile(NDManager manager, Path imagePath)
            throws Exception {
        Image image = loadFromFile(imagePath);
        return preprocess(manager, image);
    }
}