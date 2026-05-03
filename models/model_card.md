# Graffiti Classifier — Model Card

**Run ID:** `20260503-183145`
**Date:** 2026-05-03T20:28:12.802987Z

## Model details
- Architecture: EfficientNet-B0 with binary classification head (Linear 1280 → 1, sigmoid)
- Input: RGB image, 224×224, ImageNet-normalized
- Output: scalar logit; apply sigmoid for probability of graffiti
- Format: ONNX opset 17, INT8 dynamic-quantized weights (~5 MB)

## Intended use
Score Street View imagery for visible graffiti as a downstream filter/rank signal in the NOLA Lower Ninth Ward blight map. Not intended for legal evidence, person identification, or any use beyond binary visual classification.

## Out of scope
- Person or vehicle identification
- Detection of specific tags or attribution
- Use on imagery dissimilar to street-level photographs (drone, satellite, indoor)
- Real-time safety-critical decisions

## Training data
- Source: Kaggle `pinstripezebra/graffiti-classification`
- Samples: train 16509 / val 2063 / test 2065
- Class mapping: {0: 'no_graffiti', 1: 'graffiti'}

## Evaluation
| metric | value |
|---|---|
| Best val AUC | 0.9870 |
| Test AUC | 0.9898 |
| Test accuracy | 0.9947 |
| Test precision | 0.9583 |
| Test recall | 0.8364 |

Confusion matrix: see `confusion_matrix.png`.

## Limitations & biases
- Domain shift: training images are not Street View. Performance on Street View crops may be lower; verify with a hand-labeled sanity-check set before relying on the score.
- Class imbalance in source data may bias the threshold; the score is more reliable for ranking than for hard classification.
- Lighting, weather, and resolution of Street View tiles vary. The model has no exposure to nighttime imagery.

## License
Code: MIT (matches repo). Model weights: derived from Kaggle dataset; users must comply with that dataset's license terms.
