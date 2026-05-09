package com.visolearn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TrainingLogLoader reads JSON log files saved during Python training.
 * Updated to accept dynamic filenames for dual-model ensembles.
 */
public class TrainingLogLoader {

    public static class EpochData {
        public final int epoch;
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

    public List<EpochData> load(String fileName) throws Exception {
        InputStream stream = getClass().getResourceAsStream(fileName);

        if (stream == null) {
            throw new IllegalStateException("Log file not found: " + fileName);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(stream);

        JsonNode epochs     = root.get("epoch");
        JsonNode trainLoss  = root.get("train_loss");
        JsonNode valLoss    = root.get("val_loss");
        JsonNode trainAcc   = root.get("train_acc");
        JsonNode valAcc     = root.get("val_acc");

        if (epochs == null || trainLoss == null || valLoss == null
                || trainAcc == null || valAcc == null) {
            throw new IllegalStateException("Missing required fields in " + fileName);
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

        System.out.println("TrainingLogLoader: loaded " + result.size() + " epochs from " + fileName);
        return result;
    }

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