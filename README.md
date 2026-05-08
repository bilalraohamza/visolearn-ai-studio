# VisoLearn AI Studio

A JavaFX desktop application for real-time skin lesion classification using deep learning models. Built on top of [Deep Java Library (DJL)](https://djl.ai/) with ONNX Runtime, it provides single-image classification, occlusion sensitivity heatmaps, batch folder analysis, and live training curve visualization — all running locally on CPU with no internet connection required.

> **Status:** Active development. EfficientNet-B4 is the current production model. DenseNet169 support is planned for the next release.

---

## Features

**Single Image Classification**
Upload or drag-and-drop a dermoscopy image to get instant predictions across 7 skin lesion classes with per-class confidence bars and clinical descriptions.

**Occlusion Sensitivity Saliency Maps**
Toggle a heatmap overlay that highlights which regions of the image drove the model's prediction. Based on the occlusion sensitivity method from Zeiler and Fergus (ECCV 2014): 49 forward passes on a 7x7 patch grid, then normalized and colorized with the jet colormap.

**Training Dashboard**
Visualizes training and validation loss/accuracy curves loaded directly from `training_log.json`. Shows best validation accuracy and the epoch it was achieved.

**Batch Analysis**
Select a folder of images and run inference across all of them at once. Results populate a live table with file name, predicted class, confidence, and inference time. Export all results to CSV with one click.

---

## Supported Classes

The model classifies images across 7 categories from the [HAM10000](https://www.kaggle.com/datasets/kmader/skin-lesion-analysis-toward-melanoma-detection) dataset:

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

## Model Performance

| Model | Epochs | Best Val Accuracy | Train Accuracy |
|-------|--------|-------------------|----------------|
| EfficientNet-B4 (current) | 25 | **81.76%** | 93.61% |
| DenseNet169 | Planned | TBD | TBD |

Input tensor shape: `[1, 3, 380, 380]` — matches EfficientNet-B4's native resolution.

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| UI Framework | JavaFX 21 |
| ML Runtime | DJL 0.26.0 + ONNX Runtime |
| Model Format | ONNX (`skin_model.onnx`) |
| JSON Parsing | Jackson Databind 2.17.0 |
| Build Tool | Maven 3 |
| Java Version | Java 17 |
| Logging | SLF4J Simple |

---

## Project Structure

```
visolearn-ai-studio/
├── pom.xml
└── src/main/
    ├── java/com/visolearn/
    │   ├── MainApp.java              # Application entry point, shared classifier init
    │   ├── SkinClassifier.java       # DJL/ONNX inference engine, softmax, result model
    │   ├── ImagePreprocessor.java    # Resize + normalize images to [1, 3, 380, 380]
    │   ├── GradCamRenderer.java      # Occlusion sensitivity saliency map generator
    │   ├── ClassifyController.java   # Tab 1: single image classification UI
    │   ├── DashboardController.java  # Tab 2: training curve charts
    │   ├── BatchController.java      # Tab 3: folder batch inference + CSV export
    │   └── TrainingLogLoader.java    # Reads training_log.json for Dashboard tab
    └── resources/
        ├── main.fxml                 # Root layout with 3-tab structure
        ├── classify_tab.fxml         # Tab 1 layout
        ├── dashboard_tab.fxml        # Tab 2 layout
        ├── batch_tab.fxml            # Tab 3 layout
        ├── skin_model.onnx           # EfficientNet-B4 model weights
        ├── labels.txt                # 7 class names (one per line)
        ├── training_log.json         # Loss + accuracy curves (25 epochs)
        └── styles.css                # Dark theme stylesheet
```

---

## Prerequisites

- Java 17 or higher
- Maven 3.8 or higher

No GPU required. All inference runs on CPU via ONNX Runtime.

---

## Getting Started

**1. Clone the repository**

```bash
git clone https://github.com/bilalraohamza/visolearn-ai-studio.git
cd visolearn-ai-studio
```

**2. Build the project**

```bash
mvn clean package -DskipTests
```

**3. Run the application**

```bash
mvn javafx:run
```

Or run the packaged fat JAR directly:

```bash
java -jar target/visolearn-ai-studio-1.0-SNAPSHOT.jar
```

> The first launch takes 3-5 seconds while the ONNX model loads into memory. Subsequent tabs reuse the same shared model instance.

---

## Usage

**Classify Tab**
1. Click "Upload Image" or drag a `.jpg` / `.png` file onto the image panel.
2. The model runs inference automatically. Results appear within 1-2 seconds.
3. Toggle "Show Saliency Map" to generate a heatmap overlay. This takes 5-8 seconds for 49 forward passes.
4. Click "Clear" to reset the panel.

**Dashboard Tab**
Training and validation curves load automatically from `training_log.json`. Click "Reload" to refresh if you replace the file with updated metrics.

**Batch Tab**
1. Click "Browse Folder" and select a directory of `.jpg` / `.png` images.
2. Click "Run Analysis" to process all images sequentially.
3. Watch results populate the table in real time.
4. Click "Export CSV" to save results to disk.

---

## Architecture Notes

**Single shared classifier instance**
The ONNX model is loaded once in `MainApp` on a background thread and shared across all tabs via `MainApp.getSharedClassifier()`. This prevents double-loading the model file, which causes ONNX Runtime conflicts.

**Threading model**
All DJL inference calls run on background `Task` threads. All UI updates are dispatched via `Platform.runLater()`. The JavaFX Application Thread is never blocked.

**Softmax in Java**
The ONNX model outputs raw logits. Numerically stable softmax (subtract max before exponentiation) is applied in Java before returning probabilities.

**Saliency map method**
The heatmap uses occlusion sensitivity analysis, not gradient-based Grad-CAM. Each of 49 patches in a 7x7 grid is filled with the ImageNet mean color `(124, 116, 104)` which normalizes to zero in all channels. A fresh inference pass records the confidence drop per patch. Regions with large drops are highlighted red. ReLU, normalization, Gaussian smoothing, and jet colormap are applied before blending onto the original image at 50% opacity.

---

## Planned Features

- [ ] DenseNet169 model integration
- [ ] Model selector UI (switch between EfficientNet-B4 and DenseNet169)
- [ ] Side-by-side model comparison view
- [ ] Per-class accuracy breakdown on Dashboard tab
- [ ] Confidence threshold filtering in Batch tab
- [ ] Exportable saliency map images

---

## Disclaimer

This application is intended for educational and research purposes only. It is not a medical device and must not be used for clinical diagnosis. Always consult a qualified dermatologist for medical evaluation of skin lesions.

---

## Author

**Rao Hamza Bilal**

---

## License

This project is currently unlicensed. All rights reserved by the author.
