package com.visolearn.utils;

import com.visolearn.SkinClassifier;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class PdfReportExporter {

    public static void exportClinicalReport(File file, SkinClassifier.PredictionResult result, Image originalImage, Image heatmapImage) throws IOException {
        
        String topClass = SkinClassifier.CLASS_FULL_NAMES[result.classIndex];
        double confidence = result.confidence;
        String inferenceTime = result.inferenceTimeMs + " ms";

        // Generate the exact same beautiful layout as the PNG export!
        WritableImage snapshot = ReportExportUtil.generateReportSnapshot(
                originalImage, heatmapImage, topClass, confidence, inferenceTime);
                
        BufferedImage awtImage = SwingFXUtils.fromFXImage(snapshot, null);

        try (PDDocument document = new PDDocument()) {
            // Create A4 page
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDImageXObject pdImage = LosslessFactory.createFromImage(document, awtImage);

            // Scale image to fit A4 width exactly (minus some padding if desired, or full bleed)
            // A4 width = 595.27563, height = 841.8898
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            
            float imageWidth = pdImage.getWidth();
            float imageHeight = pdImage.getHeight();
            
            // We want it to fit width-wise
            float scale = pageWidth / imageWidth;
            float scaledWidth = imageWidth * scale;
            float scaledHeight = imageHeight * scale;

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                // Draw from top left
                float startY = pageHeight - scaledHeight;
                contentStream.drawImage(pdImage, 0, startY, scaledWidth, scaledHeight);
            }

            document.save(file);
        }
    }
}
