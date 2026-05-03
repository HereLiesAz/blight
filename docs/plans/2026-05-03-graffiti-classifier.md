# Graffiti Classifier Pipeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a per-address graffiti-presence score (0–1) to the blight database so the map can filter and rank properties by visible graffiti, derived from Google Street View imagery and a small CNN classifier trained on the Kaggle `pinstripezebra/graffiti-classification` dataset.

**Architecture:** A Colab notebook fine-tunes EfficientNet-B0 on Kaggle data, exports a quantized ONNX model (~5 MB), and saves checkpoints + a model card to Google Drive. The user commits the ONNX file to `main`. A scraper hits Google's internal Street View panorama/tile endpoints (no API, no headless browser) to fetch one image per address. An inference job — invoked from the existing GitHub Actions cron — runs ONNX on each scraped image and writes `graffiti_score`, `graffiti_panoid`, and `graffiti_classified_at` columns back to the existing Google Sheet. The static `index.html` reads the new columns to provide a filter toggle and rank-by-score view.

**Tech Stack:** Python 3.11, PyTorch + torchvision, ONNX Runtime, gspread (already in repo), pytest + responses (HTTP mocking), Leaflet.js (already in repo), GitHub Actions.

**Reference design:** [docs/plans/2026-05-03-graffiti-classifier-design.md](2026-05-03-graffiti-classifier-design.md)

---

## Conventions

- **Directory layout** introduced by this plan:
  - `notebooks/train_graffiti_classifier.ipynb`
  - `models/.gitkeep` (the actual `graffiti_classifier.onnx` is committed by the user after training)
  - `scripts/streetview_scrape.py`
  - `scripts/classify_graffiti.py`
  - `scripts/lib/` — shared modules importable by `scripts/*.py` (`__init__.py`, `streetview.py`, `inference.py`, `sheet.py`)
  - `tests/` — pytest tests (`test_streetview.py`, `test_inference.py`, `test_sheet.py`)
  - `tests/fixtures/` — recorded HTTP fixtures and a stub ONNX model
  - `docs/graffiti-pipeline.md`
  - `MODEL_CARD.md` (root)

- **Commit style** matches existing history (`Add ...`, `Update ...`, no Co-Authored-By unless asked elsewhere). Each task ends with a commit.

- **Tests run with:** `pytest -v` from repo root.

- **Forward slashes** in commands. Where Windows-specific bash quirks matter, the step calls them out.

---

# Part A — Repo scaffolding

## Task A1: Create directory structure and update requirements

**Files:**
- Create: `models/.gitkeep`
- Create: `scripts/lib/__init__.py`
- Create: `tests/__init__.py`
- Create: `tests/fixtures/.gitkeep`
- Create: `notebooks/.gitkeep`
- Modify: `scripts/requirements.txt`
- Modify: `.gitignore`

**Step 1: Create empty placeholder files**

```bash
mkdir -p models notebooks scripts/lib tests/fixtures
touch models/.gitkeep notebooks/.gitkeep tests/fixtures/.gitkeep
```

**Step 2: Create `scripts/lib/__init__.py`**

```python
"""Shared library code for blight pipeline scripts."""
```

**Step 3: Create `tests/__init__.py`** (empty file).

**Step 4: Append to `scripts/requirements.txt`**

```
onnxruntime==1.20.1
numpy==2.1.3
Pillow==11.0.0
pytest==8.3.4
responses==0.25.3
```

**Step 5: Append to `.gitignore`**

```
# ML pipeline
models/*.onnx
models/*.pt
.cache/
tests/fixtures/*.onnx
__pycache__/
.pytest_cache/
```

**Note:** `models/*.onnx` is gitignored at first so we don't commit accidental large files; the trained model is added with `git add -f` once the user finishes training. The plan calls this out at the relevant step.

**Step 6: Commit**

```bash
git add models/.gitkeep notebooks/.gitkeep scripts/lib/__init__.py tests/__init__.py tests/fixtures/.gitkeep scripts/requirements.txt .gitignore
git commit -m "Add scaffolding for graffiti classifier pipeline"
```

---

## Task A2: Configure pytest

**Files:**
- Create: `pytest.ini`

**Step 1: Create `pytest.ini`**

```ini
[pytest]
testpaths = tests
python_files = test_*.py
python_classes = Test*
python_functions = test_*
addopts = -ra --strict-markers
markers =
    network: marks tests that hit external services (deselect with -m 'not network')
```

**Step 2: Verify pytest discovery**

```bash
pip install -r scripts/requirements.txt
pytest --collect-only
```

Expected: `collected 0 items` (no errors).

**Step 3: Commit**

```bash
git add pytest.ini
git commit -m "Configure pytest"
```

---

# Part B — Training notebook

The notebook is a one-shot artifact run by a human in Colab. We do not unit-test it. We *do* validate that the file is syntactically a notebook and that each cell's source is what we intend.

## Task B1: Write the training notebook

**Files:**
- Create: `notebooks/train_graffiti_classifier.ipynb`

**Step 1: Create the notebook file**

Write a Jupyter notebook with the following cells in order. Use the `nbformat` Python library to construct it programmatically so the JSON is valid:

```python
# Run this once locally to generate the .ipynb file
import nbformat as nbf

nb = nbf.v4.new_notebook()
cells = []
```

Cells (each appended via `cells.append(nbf.v4.new_markdown_cell(...))` or `nbf.v4.new_code_cell(...)`):

**Cell 1 — Markdown (title & overview):**

```markdown
# Train Graffiti Classifier

Fine-tunes EfficientNet-B0 on the Kaggle `pinstripezebra/graffiti-classification` dataset and exports a CPU-friendly quantized ONNX model for use in the blight pipeline's GitHub Actions runner.

**Outputs (saved to `MyDrive/blight-graffiti/<run_id>/`):**
- `final/model.onnx` — FP32 ONNX
- `final/quantized.onnx` — INT8-quantized ONNX (commit this to the repo at `models/graffiti_classifier.onnx`)
- `final/metrics.json` — AUC, accuracy, precision, recall, confusion matrix
- `final/model_card.md` — generated model card
- `checkpoints/epoch_*.pt` — per-epoch PyTorch checkpoints

**Runtime:** ~10 minutes on a Colab T4.
```

**Cell 2 — Code (setup & Drive mount):**

```python
!pip -q install kaggle onnx onnxruntime
from google.colab import drive
drive.mount('/content/drive')

import os, json, time, shutil, pathlib, datetime
RUN_ID = datetime.datetime.utcnow().strftime("%Y%m%d-%H%M%S")
DRIVE_BASE = pathlib.Path('/content/drive/MyDrive/blight-graffiti') / RUN_ID
(DRIVE_BASE / 'checkpoints').mkdir(parents=True, exist_ok=True)
(DRIVE_BASE / 'final').mkdir(parents=True, exist_ok=True)
print(f"Run output: {DRIVE_BASE}")
```

**Cell 3 — Code (Kaggle credentials):**

