package com.visolearn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TrainingLogLoader reads the training_log.json file saved during
 * Python EfficientNet-B4 training on Kaggle.
 * It parses epoch-by-epoch loss and accuracy data for the
 * Training Dashboard tab in the JavaFX GUI.
 *
 * @author Rao Hamza Bilal
 * @version 1.0
 */
public class TrainingLogLoader {

    /**
     * Represents one epoch of training data.
     * Stores train and validation loss and accuracy for that epoch.
     */
    public static class EpochData {

        /** Epoch number (1-based). */
        public final int epoch;

        /** Training loss for this epoch. */
        public final double trainLoss;

        /** Validation loss for this epoch. */
        public final double valLoss;

        /** Training accuracy for this epoch (0-100 scale). */
        public final double trainAcc;

        /** Validation accuracy for this epoch (0-100 scale). */
        public final double valAcc;

        /**
         * Constructs one epoch record.
         *
         * @param epoch     epoch number
         * @param trainLoss training loss value
         * @param valLoss   validation loss value
         * @param trainAcc  training accuracy percentage
         * @param valAcc    validation accuracy percentage
         */
        public EpochData(int epoch, double trainLoss, double valLoss,
                         double trainAcc, double valAcc) {
            this.epoch     = epoch;
            this.trainLoss = trainLoss;
            this.valLoss   = valLoss;
            this.trainAcc  = trainAcc;
            this.valAcc    = valAcc;
        }
    }

    /**
     * Loads and parses the training_log.json file from the
     * application resources folder.
     * The JSON structure has parallel arrays:
     * epoch[], train_loss[], val_loss[], train_acc[], val_acc[]
     *
     * @return list of EpochData objects, one per training epoch
     * @throws Exception if the file cannot be found or parsed
     */
    public List<EpochData> load() throws Exception {

        // Load training_log.json from resources
        InputStream stream = getClass()
                .getResourceAsStream("/training_log.json");

        if (stream == null) {
            throw new IllegalStateException(
                    "training_log.json not found in resources. " +
                            "Make sure it is copied to src/main/resources/"
            );
        }

        // Parse JSON using Jackson ObjectMapper
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(stream);

        // Extract the parallel arrays from the JSON
        JsonNode epochs     = root.get("epoch");
        JsonNode trainLoss  = root.get("train_loss");
        JsonNode valLoss    = root.get("val_loss");
        JsonNode trainAcc   = root.get("train_acc");
        JsonNode valAcc     = root.get("val_acc");

        // Validate all arrays are present
        if (epochs == null || trainLoss == null || valLoss == null
                || trainAcc == null || valAcc == null) {
            throw new IllegalStateException(
                    "training_log.json is missing required fields. " +
                            "Expected: epoch, train_loss, val_loss, train_acc, val_acc"
            );
        }

        // Build list of EpochData objects
        List<EpochData> result = new ArrayList<>();
        int numEpochs = epochs.size();

        for (int i = 0; i < numEpochs; i++) {
            result.add(new EpochData(
                    epochs.get(i).asInt(),
                    trainLoss.get(i).asDouble(),
                    valLoss.get(i).asDouble(),
                    trainAcc.get(i).asDouble(),
                    valAcc.get(i).asDouble()
            ));
        }

        System.out.println("TrainingLogLoader: loaded " +
                result.size() + " epochs from training_log.json");

        return result;
    }

    /**
     * Returns the epoch index with the highest validation accuracy.
     * Used to mark the best epoch on the training dashboard chart.
     *
     * @param epochs list of epoch data
     * @return the EpochData object with the best validation accuracy
     */
    public EpochData getBestEpoch(List<EpochData> epochs) {
        EpochData best = epochs.get(0);
        for (EpochData e : epochs) {
            if (e.valAcc > best.valAcc) {
                best = e;
            }
        }
        return best;
    }
}