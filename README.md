# VisoLearn AI Studio

A JavaFX desktop clinical support application for skin lesion classification, built for dermatology workflows. It runs a real-time ensemble of EfficientNet-B4 and DenseNet-169 models via ONNX Runtime, provides two independent saliency-map methods for explainability, and wraps the whole thing in a doctor-facing workflow with patient records, prediction history, and exportable PDF reports — all running locally, offline, on CPU.

---

## Features

**Ensemble Classification**
Every prediction runs both EfficientNet-B4 and DenseNet-169 in parallel, averages their logits, and applies a numerically stable softmax to produce the final 7-class prediction with a full confidence distribution.

**Dual Explainability Methods**
- **Score-CAM** (Wang et al., CVPR 2020 Workshops) — generates 25 soft Gaussian masks across a 5x5 grid, scores each masked forward pass against the baseline prediction, and blends the result into a smooth saliency heatmap.
- **Occlusion Sensitivity** (Zeiler & Fergus, ECCV 2014) — runs 49 forward passes across a 7x7 hard-patch grid, measuring the confidence drop caused by occluding each region, then colorizes the result with a jet colormap.

**Doctor Login & Patient Records**
Multi-user login system with bcrypt password hashing. Each doctor manages their own patient list, and every prediction is saved to a SQLite database (pooled via HikariCP) tied to a specific patient.

**Automated Risk Stratification**
Every prediction is automatically classified into an URGENT / MODERATE / LOW risk band — e.g., a Melanoma or Basal Cell Carcinoma prediction above 60% confidence is flagged URGENT — to help prioritize follow-up.

**Prediction History**
Doctors can review a searchable history of past predictions per patient, including confidence scores and saved saliency maps.

**PDF Report Export**
Generates a clinical-style PDF report (via Apache PDFBox) for any prediction, embedding the source image, saliency map, and risk assessment.

**Batch Analysis**
Select a folder of images and run ensemble inference across all of them concurrently (thread-safe per-worker predictor pairs), with live results in a table and one-click CSV export.

**Training Dashboard**
Visualizes training/validation loss and accuracy curves for both models, loaded from their respective training logs, with best validation accuracy and the epoch it was achieved.

---

## Supported Classes

The model classifies dermoscopy images across 7 categories from the [HAM10000](https://www.kaggle.com/datasets/kmader/skin-lesion-analysis-toward-melanoma-detection) dataset:

| Code | Full Name | Description |
|------|-----------|-------------|
| `akiec` | Actinic Keratosis | Rough, scaly patch from sun exposure; can become cancerous |
| `bcc` | Basal Cell Carcinoma | Most common skin cancer; locally destructive |
| `bkl` | Benign Keratosis | Non-cancerous growth (e.g. seborrheic keratoses) |
| `df` | Dermatofibroma | Common benign skin nodule; usually harmless |
| `mel` | Melanoma | Most dangerous skin cancer; early detection critical |
| `nv` | Melanocytic Nevus | Common mole; monitor for changes |
| `vasc` | Vascular Lesion | Blood vessel lesions; usually benign |

---

## Model Performance (Ensemble)

| Metric | Score |
|--------|-------|
| Test Accuracy | **80.99%** |
| Macro F1 | **0.708** |

**Per-class F1:**

| Class | F1 |
|-------|-----|
| bcc | 0.809 |
| nv | 0.887 |
| vasc | 0.787 |
| mel | 0.684 |
| df | 0.654 |
| bkl | 0.601 |
| akiec | 0.535 |

Input tensor shape: `[1, 3, 380, 380]`.

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| UI Framework | JavaFX 21 |
| ML Runtime | Deep Java Library (DJL) + ONNX Runtime engine |
| Models | EfficientNet-B4 + DenseNet-169 (ensemble, ONNX format) |
| Database | SQLite (`sqlite-jdbc`) via HikariCP connection pool |
| PDF Export | Apache PDFBox 2.0 |
| Password Hashing | jBCrypt |
| JSON Parsing | Jackson Databind |
| Build Tool | Maven 3 |
| Java Version | Java 17 |
| Testing | JUnit 5 |
| Logging | SLF4J Simple |

No GPU required — all inference runs on CPU.

---

## Project Structure

```
visolearn-ai-studio/
├── pom.xml
└── src/main/
    ├── java/com/visolearn/
    │   ├── MainApp.java, Launcher.java, SplashScreen.java
    │   ├── LoginController.java, SessionManager.java
    │   ├── ClassifyController.java, BatchController.java
    │   ├── DashboardController.java, HistoryController.java
    │   ├── SkinClassifier.java          # Ensemble inference engine
    │   ├── ImagePreprocessor.java       # Resize + normalize to [1,3,380,380]
    │   ├── ScoreCamRenderer.java        # Score-CAM saliency maps
    │   ├── OcclusionRenderer.java       # Occlusion sensitivity saliency maps
    │   ├── data/                        # DatabaseUtil, DoctorDAO, PatientDAO, PredictionDAO
    │   ├── service/                     # ClassificationService, PatientService
    │   └── utils/                       # RiskAssessor, PdfReportExporter, BackupManager, etc.
    ├── resources/
    │   ├── efficientnet_b4_v3.onnx, densenet169_v2.onnx
    │   ├── ensemble_metrics.json
    │   ├── training_log_b4v3.json, training_log_densenet169v2.json
    │   ├── labels.txt
    │   └── *.fxml, *.css                # UI layouts and themes
    └── test/java/com/visolearn/         # JUnit test suite
```

---

## Prerequisites

- Java 17 or higher
- Maven 3.8 or higher

## Getting Started

```bash
git clone https://github.com/bilalraohamza/visolearn-ai-studio.git
cd visolearn-ai-studio
mvn clean package -DskipTests
mvn javafx:run
```

Or run the packaged fat JAR directly:

```bash
java -jar target/visolearn-ai-studio-1.0-SNAPSHOT.jar
```

---

## Architecture Notes

**Ensemble inference**
Both models are loaded once and run per-prediction; their raw logits are averaged (equivalent to a geometric mean of pre-softmax activations) before a numerically stable softmax is applied — subtracting the max logit before exponentiation avoids float overflow.

**Thread-safe batch processing**
`ZooModel` instances are thread-safe and shared; each batch worker thread owns its own `Predictor` pair, giving true parallel inference with no synchronization needed at the call site.

**Memory management**
Ensemble inference wraps model outputs in try-with-resources blocks, since DJL's `NDList` holds off-heap native memory not managed by the JVM garbage collector — without this, native memory balloons during long batch sessions.

**Risk logic**
Melanoma or Basal Cell Carcinoma predictions above 60% confidence are flagged `URGENT`; below that threshold, `MODERATE`; benign classes default to `LOW`.

---

## Disclaimer

This application is intended for educational and research purposes only. It is not a certified medical device and must not be used for clinical diagnosis. Always consult a qualified dermatologist for medical evaluation of skin lesions.

---

## Author

**Rao Hamza Bilal**

## License

This project is currently unlicensed. All rights reserved by the author.