```python
# Either upload kaggle.json via the Files panel OR set Colab secrets.
# We accept both.
from pathlib import Path
KAGGLE_DIR = Path.home() / '.kaggle'
KAGGLE_DIR.mkdir(exist_ok=True)
kjson = KAGGLE_DIR / 'kaggle.json'

try:
    from google.colab import userdata
    kaggle_secret = userdata.get('KAGGLE_JSON')
    if kaggle_secret:
        kjson.write_text(kaggle_secret)
except Exception:
    pass

if not kjson.exists():
    from google.colab import files
    print("Upload kaggle.json")
    uploaded = files.upload()
    for name, data in uploaded.items():
        kjson.write_bytes(data)

os.chmod(kjson, 0o600)
print("Kaggle credentials configured.")
```

**Cell 4 — Code (download dataset):**

```python
DATA_DIR = pathlib.Path('/content/data/graffiti')
DATA_DIR.mkdir(parents=True, exist_ok=True)
!kaggle datasets download -d pinstripezebra/graffiti-classification -p {DATA_DIR} --unzip
!ls {DATA_DIR}
```

**Cell 5 — Code (EDA):**

```python
import collections, random
from PIL import Image
import matplotlib.pyplot as plt

def find_class_dirs(root):
    return {p.name: p for p in pathlib.Path(root).rglob('*') if p.is_dir() and not p.name.startswith('.') and any(c.suffix.lower() in {'.jpg', '.jpeg', '.png'} for c in p.iterdir() if c.is_file())}

class_dirs = find_class_dirs(DATA_DIR)
print("Classes:", {k: len(list(v.glob('*'))) for k, v in class_dirs.items()})

# Sample grid
fig, axes = plt.subplots(2, 4, figsize=(12, 6))
for ax, (name, d) in zip(axes.flat, list(class_dirs.items()) * 4):
    sample = random.choice(list(d.glob('*.*')))
    ax.imshow(Image.open(sample))
    ax.set_title(name)
    ax.axis('off')
plt.tight_layout(); plt.show()
```

**Note in plan:** the Kaggle dataset's directory layout may differ from a clean `class_a/`, `class_b/` structure. The cell above is defensive. The user may need to map directory names to labels in Cell 6 — leave a clear `LABEL_MAP = {...}` at the top of Cell 6 for them to adjust.

**Cell 6 — Code (datasets & dataloaders):**

```python
import torch
from torch.utils.data import DataLoader, random_split, Dataset
from torchvision import transforms
from PIL import Image

# EDIT IF NEEDED — map directory name → class label (1 = graffiti, 0 = no graffiti)
LABEL_MAP = {name: (1 if 'graffiti' in name.lower() and 'no' not in name.lower() else 0) for name in class_dirs}
print("Label map:", LABEL_MAP)
assert set(LABEL_MAP.values()) == {0, 1}, "Adjust LABEL_MAP — must contain both classes"

class GraffitiDataset(Dataset):
    def __init__(self, items, transform):
        self.items = items; self.transform = transform
    def __len__(self): return len(self.items)
    def __getitem__(self, idx):
        path, label = self.items[idx]
        img = Image.open(path).convert('RGB')
        return self.transform(img), torch.tensor(label, dtype=torch.float32)

items = [(p, LABEL_MAP[d.name]) for d in class_dirs.values() for p in d.glob('*.*') if p.suffix.lower() in {'.jpg', '.jpeg', '.png'}]
print(f"Total samples: {len(items)}")

IMAGENET_MEAN = [0.485, 0.456, 0.406]; IMAGENET_STD = [0.229, 0.224, 0.225]
train_tf = transforms.Compose([
    transforms.RandomResizedCrop(224, scale=(0.7, 1.0)),
    transforms.RandomHorizontalFlip(),
    transforms.ColorJitter(0.2, 0.2, 0.2, 0.05),
    transforms.ToTensor(),
    transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
])
eval_tf = transforms.Compose([
    transforms.Resize(256), transforms.CenterCrop(224),
    transforms.ToTensor(), transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
])

random.Random(42).shuffle(items)
n = len(items); n_train = int(0.8 * n); n_val = int(0.1 * n)
train_items = items[:n_train]; val_items = items[n_train:n_train+n_val]; test_items = items[n_train+n_val:]

class TransformWrap(Dataset):
    def __init__(self, ds, tf): self.ds = ds; self.tf = tf
    def __len__(self): return len(self.ds)
    def __getitem__(self, i): return self.tf(Image.open(self.ds[i][0]).convert('RGB')), torch.tensor(self.ds[i][1], dtype=torch.float32)

train_ds = TransformWrap(train_items, train_tf)
val_ds   = TransformWrap(val_items, eval_tf)
test_ds  = TransformWrap(test_items, eval_tf)

train_loader = DataLoader(train_ds, batch_size=64, shuffle=True, num_workers=2, pin_memory=True)
val_loader   = DataLoader(val_ds,   batch_size=64, num_workers=2, pin_memory=True)
test_loader  = DataLoader(test_ds,  batch_size=64, num_workers=2, pin_memory=True)
```

**Cell 7 — Code (model definition):**

```python
from torchvision.models import efficientnet_b0, EfficientNet_B0_Weights
import torch.nn as nn

device = 'cuda' if torch.cuda.is_available() else 'cpu'
model = efficientnet_b0(weights=EfficientNet_B0_Weights.IMAGENET1K_V1)
model.classifier = nn.Sequential(nn.Dropout(p=0.2), nn.Linear(1280, 1))
model = model.to(device)
print(f"Device: {device}")
```

**Cell 8 — Code (training loop with frozen-then-unfrozen backbone):**

```python
import torch.optim as optim

def set_backbone_grad(m, requires_grad):
    for p in m.features.parameters():
        p.requires_grad = requires_grad

def step(loader, training):
    losses, preds, labels = [], [], []
    model.train() if training else model.eval()
    ctx = torch.enable_grad() if training else torch.no_grad()
    with ctx:
        for x, y in loader:
            x, y = x.to(device), y.to(device)
            logits = model(x).squeeze(1)
            loss = criterion(logits, y)
            if training:
                opt.zero_grad(); loss.backward(); opt.step()
            losses.append(loss.item()); preds.append(torch.sigmoid(logits).detach().cpu()); labels.append(y.cpu())
    import torch as _t
    return sum(losses)/len(losses), _t.cat(preds), _t.cat(labels)

EPOCHS = 10; FREEZE_EPOCHS = 3
criterion = nn.BCEWithLogitsLoss()
opt = optim.AdamW(filter(lambda p: p.requires_grad, model.parameters()), lr=1e-3, weight_decay=1e-4)
scheduler = optim.lr_scheduler.CosineAnnealingLR(opt, T_max=EPOCHS)

set_backbone_grad(model, False)
best_auc = -1; history = []
from sklearn.metrics import roc_auc_score, accuracy_score, precision_score, recall_score, confusion_matrix

for epoch in range(EPOCHS):
    if epoch == FREEZE_EPOCHS:
        set_backbone_grad(model, True)
        opt = optim.AdamW(model.parameters(), lr=1e-4, weight_decay=1e-4)
        scheduler = optim.lr_scheduler.CosineAnnealingLR(opt, T_max=EPOCHS - FREEZE_EPOCHS)
    tr_loss, _, _ = step(train_loader, True)
    val_loss, vp, vl = step(val_loader, False)
    val_auc = roc_auc_score(vl, vp)
    history.append({'epoch': epoch, 'train_loss': tr_loss, 'val_loss': val_loss, 'val_auc': val_auc})
    print(f"epoch {epoch:02d}  train {tr_loss:.4f}  val {val_loss:.4f}  AUC {val_auc:.4f}")
    torch.save(model.state_dict(), DRIVE_BASE / 'checkpoints' / f'epoch_{epoch:02d}.pt')
    if val_auc > best_auc:
        best_auc = val_auc
        torch.save(model.state_dict(), DRIVE_BASE / 'checkpoints' / 'best.pt')
    scheduler.step()
```

