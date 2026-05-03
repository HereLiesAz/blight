# Graffiti Classifier Pipeline — Design

**Date:** 2026-05-03
**Status:** Approved — pending implementation plan

## Goal

Add a per-address graffiti-presence score (0.0–1.0) to the blight database so the map can both **filter** ("show only properties with graffiti") and **rank** (sort/color markers by score) the existing list of properties pulled from NOLA Open Data.

## Pipeline shape

```
Kaggle dataset ──[Colab notebook]──▶ EfficientNet-B0 fine-tuned ──▶ model.onnx (~20MB) ──▶ commit to main
                                            │
                                            └──▶ Google Drive (checkpoints, metrics, model card)

Sheet rows ──[GH Actions cron]──▶ scrape Street View tile ──▶ ONNX inference
                                                                    │
                                                                    ▼
                                  writes graffiti_score, panoid, classified_at to Sheet
                                                                    │
                                                                    ▼
                                                       index.html: filter + rank
```

## Components

### 1. Training notebook — `notebooks/train_graffiti_classifier.ipynb`

Single-button Colab notebook that produces a CPU-friendly ONNX model.

- **Setup:** mount Google Drive, install deps (`torch`, `torchvision`, `onnx`, `onnxruntime`, `kaggle`, `scikit-learn`), configure Kaggle API from `kaggle.json` (Colab secret or upload).
- **Data:** download `pinstripezebra/graffiti-classification`, EDA (class balance, image dims, sample grid), 80/10/10 split, ImageNet-normalized 224×224 transforms with light augmentation (horizontal flip, color jitter, random resize-crop).
- **Model:** torchvision `efficientnet_b0` (ImageNet weights). Replace classifier head with `Linear(1280 → 1) + Sigmoid`.
- **Train:** AdamW, cosine LR schedule, ~10 epochs, freeze backbone for first 3 epochs then unfreeze top blocks. Save best checkpoint by validation AUC.
- **Evaluate:** AUC, accuracy, precision/recall, confusion matrix, threshold sweep, sample misclassifications.
- **Export:** ONNX (FP32) + dynamic-range-quantized ONNX (~5 MB) for the GH Actions runner; also TorchScript for completeness.
- **Save to Drive:** `MyDrive/blight-graffiti/{run_id}/checkpoints/`, `final/model.onnx`, `final/quantized.onnx`, `metrics.json`, `model_card.md`, `confusion_matrix.png`.
- **Final cell:** prints `git add` / commit instructions to push `models/graffiti_classifier.onnx` to repo main.
- **Optional last cell:** small hand-labeled Street View crop set for a domain-shift sanity check.

### 2. Scraper — `scripts/streetview_scrape.py`

Direct-HTTP scraper of Google's internal panorama endpoints (no API key, no headless browser).

- Lat/lng → `photometa/v1` metadata → nearest `panoid`
- Fetch a single front-facing tile at fixed zoom (one image per address; we can add cardinal headings later if signal is weak)
- Throttle: 1 request / 3–5 s, exponential backoff, User-Agent rotation, retry on 429/CAPTCHA
- Cache panoid + image bytes on disk so a re-run does not re-scrape

### 3. Inference job — `scripts/classify_graffiti.py`

Sibling to `update_database.py`, runs after it.

- For each sheet row missing `graffiti_score` (or older than N days): scrape → run ONNX inference → write back.
- Resume-safe: idempotent, one address at a time.
- New sheet columns:
  - **L:** `graffiti_score` (float, 0–1)
  - **M:** `graffiti_panoid` (string)
  - **N:** `graffiti_classified_at` (UTC timestamp)
- Existing GH Actions cron picks it up.

### 4. UI — `index.html`

- Popup shows score (e.g. "Graffiti likelihood: 0.83").
- Toolbar adds: filter toggle (`graffiti only` / `no graffiti` / `all`) + sort/color toggle (rank markers by score).

### 5. Documentation deliverables

- **`MODEL_CARD.md`** at repo root — Hugging Face-style: model details, intended use & out-of-scope use, training data, evaluation metrics, biases & limitations, ethical considerations, license.
- **`docs/graffiti-pipeline.md`** — operator docs: how to retrain, how to invoke inference standalone, how to load the model in other projects (Python + onnxruntime, JS via onnxruntime-web), known failure modes for the scraper.

## Architecture decisions & rationale

| Decision | Choice | Why |
|---|---|---|
| Datasets | Kaggle `graffiti-classification` only | 17K-Graffiti requires application access; user opted out |
| Model | EfficientNet-B0 head fine-tune | Smallest viable accuracy/cost trade-off; ~20 MB ONNX, ~80 ms CPU inference |
| Quantization | Dynamic-range INT8 | Halves runtime model size and CPU latency on Actions runners |
| Output type | Continuous score (0–1) | Satisfies both filter (threshold) and rank (sort) use-cases |
| Images per address | 1 (front-facing) | Simplicity & throttling budget; revisit if signal weak |
| Inference platform | GH Actions, CPU-only | Matches existing `update_database.py` cron pattern, free, reproducible |
| Imagery source | Google Street View, scraped via direct HTTP | Per user constraint (no API, no headless browser) |

## Risks

- **Scraping is ToS-violating and brittle.** Endpoint paths and response shapes can change without notice. The scraper must fail gracefully; the Actions job must keep running even if a batch fails.
- **Domain shift.** Kaggle data is not Street View. Closeup tag photos may not generalize to crops of houses seen from across the street. Mitigation: optional small hand-labeled sanity-check set in the notebook before declaring the model production-ready.
- **Rate limits / CAPTCHA.** Aggressive throttling and exponential backoff are mandatory. If the runner IP is blocked, the job pauses gracefully rather than corrupting the sheet.
- **Privacy.** Pipeline runs server-side only; no user data involved. The README's strict no-tracking rules continue to apply unchanged.

## Out of scope (for now)

- Detection (bounding boxes) — overkill for the filter/rank use-case; classifier score is sufficient.
- Multi-heading capture (4 cardinal directions per address) — adds 4× scraping cost; revisit only if single-heading accuracy disappoints.
- Other visual signals (boarded windows, overgrowth, fire damage). The pipeline is structured so additional classifiers can be added later without redesign.
- On-demand inference (live API call when user taps a marker). Static enrichment is sufficient.
