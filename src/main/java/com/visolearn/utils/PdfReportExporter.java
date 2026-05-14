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

    public static void exportClinicalReport(File file, SkinClassifier.PredictionResult result, Image originalImage, Image heatmapImage, String notes) throws IOException {
        
        // Generate the exact same beautiful layout as the PNG export!
        WritableImage snapshot = ReportExportUtil.generateReportSnapshot(
                originalImage, heatmapImage, result, notes);
                
        BufferedImage awtImage = SwingFXUtils.fromFXImage(snapshot, null);

        try (PDDocument document = new PDDocument()) {
            // Create A4 page
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDImageXObject pdImage = LosslessFactory.createFromImage(document, awtImage);

            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float imageWidth = pdImage.getWidth();
            float imageHeight = pdImage.getHeight();

            // Add a small margin (e.g., 20 points)
            float margin = 20f;
            float availableWidth = pageWidth - 2 * margin;
            float availableHeight = pageHeight - 2 * margin;
            
            // We want it to fit completely on the page (both width-wise and height-wise)
            float scale = Math.min(availableWidth / imageWidth, availableHeight / imageHeight);
            float scaledWidth = imageWidth * scale;
            float scaledHeight = imageHeight * scale;

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                // Center horizontally, align to top margin
                float startX = margin + (availableWidth - scaledWidth) / 2;
                float startY = pageHeight - margin - scaledHeight;
                contentStream.drawImage(pdImage, startX, startY, scaledWidth, scaledHeight);
            }

            document.save(file);
        }
    }
}