**Cell 9 — Code (test-set evaluation):**

```python
model.load_state_dict(torch.load(DRIVE_BASE / 'checkpoints' / 'best.pt'))
_, tp, tl = step(test_loader, False)
test_auc = roc_auc_score(tl, tp)
test_acc = accuracy_score(tl, (tp > 0.5).int())
test_prec = precision_score(tl, (tp > 0.5).int())
test_rec = recall_score(tl, (tp > 0.5).int())
cm = confusion_matrix(tl, (tp > 0.5).int()).tolist()
metrics = {'best_val_auc': best_auc, 'test_auc': test_auc, 'test_accuracy': test_acc,
           'test_precision': test_prec, 'test_recall': test_rec, 'confusion_matrix': cm,
           'history': history, 'n_train': len(train_items), 'n_val': len(val_items), 'n_test': len(test_items)}
print(json.dumps(metrics, indent=2))
(DRIVE_BASE / 'final' / 'metrics.json').write_text(json.dumps(metrics, indent=2))

import matplotlib.pyplot as plt
fig, ax = plt.subplots(figsize=(4, 4))
ax.imshow(cm, cmap='Blues'); ax.set_xlabel('predicted'); ax.set_ylabel('true')
for (i, j), v in [((i, j), cm[i][j]) for i in range(2) for j in range(2)]:
    ax.text(j, i, str(v), ha='center', va='center')
plt.savefig(DRIVE_BASE / 'final' / 'confusion_matrix.png', bbox_inches='tight'); plt.show()
```

**Cell 10 — Code (ONNX export & quantization):**

```python
import torch.onnx
from onnxruntime.quantization import quantize_dynamic, QuantType

model.eval()
dummy = torch.randn(1, 3, 224, 224, device=device)
fp32_path = DRIVE_BASE / 'final' / 'model.onnx'
torch.onnx.export(model, dummy, str(fp32_path),
                  input_names=['input'], output_names=['logits'],
                  dynamic_axes={'input': {0: 'batch'}, 'logits': {0: 'batch'}},
                  opset_version=17)

q_path = DRIVE_BASE / 'final' / 'quantized.onnx'
quantize_dynamic(str(fp32_path), str(q_path), weight_type=QuantType.QUInt8)
print(f"FP32: {fp32_path.stat().st_size/1e6:.1f} MB; INT8: {q_path.stat().st_size/1e6:.1f} MB")
```

**Cell 11 — Code (model card generation):**

```python
card = f"""# Graffiti Classifier — Model Card

**Run ID:** `{RUN_ID}`
**Date:** {datetime.datetime.utcnow().isoformat()}Z

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
- Samples: train {len(train_items)} / val {len(val_items)} / test {len(test_items)}
- Class mapping: {LABEL_MAP}

## Evaluation
| metric | value |
|---|---|
| Best val AUC | {best_auc:.4f} |
| Test AUC | {test_auc:.4f} |
| Test accuracy | {test_acc:.4f} |
| Test precision | {test_prec:.4f} |
| Test recall | {test_rec:.4f} |

Confusion matrix: see `confusion_matrix.png`.

## Limitations & biases
- Domain shift: training images are not Street View. Performance on Street View crops may be lower; verify with a hand-labeled sanity-check set before relying on the score.
- Class imbalance in source data may bias the threshold; the score is more reliable for ranking than for hard classification.
- Lighting, weather, and resolution of Street View tiles vary. The model has no exposure to nighttime imagery.

## License
Code: MIT (matches repo). Model weights: derived from Kaggle dataset; users must comply with that dataset's license terms.
"""
(DRIVE_BASE / 'final' / 'model_card.md').write_text(card)
print(card)
```

**Cell 12 — Code (commit instructions):**

```python
print(f"""
Training complete. To deploy this model:

1. Download from Drive:
   {DRIVE_BASE / 'final' / 'quantized.onnx'}
   {DRIVE_BASE / 'final' / 'model_card.md'}

2. In your local repo:
   cp <downloaded>/quantized.onnx models/graffiti_classifier.onnx
   cp <downloaded>/model_card.md MODEL_CARD.md
   git add -f models/graffiti_classifier.onnx
   git add MODEL_CARD.md
   git commit -m 'Add trained graffiti classifier (run {RUN_ID})'
   git push origin main
""")
```

**Step 2: Save the notebook**

```python
nbf.write(nb, 'notebooks/train_graffiti_classifier.ipynb')
```

**Step 3: Validate the notebook**

```bash
python -c "import nbformat; nbformat.read('notebooks/train_graffiti_classifier.ipynb', as_version=4)"
```

Expected: no output, no error.

**Step 4: Commit**

```bash
git add notebooks/train_graffiti_classifier.ipynb
git commit -m "Add Colab notebook for training graffiti classifier"
```

---

# Part C — Street View scraper (TDD)

The scraper hits two Google endpoints (`SingleImageSearch` for panoid lookup, `streetviewpixels-pa.googleapis.com/v1/tile` for image bytes). All HTTP is mocked in tests via the `responses` library; one end-to-end live test is marked `@pytest.mark.network` and is opt-in.

## Task C1: Test panoid lookup

**Files:**
- Create: `tests/fixtures/photometa_response.txt`
- Create: `tests/test_streetview.py`

**Step 1: Capture a representative response**

Save a known-good response body from `https://maps.googleapis.com/maps/api/js/GeoPhotoService.SingleImageSearch?...` (the response is a JSON-with-prefix string starting with `)]}'`). For testing we hand-craft a minimal one — copy the structure below verbatim into `tests/fixtures/photometa_response.txt`:

```
)]}'
[["apiv3"],[[[2,"TEST_PANOID_ABC123"],null,[[29.964,-90.007]]]]]
```

**Step 2: Write the failing test**

```python
# tests/test_streetview.py
import pytest
import responses
from scripts.lib.streetview import lookup_panoid, PanoramaNotFound, SINGLE_IMAGE_SEARCH_URL

@responses.activate
def test_lookup_panoid_returns_panoid_id():
    body = open('tests/fixtures/photometa_response.txt').read()
    responses.add(responses.GET, SINGLE_IMAGE_SEARCH_URL, body=body, status=200)
    assert lookup_panoid(29.964, -90.007) == "TEST_PANOID_ABC123"

@responses.activate
def test_lookup_panoid_raises_when_no_pano():
    body = ")]}'\n[[\"apiv3\"],[]]"
    responses.add(responses.GET, SINGLE_IMAGE_SEARCH_URL, body=body, status=200)
    with pytest.raises(PanoramaNotFound):
        lookup_panoid(0.0, 0.0)
```

**Step 3: Run — verify failure**

```bash
pytest tests/test_streetview.py -v
```

Expected: ImportError / ModuleNotFoundError on `scripts.lib.streetview`.

