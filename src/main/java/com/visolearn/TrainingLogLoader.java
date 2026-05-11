package com.visolearn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads JSON training log files produced during Python model training.
 * Supports dynamic filenames to accommodate dual-model ensemble workflows.
 */
public class TrainingLogLoader {

    // ─────────────────────────────────────────────────────────────────────────
    // Data Model
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of metrics recorded for a single training epoch.
     */
    public static class EpochData {
        public final int    epoch;
        public final double trainLoss;
        public final double valLoss;
        public final double trainAcc;
        public final double valAcc;

        public EpochData(int epoch, double trainLoss, double valLoss,
                         double trainAcc, double valAcc) {
            this.epoch     = epoch;
            this.trainLoss = trainLoss;
            this.valLoss   = valLoss;
            this.trainAcc  = trainAcc;
            this.valAcc    = valAcc;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a JSON training log from the classpath and returns an ordered
     * list of per-epoch metric snapshots.
     *
     * <p>The {@link InputStream} is wrapped in a {@code try-with-resources}
     * block to guarantee closure after parsing, preventing file handle leaks
     * across repeated calls (e.g., loading logs for both ensemble models on
     * the Training Metrics screen).</p>
     *
     * <p>Expected JSON structure:</p>
     * <pre>{@code
     * {
     *   "epoch":      [1, 2, 3, ...],
     *   "train_loss": [0.9, 0.7, ...],
     *   "val_loss":   [1.0, 0.8, ...],
     *   "train_acc":  [0.6, 0.75, ...],
     *   "val_acc":    [0.55, 0.72, ...]
     * }
     * }</pre>
     *
     * @param fileName Classpath-relative path to the JSON log file
     *                 (e.g., {@code "/training_log_effnet.json"}).
     * @return Ordered list of {@link EpochData} matching the JSON arrays.
     * @throws IllegalStateException if the file is not found on the classpath
     *                               or any required JSON field is absent.
     * @throws Exception             if Jackson fails to parse the JSON content.
     */
    public List<EpochData> load(String fileName) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(fileName)) {

            if (stream == null) {
                throw new IllegalStateException("Log file not found on classpath: " + fileName);
            }

            JsonNode root = new ObjectMapper().readTree(stream);

            JsonNode epochs    = root.get("epoch");
            JsonNode trainLoss = root.get("train_loss");
            JsonNode valLoss   = root.get("val_loss");
            JsonNode trainAcc  = root.get("train_acc");
            JsonNode valAcc    = root.get("val_acc");

            if (epochs == null || trainLoss == null || valLoss == null
                    || trainAcc == null || valAcc == null) {
                throw new IllegalStateException(
                        "Missing one or more required fields " +
                                "(epoch, train_loss, val_loss, train_acc, val_acc) in: " + fileName
                );
            }

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

            System.out.println("TrainingLogLoader: loaded "
                    + result.size() + " epochs from " + fileName);

            return result;
        }
    }

    /**
     * Returns the epoch with the highest validation accuracy from the
     * provided list. Assumes the list contains at least one element.
     *
     * @param epochs Non-empty list of {@link EpochData} to search.
     * @return The {@link EpochData} entry with the maximum {@code valAcc}.
     */
    public EpochData getBestEpoch(List<EpochData> epochs) {
        if (epochs == null || epochs.isEmpty()) {
            throw new IllegalArgumentException("Epoch list is empty — check the JSON log file.");
        }
        EpochData best = epochs.get(0);
        for (EpochData e : epochs) {
            if (e.valAcc > best.valAcc) {
                best = e;
            }
        }
        return best;
    }
}