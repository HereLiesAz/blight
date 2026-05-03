# Graffiti Classifier — Model Card

**Run ID:** `20260503-183145`
**Date:** 2026-05-03T19:56:13.858140Z

## Model details
- Architecture: EfficientNet-B0 with binary classification head (Linear 1280 → 1, sigmoid)
- Input: RGB image, 224×224, ImageNet-normalized
- Output: scalar logit; apply sigmoid for probability of graffiti
- Format: ONNX opset 17, FP32 weights (~16 MB total: `models/model.onnx` + `models/model.onnx.data` external-data sidecar). An INT8-quantized variant is also produced by the notebook at `final/quantized.onnx` (~5 MB) — substitute it for faster CPU inference if accuracy holds.

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
- Class mapping: `{0: 'no_graffiti', 1: 'graffiti'}`
- Severe class imbalance: ~2.6% positive class in test split (55 of 2065)

## Evaluation
| metric | value |
|---|---|
| Best val AUC | 0.9870 |
| Test AUC | 0.9898 |
| Test accuracy | 0.9947 |
| Test precision | 0.9583 |
| Test recall | 0.8364 |

Confusion matrix: see [`models/confusion_matrix.png`](models/confusion_matrix.png). Full per-epoch history in [`models/metrics.json`](models/metrics.json).

The high precision and AUC make the score reliable for **ranking** addresses by graffiti likelihood. The lower recall (0.836) reflects the class imbalance — the model misses some positives — so a hard threshold for binary classification should be tuned per use-case rather than relying on the default 0.5.

## Limitations & biases
- **Domain shift:** training images are not Street View. Performance on Street View crops may be lower; verify with a hand-labeled sanity-check set before relying on the score.
- **Class imbalance:** the source dataset is heavily skewed toward negatives (~97% no-graffiti). The score is more reliable for ranking than for hard classification.
- **Lighting, weather, resolution** of Street View tiles vary. The model has no exposure to nighttime imagery.

## License
Code: MIT (matches repo). Model weights: derived from Kaggle dataset; users must comply with that dataset's license terms.