**Step 4: Implement panoid lookup**

```python
# scripts/lib/streetview.py
"""Direct-HTTP scraping of Google Street View panorama metadata and tiles.

Bypasses the Static Street View API. Endpoints are unofficial; expect breakage.
"""
from __future__ import annotations
import json
import re
import requests

SINGLE_IMAGE_SEARCH_URL = "https://maps.googleapis.com/maps/api/js/GeoPhotoService.SingleImageSearch"

class PanoramaNotFound(Exception):
    """No Street View panorama exists near the given lat/lng."""

def _strip_xss_prefix(body: str) -> str:
    return re.sub(r"^\)\]\}'\s*", "", body, count=1)

def lookup_panoid(lat: float, lng: float, *, radius_m: int = 50, session: requests.Session | None = None, timeout: float = 10.0) -> str:
    """Return the nearest Street View panoid for (lat, lng), or raise PanoramaNotFound."""
    pb = (
        f"!1m5!1sapiv3!5sUS!11m2!1m1!1b0!2m4!1m2!3d{lat}!4d{lng}!2d{radius_m}"
        "!3m10!2m2!1sen!2sUS!9m1!1e2!11m4!1m3!1e2!2b1!3e2"
        "!4m10!1e1!1e2!1e3!1e4!1e8!1e6!5m1!1e0!6m1!1e1"
    )
    s = session or requests
    r = s.get(SINGLE_IMAGE_SEARCH_URL, params={"pb": pb}, timeout=timeout)
    r.raise_for_status()
    data = json.loads(_strip_xss_prefix(r.text))
    try:
        return data[1][0][0][1]
    except (TypeError, IndexError, KeyError):
        raise PanoramaNotFound(f"No panorama near ({lat}, {lng})")
```

**Step 5: Run — verify pass**

```bash
pytest tests/test_streetview.py -v
```

Expected: 2 passed.

**Step 6: Commit**

```bash
git add scripts/lib/streetview.py tests/test_streetview.py tests/fixtures/photometa_response.txt
git commit -m "Add Street View panoid lookup"
```

---

## Task C2: Test tile fetch

**Files:**
- Create: `tests/fixtures/sample_tile.jpg` (any 256×256 JPEG, ~5 KB)
- Modify: `scripts/lib/streetview.py`
- Modify: `tests/test_streetview.py`

**Step 1: Generate a fixture JPEG**

```bash
python -c "from PIL import Image; Image.new('RGB',(256,256),(128,64,32)).save('tests/fixtures/sample_tile.jpg', 'JPEG')"
```

**Step 2: Append to `tests/test_streetview.py`**

```python
from scripts.lib.streetview import fetch_tile, TILE_URL

@responses.activate
def test_fetch_tile_returns_bytes():
    body = open('tests/fixtures/sample_tile.jpg', 'rb').read()
    responses.add(responses.GET, TILE_URL, body=body, status=200, content_type='image/jpeg')
    out = fetch_tile("PANO_X", x=0, y=0, zoom=0)
    assert out == body
    assert out[:3] == b'\xff\xd8\xff'  # JPEG magic
```

**Step 3: Run — verify failure**

```bash
pytest tests/test_streetview.py::test_fetch_tile_returns_bytes -v
```

Expected: ImportError on `fetch_tile` / `TILE_URL`.

**Step 4: Implement tile fetch in `scripts/lib/streetview.py`**

```python
TILE_URL = "https://streetviewpixels-pa.googleapis.com/v1/tile"

def fetch_tile(panoid: str, *, x: int = 0, y: int = 0, zoom: int = 0,
               session: requests.Session | None = None, timeout: float = 10.0) -> bytes:
    """Fetch a single Street View panorama tile as JPEG bytes."""
    s = session or requests
    r = s.get(TILE_URL,
              params={"cb_client": "maps_sv.tactile", "panoid": panoid,
                      "x": x, "y": y, "zoom": zoom, "nbt": 1, "fover": 2},
              timeout=timeout)
    r.raise_for_status()
    return r.content
```

**Step 5: Run — verify pass**

```bash
pytest tests/test_streetview.py -v
```

Expected: 3 passed.

**Step 6: Commit**

```bash
git add scripts/lib/streetview.py tests/test_streetview.py tests/fixtures/sample_tile.jpg
git commit -m "Add Street View tile fetch"
```

---

## Task C3: Throttling & retry

**Files:**
- Modify: `scripts/lib/streetview.py`
- Modify: `tests/test_streetview.py`

**Step 1: Append to `tests/test_streetview.py`**

```python
import time as _time
from scripts.lib.streetview import ScraperSession

@responses.activate
def test_scraper_retries_on_429(monkeypatch):
    sleeps = []
    monkeypatch.setattr(_time, 'sleep', lambda s: sleeps.append(s))
    responses.add(responses.GET, TILE_URL, status=429)
    responses.add(responses.GET, TILE_URL, status=429)
    responses.add(responses.GET, TILE_URL, body=b'\xff\xd8\xff\xe0', status=200)
    sess = ScraperSession(min_interval_s=0, max_retries=3, backoff_base=0.0)
    out = sess.fetch_tile("PANO_X")
    assert out.startswith(b'\xff\xd8\xff')
    assert len(sleeps) >= 2  # at least one back-off sleep per retry

@responses.activate
def test_scraper_throttles_between_requests(monkeypatch):
    sleeps = []
    monkeypatch.setattr(_time, 'sleep', lambda s: sleeps.append(s))
    responses.add(responses.GET, TILE_URL, body=b'\xff\xd8\xff', status=200)
    responses.add(responses.GET, TILE_URL, body=b'\xff\xd8\xff', status=200)
    sess = ScraperSession(min_interval_s=3.0, max_retries=0, backoff_base=0.0)
    sess.fetch_tile("A"); sess.fetch_tile("B")
    assert any(s >= 2.5 for s in sleeps), f"expected a throttle sleep ~3s, got {sleeps}"
```

**Step 2: Run — verify failure**

```bash
pytest tests/test_streetview.py -v
```

Expected: ImportError on `ScraperSession`.

**Step 3: Implement `ScraperSession` in `scripts/lib/streetview.py`**

