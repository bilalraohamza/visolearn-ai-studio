package com.visolearn.utils;

import com.visolearn.SkinClassifier.PredictionResult;

public class RiskAssessor {

    public static RiskLevel assess(PredictionResult result) {
        int classIndex = result.classIndex;
        float confidence = result.confidence;

        boolean isMelanomaOrBCC = (classIndex == 4 || classIndex == 1);
        boolean isAK = (classIndex == 0);

        if (isMelanomaOrBCC && confidence > 60.0f) {
            return RiskLevel.URGENT;
        } else if (isMelanomaOrBCC) {
            return RiskLevel.MODERATE;
        } else if (isAK && confidence > 60.0f) {
            return RiskLevel.MODERATE;
        } else {
            // Benign classes (2: BKL, 3: DF, 5: NV, 6: VASC) or low confidence AK
            return RiskLevel.LOW;
        }
    }
}