```python
import time
import random

DEFAULT_USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
]

class ScraperSession:
    """Throttled, retrying HTTP session for Street View scraping."""
    def __init__(self, *, min_interval_s: float = 3.0, max_retries: int = 3,
                 backoff_base: float = 1.5, user_agents: list[str] | None = None):
        self.min_interval_s = min_interval_s
        self.max_retries = max_retries
        self.backoff_base = backoff_base
        self.user_agents = user_agents or DEFAULT_USER_AGENTS
        self._session = requests.Session()
        self._last_request_at = 0.0

    def _throttle(self):
        elapsed = time.time() - self._last_request_at
        if elapsed < self.min_interval_s:
            time.sleep(self.min_interval_s - elapsed)

    def _get(self, url: str, params: dict, timeout: float = 10.0) -> requests.Response:
        for attempt in range(self.max_retries + 1):
            self._throttle()
            self._session.headers["User-Agent"] = random.choice(self.user_agents)
            r = self._session.get(url, params=params, timeout=timeout)
            self._last_request_at = time.time()
            if r.status_code == 200:
                return r
            if r.status_code in (429, 503) and attempt < self.max_retries:
                time.sleep(self.backoff_base * (2 ** attempt) + random.uniform(0, 0.5))
                continue
            r.raise_for_status()
        raise RuntimeError(f"Exhausted retries for {url}")

    def lookup_panoid(self, lat: float, lng: float, *, radius_m: int = 50) -> str:
        pb = (f"!1m5!1sapiv3!5sUS!11m2!1m1!1b0!2m4!1m2!3d{lat}!4d{lng}!2d{radius_m}"
              "!3m10!2m2!1sen!2sUS!9m1!1e2!11m4!1m3!1e2!2b1!3e2"
              "!4m10!1e1!1e2!1e3!1e4!1e8!1e6!5m1!1e0!6m1!1e1")
        r = self._get(SINGLE_IMAGE_SEARCH_URL, {"pb": pb})
        data = json.loads(_strip_xss_prefix(r.text))
        try:
            return data[1][0][0][1]
        except (TypeError, IndexError, KeyError):
            raise PanoramaNotFound(f"No panorama near ({lat}, {lng})")

    def fetch_tile(self, panoid: str, *, x: int = 0, y: int = 0, zoom: int = 0) -> bytes:
        r = self._get(TILE_URL, {"cb_client": "maps_sv.tactile", "panoid": panoid,
                                 "x": x, "y": y, "zoom": zoom, "nbt": 1, "fover": 2})
        return r.content
```

**Step 4: Run — verify pass**

```bash
pytest tests/test_streetview.py -v
```

Expected: 5 passed.

**Step 5: Commit**

```bash
git add scripts/lib/streetview.py tests/test_streetview.py
git commit -m "Add throttled retry session for Street View scraper"
```

---

## Task C4: CLI entrypoint

**Files:**
- Create: `scripts/streetview_scrape.py`

**Step 1: Implement CLI**

```python
"""Standalone Street View scraper for ad-hoc use.

Usage:
  python scripts/streetview_scrape.py --lat 29.964 --lng -90.007 --out cache/sample.jpg
"""
from __future__ import annotations
import argparse, pathlib, sys
from scripts.lib.streetview import ScraperSession, PanoramaNotFound

def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--lat", type=float, required=True)
    p.add_argument("--lng", type=float, required=True)
    p.add_argument("--zoom", type=int, default=0)
    p.add_argument("--out", type=pathlib.Path, required=True)
    p.add_argument("--min-interval", type=float, default=3.0)
    args = p.parse_args(argv)

    sess = ScraperSession(min_interval_s=args.min_interval)
    try:
        panoid = sess.lookup_panoid(args.lat, args.lng)
    except PanoramaNotFound as e:
        print(f"no panorama: {e}", file=sys.stderr); return 2

    img = sess.fetch_tile(panoid, zoom=args.zoom)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(img)
    print(f"panoid={panoid} bytes={len(img)} → {args.out}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
```

**Step 2: Smoke-test CLI argument parsing**

```bash
python scripts/streetview_scrape.py --help
```

Expected: usage message, exit 0.

**Step 3: Commit**

```bash
git add scripts/streetview_scrape.py
git commit -m "Add CLI for ad-hoc Street View scraping"
```

---

# Part D — Inference module (TDD)

## Task D1: Create a stub ONNX model for tests

**Files:**
- Create: `tests/conftest.py`

**Step 1: Write `tests/conftest.py`**

```python
"""Test fixtures shared across the suite."""
import pathlib
import pytest

@pytest.fixture(scope="session")
def stub_onnx_path(tmp_path_factory):
    """Tiny ONNX model: takes 1x3x224x224, returns deterministic logit per image (mean of pixels - 0.5)."""
    import numpy as np
    import onnx
    from onnx import helper, TensorProto

    inp = helper.make_tensor_value_info('input', TensorProto.FLOAT, [None, 3, 224, 224])
    out = helper.make_tensor_value_info('logits', TensorProto.FLOAT, [None, 1])

    # ReduceMean over (1,2,3) -> shape [N,1,1,1]; subtract 0.5; flatten to [N,1]
    rm = helper.make_node('ReduceMean', ['input'], ['m'], axes=[1, 2, 3], keepdims=1)
    half_const = helper.make_node('Constant', [], ['half'],
                                   value=helper.make_tensor('h', TensorProto.FLOAT, [1], [0.5]))
    sub = helper.make_node('Sub', ['m', 'half'], ['s'])
    flatten = helper.make_node('Flatten', ['s'], ['logits'], axis=1)

    graph = helper.make_graph([rm, half_const, sub, flatten], 'stub', [inp], [out])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.checker.check_model(model)
    p = tmp_path_factory.mktemp("onnx") / "stub.onnx"
    onnx.save(model, str(p))
    return str(p)
```

**Step 2: Verify the fixture produces a valid model**

```bash
pytest --collect-only
```

Expected: still 5 collected (or current count) — this just confirms no syntax error.

**Step 3: Commit**

```bash
git add tests/conftest.py
git commit -m "Add stub ONNX fixture for inference tests"
```

---

## Task D2: Test ONNX inference wrapper

**Files:**
- Create: `tests/test_inference.py`
- Modify: `scripts/requirements.txt` (add `onnx==1.17.0` for the conftest fixture)

**Step 1: Add `onnx==1.17.0` to `scripts/requirements.txt`** (above `onnxruntime`).

**Step 2: Write the failing test**

```python
# tests/test_inference.py
import numpy as np
from PIL import Image
from io import BytesIO
import pytest
from scripts.lib.inference import GraffitiClassifier

def _make_jpeg(value: int) -> bytes:
    img = Image.new('RGB', (300, 300), (value, value, value))
    buf = BytesIO(); img.save(buf, 'JPEG'); return buf.getvalue()

def test_classifier_returns_score_in_unit_interval(stub_onnx_path):
    clf = GraffitiClassifier(stub_onnx_path)
    score = clf.score(_make_jpeg(128))
    assert 0.0 <= score <= 1.0

def test_classifier_orders_dark_below_bright(stub_onnx_path):
    """Stub model: score = sigmoid(mean - 0.5). Brighter image → higher score."""
    clf = GraffitiClassifier(stub_onnx_path)
    s_dark = clf.score(_make_jpeg(40))
    s_bright = clf.score(_make_jpeg(220))
    assert s_dark < s_bright

def test_classifier_handles_corrupt_bytes(stub_onnx_path):
    clf = GraffitiClassifier(stub_onnx_path)
    with pytest.raises(ValueError):
        clf.score(b'not-an-image')
```

**Step 3: Run — verify failure**

```bash
pytest tests/test_inference.py -v
```

Expected: ImportError on `scripts.lib.inference`.

**Step 4: Implement the wrapper**

```python
# scripts/lib/inference.py
"""ONNX-runtime wrapper for the graffiti classifier."""
from __future__ import annotations
import numpy as np
import onnxruntime as ort
from PIL import Image, UnidentifiedImageError
from io import BytesIO

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

def _preprocess(jpeg_bytes: bytes) -> np.ndarray:
    try:
        img = Image.open(BytesIO(jpeg_bytes)).convert('RGB')
    except UnidentifiedImageError as e:
        raise ValueError(f"not a decodable image: {e}") from e
    img = img.resize((256, 256))
    left = (256 - 224) // 2
    img = img.crop((left, left, left + 224, left + 224))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    arr = (arr - IMAGENET_MEAN) / IMAGENET_STD
    arr = arr.transpose(2, 0, 1)[None, ...]  # NCHW
    return arr.astype(np.float32)

def _sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-x))

class GraffitiClassifier:
    def __init__(self, model_path: str):
        self.session = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
        self.input_name = self.session.get_inputs()[0].name

    def score(self, jpeg_bytes: bytes) -> float:
        x = _preprocess(jpeg_bytes)
        logits = self.session.run(None, {self.input_name: x})[0]
        return float(_sigmoid(logits).reshape(-1)[0])
```

**Step 5: Run — verify pass**

```bash
pytest tests/test_inference.py -v
```

Expected: 3 passed.

**Step 6: Commit**

```bash
git add scripts/lib/inference.py tests/test_inference.py scripts/requirements.txt
git commit -m "Add ONNX inference wrapper for graffiti classifier"
```

---

# Part E — Sheet writeback & job orchestration (TDD)

## Task E1: Test the column-finder helper

**Files:**
- Create: `tests/test_sheet.py`

**Step 1: Write the failing test**

```python
# tests/test_sheet.py
from scripts.lib.sheet import ensure_columns, GRAFFITI_COLUMNS

def test_ensure_columns_adds_missing_headers():
    header_row = ["Address", "Neighborhood", "Name/Type", "Features & 2026 Status",
                  "Previous Statuses", "Updated on", "Case Number", "Notice Date",
                  "Deadline", "Latitude", "Longitude"]
    new_header = ensure_columns(header_row)
    assert new_header[:11] == header_row
    assert new_header[11:14] == GRAFFITI_COLUMNS

def test_ensure_columns_idempotent():
    header_row = ["Address", "Latitude", "Longitude"] + list(GRAFFITI_COLUMNS)
    assert ensure_columns(header_row) == header_row
```

**Step 2: Run — verify failure**

```bash
pytest tests/test_sheet.py -v
```

Expected: ImportError on `scripts.lib.sheet`.

**Step 3: Implement `scripts/lib/sheet.py`**

```python
"""Helpers for the blight Google Sheet."""
from __future__ import annotations

GRAFFITI_COLUMNS = ("graffiti_score", "graffiti_panoid", "graffiti_classified_at")

def ensure_columns(header_row: list[str]) -> list[str]:
    """Return header_row with GRAFFITI_COLUMNS appended if not already present."""
    out = list(header_row)
    for col in GRAFFITI_COLUMNS:
        if col not in out:
            out.append(col)
    return out
```

**Step 4: Run — verify pass**

```bash
pytest tests/test_sheet.py -v
```

Expected: 2 passed.

**Step 5: Commit**

```bash
git add scripts/lib/sheet.py tests/test_sheet.py
git commit -m "Add sheet column helper for graffiti columns"
```

---

## Task E2: Test row-needs-classification logic

**Files:**
- Modify: `tests/test_sheet.py`
- Modify: `scripts/lib/sheet.py`

**Step 1: Append tests**

```python
import datetime as _dt
from scripts.lib.sheet import row_needs_classification

NOW = _dt.datetime(2026, 5, 3, tzinfo=_dt.timezone.utc)

def _row(score="", classified_at=""):
    return {"Address": "1 X St", "Latitude": "29.96", "Longitude": "-90.01",
            "graffiti_score": score, "graffiti_panoid": "", "graffiti_classified_at": classified_at}

def test_row_needs_classification_when_no_score():
    assert row_needs_classification(_row(), now=NOW, max_age_days=30) is True

def test_row_skipped_when_recent_score():
    recent = (NOW - _dt.timedelta(days=5)).isoformat()
    assert row_needs_classification(_row(score="0.42", classified_at=recent), now=NOW, max_age_days=30) is False

def test_row_needs_reclassification_when_stale():
    stale = (NOW - _dt.timedelta(days=60)).isoformat()
    assert row_needs_classification(_row(score="0.42", classified_at=stale), now=NOW, max_age_days=30) is True
```

**Step 2: Run — verify failure**

Expected: ImportError on `row_needs_classification`.

**Step 3: Implement**

```python
# Append to scripts/lib/sheet.py
import datetime

def row_needs_classification(row: dict, *, now: datetime.datetime, max_age_days: int) -> bool:
    if not row.get("graffiti_score"):
        return True
    ts = row.get("graffiti_classified_at", "")
    if not ts:
        return True
    try:
        when = datetime.datetime.fromisoformat(ts)
    except ValueError:
        return True
    if when.tzinfo is None:
        when = when.replace(tzinfo=datetime.timezone.utc)
    return (now - when).days >= max_age_days
```

**Step 4: Run — verify pass**

Expected: 5 passed total in `test_sheet.py`.

**Step 5: Commit**

```bash
git add scripts/lib/sheet.py tests/test_sheet.py
git commit -m "Add staleness check for graffiti scores"
```

---

## Task E3: Wire up `classify_graffiti.py`

**Files:**
- Create: `scripts/classify_graffiti.py`

**Step 1: Implement the orchestrator**

```python
"""Iterate addresses in the blight sheet, score each via Street View + ONNX, write results back.

Designed to be run from GitHub Actions after `update_database.py` succeeds.
Idempotent: skips rows with a recent graffiti_score.

Required env: GOOGLE_CREDENTIALS (same as update_database.py), MODEL_PATH (default models/graffiti_classifier.onnx).
"""
from __future__ import annotations
import json
import os
import sys
import datetime
import pathlib
import gspread
from google.oauth2.service_account import Credentials

from scripts.lib.streetview import ScraperSession, PanoramaNotFound
from scripts.lib.inference import GraffitiClassifier
from scripts.lib.sheet import GRAFFITI_COLUMNS, ensure_columns, row_needs_classification

SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
DEFAULT_MODEL = pathlib.Path('models/graffiti_classifier.onnx')
MAX_AGE_DAYS = int(os.environ.get("GRAFFITI_MAX_AGE_DAYS", "30"))
MAX_PER_RUN = int(os.environ.get("GRAFFITI_MAX_PER_RUN", "200"))

def _open_sheet():
    creds = json.loads(os.environ["GOOGLE_CREDENTIALS"])
    scopes = ['https://www.googleapis.com/auth/spreadsheets']
    return gspread.authorize(Credentials.from_service_account_info(creds, scopes=scopes)).open_by_key(SPREADSHEET_ID).sheet1

def main() -> int:
    model_path = pathlib.Path(os.environ.get("MODEL_PATH", DEFAULT_MODEL))
    if not model_path.exists():
        print(f"Model not found at {model_path} — skipping classification.", file=sys.stderr)
        return 0  # Soft no-op so workflows don't fail before the user trains

    sheet = _open_sheet()
    rows = sheet.get_all_values()
    if not rows:
        print("Empty sheet."); return 0

    header = ensure_columns(rows[0])
    if header != rows[0]:
        sheet.update([header], "A1")
    col_idx = {name: i for i, name in enumerate(header)}

    clf = GraffitiClassifier(str(model_path))
    sess = ScraperSession(min_interval_s=float(os.environ.get("GRAFFITI_MIN_INTERVAL_S", "3.0")))
    now = datetime.datetime.now(datetime.timezone.utc)

    processed = 0
    for r_i, row in enumerate(rows[1:], start=2):  # 1-based row index, skipping header
        row += [""] * (len(header) - len(row))
        row_dict = dict(zip(header, row))
        if not row_needs_classification(row_dict, now=now, max_age_days=MAX_AGE_DAYS):
            continue
        if processed >= MAX_PER_RUN:
            print(f"Hit MAX_PER_RUN={MAX_PER_RUN}; stopping."); break
        try:
            lat = float(row_dict["Latitude"]); lng = float(row_dict["Longitude"])
        except (KeyError, ValueError):
            continue

        try:
            panoid = sess.lookup_panoid(lat, lng)
            tile = sess.fetch_tile(panoid, zoom=0)
            score = clf.score(tile)
        except PanoramaNotFound:
            score, panoid = 0.0, "NO_PANO"
        except Exception as e:
            print(f"row {r_i}: {e}", file=sys.stderr); continue

        ts = now.isoformat(timespec='seconds')
        updates = {
            col_idx["graffiti_score"]: f"{score:.4f}",
            col_idx["graffiti_panoid"]: panoid,
            col_idx["graffiti_classified_at"]: ts,
        }
        # Single batched cell update per row to minimize API calls
        cells = [gspread.Cell(r_i, c + 1, v) for c, v in updates.items()]
        sheet.update_cells(cells)
        processed += 1
        print(f"row {r_i} {row_dict['Address']!r:40s}  score={score:.3f} panoid={panoid}")

    print(f"Done. processed={processed}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
```

**Step 2: Smoke-test CLI**

```bash
python -c "import scripts.classify_graffiti"  # import succeeds
```

Expected: no output, no error.

**Step 3: Smoke-test no-model graceful-skip**

```bash
GOOGLE_CREDENTIALS='{}' MODEL_PATH=/tmp/nope.onnx python scripts/classify_graffiti.py
```

Expected: prints `Model not found at /tmp/nope.onnx — skipping classification.` exit 0.

**Step 4: Commit**

```bash
git add scripts/classify_graffiti.py
git commit -m "Add classify_graffiti orchestrator"
```

---

# Part F — GitHub Actions integration

## Task F1: Add classification step to existing workflow

**Files:**
- Modify: `.github/workflows/*.yml` (find and read first)

**Step 1: List existing workflows**

```bash
ls .github/workflows/
```

Read each. The plan assumes there's a workflow that runs `scripts/update_database.py`. Identify it.

**Step 2: Add a new step after update_database.py runs**

```yaml
      - name: Classify graffiti (best-effort)
        if: success()
        env:
          GOOGLE_CREDENTIALS: ${{ secrets.GOOGLE_CREDENTIALS }}
          MODEL_PATH: models/graffiti_classifier.onnx
          GRAFFITI_MAX_PER_RUN: '50'
          GRAFFITI_MIN_INTERVAL_S: '4.0'
        run: |
          python scripts/classify_graffiti.py || echo "graffiti classification failed; continuing"
```

The trailing `|| echo` ensures a scraper hiccup never breaks the database update.

**Step 3: Local smoke-test of YAML**

```bash
python -c "import yaml; yaml.safe_load(open('.github/workflows/<file>.yml'))"
```

Expected: no error.

**Step 4: Commit**

```bash
git add .github/workflows/<file>.yml
git commit -m "Run graffiti classifier after database update"
```

---

# Part G — UI changes

## Task G1: Read & understand index.html

**Step 1: Read the file**

```bash
wc -l index.html
```

Open and locate (a) where Sheet rows are parsed, (b) where popups are built, (c) where markers are styled. The plan assumes it uses Leaflet with circle markers.

**Step 2:** No commit; reading-only.

---

## Task G2: Surface graffiti_score in popups

**Files:**
- Modify: `index.html`

**Step 1: Add field to popup template**

Find the popup template (currently `<h3>${property.address}</h3>...`). Add:

```html
<p><b>Graffiti likelihood:</b> ${property.graffiti_score != null ? (property.graffiti_score * 100).toFixed(0) + '%' : '—'}</p>
```

**Step 2: Parse the new column** in the row-mapping function. Add handling so missing columns don't crash older code.

**Step 3: Manual test**

Open `index.html` in a browser (Live Server or `python -m http.server 8000`). Verify:
- Map loads
- Existing popups still work
- New row reads `graffiti_score` if present

**Step 4: Commit**

```bash
git add index.html
git commit -m "Show graffiti likelihood in property popups"
```

---

## Task G3: Filter toggle

**Files:**
- Modify: `index.html`

**Step 1: Add a control button to the toolbar area**

A 3-state cycle: `all` → `graffiti only (>=0.5)` → `no graffiti (<0.5)` → `all`.

```html
<button id="graffiti-filter" aria-label="Filter by graffiti">Graffiti: All</button>
```

**Step 2: Add filter logic**

```javascript
let graffitiFilter = 'all'; // 'all' | 'graffiti' | 'clean'
document.getElementById('graffiti-filter').addEventListener('click', () => {
  graffitiFilter = ({all: 'graffiti', graffiti: 'clean', clean: 'all'})[graffitiFilter];
  document.getElementById('graffiti-filter').textContent =
    'Graffiti: ' + ({all: 'All', graffiti: 'Tagged', clean: 'Clean'})[graffitiFilter];
  refreshMarkers();
});

function shouldShowByGraffiti(p) {
  if (graffitiFilter === 'all') return true;
  if (p.graffiti_score == null) return graffitiFilter === 'all';
  return graffitiFilter === 'graffiti' ? p.graffiti_score >= 0.5 : p.graffiti_score < 0.5;
}
```

Wire `shouldShowByGraffiti` into the existing marker-rendering function.

**Step 3: Manual test**

Reload the page. Click the button — markers visibly filter.

**Step 4: Commit**

```bash
git add index.html
git commit -m "Add graffiti filter toggle"
```

---

## Task G4: Rank-by-score color

**Files:**
- Modify: `index.html`

**Step 1: Add a "color by score" toggle**

```html
<button id="graffiti-rank">Color: Status</button>
```

**Step 2: Update marker fill**

```javascript
let colorMode = 'status'; // 'status' | 'graffiti'
document.getElementById('graffiti-rank').addEventListener('click', () => {
  colorMode = colorMode === 'status' ? 'graffiti' : 'status';
  document.getElementById('graffiti-rank').textContent = 'Color: ' + (colorMode === 'status' ? 'Status' : 'Graffiti');
  refreshMarkers();
});

function markerColor(p) {
  if (colorMode === 'graffiti') {
    if (p.graffiti_score == null) return '#777';
    // 0 → blue, 1 → red
    const r = Math.round(255 * p.graffiti_score);
    const b = Math.round(255 * (1 - p.graffiti_score));
    return `rgb(${r},80,${b})`;
  }
  return p.status === 'Guilty' ? '#e53935' : '#fb8c00';
}
```

**Step 3: Manual test in browser**

Toggle and verify a visible color shift on properties that have scores.

**Step 4: Commit**

```bash
git add index.html
git commit -m "Add graffiti score color mode"
```

---

# Part H — Documentation

## Task H1: Pipeline operator documentation

**Files:**
- Create: `docs/graffiti-pipeline.md`

**Step 1: Write the doc**

```markdown
# Graffiti Pipeline — Operator Guide

End-to-end overview of how addresses become graffiti scores, plus how to retrain, run inference standalone, and reuse the model elsewhere.

## Architecture

[diagram: dataset → notebook → ONNX → repo → Actions → sheet → UI]

## Files

| Path | Purpose |
|---|---|
| `notebooks/train_graffiti_classifier.ipynb` | Colab fine-tune notebook |
| `models/graffiti_classifier.onnx` | INT8-quantized model used at inference |
| `scripts/lib/streetview.py` | Throttled HTTP scraper of Street View panoramas + tiles |
| `scripts/lib/inference.py` | ONNX runtime wrapper |
| `scripts/lib/sheet.py` | Sheet column helpers |
| `scripts/streetview_scrape.py` | Standalone scraper CLI |
| `scripts/classify_graffiti.py` | Per-address classification job |
| `MODEL_CARD.md` | Model card |

## Retraining

1. Open `notebooks/train_graffiti_classifier.ipynb` in Google Colab with a GPU runtime.
2. Provide `kaggle.json` (Colab secret `KAGGLE_JSON` recommended; upload also works).
3. Mount Drive (cell prompts).
4. Runtime → Run all. ~10 min on T4.
5. Download `quantized.onnx` and `model_card.md` from `MyDrive/blight-graffiti/<run_id>/final/`.
6. In a local checkout:

   ```bash
   cp <downloaded>/quantized.onnx models/graffiti_classifier.onnx
   cp <downloaded>/model_card.md MODEL_CARD.md
   git add -f models/graffiti_classifier.onnx
   git add MODEL_CARD.md
   git commit -m "Update graffiti classifier"
   git push origin main
   ```

## Running inference standalone (no Sheet)

```bash
python scripts/streetview_scrape.py --lat 29.964 --lng -90.007 --out cache/sample.jpg
python -c "
from scripts.lib.inference import GraffitiClassifier
print(GraffitiClassifier('models/graffiti_classifier.onnx').score(open('cache/sample.jpg','rb').read()))
"
```

## Reusing the model in another project

**Python (any platform with ONNX Runtime):**

```python
from scripts.lib.inference import GraffitiClassifier
clf = GraffitiClassifier('models/graffiti_classifier.onnx')
score = clf.score(open('photo.jpg','rb').read())  # 0.0–1.0
```

**Browser / JavaScript** — use [`onnxruntime-web`](https://github.com/microsoft/onnxruntime/tree/main/js/web). Apply the same preprocessing: resize 256, center-crop 224, normalize with ImageNet mean/std, NCHW.

**REST microservice** — wrap `GraffitiClassifier` in FastAPI; runs on a CPU box or Lambda.

## Scraper failure modes

| Symptom | Cause | Fix |
|---|---|---|
| Persistent HTTP 429 | Source IP rate-limited | Increase `GRAFFITI_MIN_INTERVAL_S`; rotate runner |
| `PanoramaNotFound` for many addresses | No Street View coverage at lat/lng | Acceptable; row gets `graffiti_panoid="NO_PANO"` and `score=0.0` |
| Body parse error in `lookup_panoid` | Google changed response shape | Capture a fresh response, update `_strip_xss_prefix` / index path, update fixture |
| 200 OK but body is HTML CAPTCHA | IP flagged | Stop the runner; switch network. The pipeline cannot defeat reCAPTCHA |

## Domain-shift sanity check

The model is trained on Kaggle photos, not Street View. Before relying on it for ranking decisions, run the optional last cell of the notebook on a hand-labeled set of ~50 Street View crops from real Lower Ninth Ward addresses. If AUC < 0.7, treat scores as a weak signal only.

## Privacy

The pipeline runs server-side; no user telemetry. Imagery cache is not persisted between runs unless explicitly enabled.
```

**Step 2: Commit**

```bash
git add docs/graffiti-pipeline.md
git commit -m "Add graffiti pipeline operator documentation"
```

---

## Task H2: README pointer

**Files:**
- Modify: `README.md`

**Step 1: Append a new section**

```markdown
## 5. Graffiti Classifier (Optional Enrichment)

Each address can be enriched with a graffiti-presence score derived from Street View imagery. See [`docs/graffiti-pipeline.md`](docs/graffiti-pipeline.md) for retraining, deployment, and operational details. Model card: [`MODEL_CARD.md`](MODEL_CARD.md).
```

**Step 2: Commit**

```bash
git add README.md
git commit -m "Link graffiti pipeline docs from README"
```

---

## Task H3: Placeholder MODEL_CARD.md

**Files:**
- Create: `MODEL_CARD.md`

**Step 1: Write a placeholder that the notebook will overwrite**

```markdown
# Graffiti Classifier — Model Card

> **Placeholder.** This file is regenerated by `notebooks/train_graffiti_classifier.ipynb` (Cell 11) at the end of training. After training, copy `MyDrive/blight-graffiti/<run_id>/final/model_card.md` over this file and commit.

See [`docs/graffiti-pipeline.md`](docs/graffiti-pipeline.md) for pipeline details.
```

**Step 2: Commit**

```bash
git add MODEL_CARD.md
git commit -m "Add placeholder model card"
```

---

# Part I — Final integration check

## Task I1: Run the full test suite

**Step 1:**

```bash
pytest -v
```

Expected: all tests pass (8–10 tests across `test_streetview.py`, `test_inference.py`, `test_sheet.py`).

**Step 2: Linter / import check**

```bash
python -c "import scripts.classify_graffiti, scripts.streetview_scrape, scripts.lib.streetview, scripts.lib.inference, scripts.lib.sheet"
```

Expected: no output.

**Step 3: Commit-free verification only.**

---

## Task I2: Open PR

**Step 1: Push branch and open PR**

```bash
git push -u origin claude/vibrant-hawking-dd3434
gh pr create --title "Graffiti classifier pipeline" --body "$(cat <<'EOF'
## Summary
- Adds a Colab notebook to fine-tune EfficientNet-B0 on Kaggle graffiti data
- Adds Street View scraper (no API, no headless browser) + ONNX inference module
- Wires per-address classification into the existing GitHub Actions cron
- Surfaces graffiti score as a filter and color mode on the map

## Test plan
- [ ] `pytest -v` passes
- [ ] Notebook runs end-to-end on Colab (one human run before PR merge)
- [ ] After committing trained ONNX, Actions classification step writes scores to sheet
- [ ] UI shows score in popup, filter toggle works, color toggle works

See `docs/plans/2026-05-03-graffiti-classifier-design.md` for the design.
EOF
)"
```

---

# Notes for the implementer

- The trained `models/graffiti_classifier.onnx` is added with `git add -f` because `models/*.onnx` is gitignored. This is intentional — keeps stray training artifacts out of the repo.
- The Actions workflow's classification step is a **soft no-op** when the model is missing; it is safe to merge this PR before the first model is trained.
- The scraper hits unofficial Google endpoints. Treat any test that exercises live URLs as a `network` marker test, opt-in only. CI runs only the offline suite.
- The `MAX_PER_RUN` env defaults to 200 in code but the workflow caps at 50 — adjust upward only after observing the runner doesn't get 429s.
