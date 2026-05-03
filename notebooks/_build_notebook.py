"""Builds notebooks/train_graffiti_classifier.ipynb programmatically.

Run from the repo root:

    python notebooks/_build_notebook.py

Re-run any time a cell needs to change so the .ipynb stays in sync.
"""
from __future__ import annotations

import pathlib

import nbformat


CELL_1_MD = """# Train Graffiti Classifier

Fine-tunes EfficientNet-B0 on the Kaggle `pinstripezebra/graffiti-classification` dataset and exports a CPU-friendly quantized ONNX model for use in the blight pipeline's GitHub Actions runner.

**Outputs (saved to `MyDrive/blight-graffiti/<run_id>/`):**
- `final/model.onnx` — FP32 ONNX
- `final/quantized.onnx` — INT8-quantized ONNX (commit this to the repo at `models/graffiti_classifier.onnx`)
- `final/metrics.json` — AUC, accuracy, precision, recall, confusion matrix
- `final/model_card.md` — generated model card
- `checkpoints/epoch_*.pt` — per-epoch PyTorch checkpoints

**Runtime:** ~10 minutes on a Colab T4."""


CELL_2 = """!pip -q install kaggle onnx onnxruntime
from google.colab import drive
drive.mount('/content/drive')

import os, json, time, shutil, pathlib, datetime
RUN_ID = datetime.datetime.utcnow().strftime("%Y%m%d-%H%M%S")
DRIVE_BASE = pathlib.Path('/content/drive/MyDrive/blight-graffiti') / RUN_ID
(DRIVE_BASE / 'checkpoints').mkdir(parents=True, exist_ok=True)
(DRIVE_BASE / 'final').mkdir(parents=True, exist_ok=True)
print(f"Run output: {DRIVE_BASE}")"""


CELL_3 = """# Either upload kaggle.json via the Files panel OR set Colab secrets.
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
print("Kaggle credentials configured.")"""


CELL_4 = """DATA_DIR = pathlib.Path('/content/data/graffiti')
DATA_DIR.mkdir(parents=True, exist_ok=True)
!kaggle datasets download -d pinstripezebra/graffiti-classification -p {DATA_DIR} --unzip
!ls {DATA_DIR}"""


CELL_5 = """import collections, random
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
plt.tight_layout(); plt.show()"""


CELL_6 = """import torch
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
test_loader  = DataLoader(test_ds,  batch_size=64, num_workers=2, pin_memory=True)"""


CELL_7 = """from torchvision.models import efficientnet_b0, EfficientNet_B0_Weights
import torch.nn as nn

device = 'cuda' if torch.cuda.is_available() else 'cpu'
model = efficientnet_b0(weights=EfficientNet_B0_Weights.IMAGENET1K_V1)
model.classifier = nn.Sequential(nn.Dropout(p=0.2), nn.Linear(1280, 1))
model = model.to(device)
print(f"Device: {device}")"""


CELL_8 = """import torch.optim as optim

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
    scheduler.step()"""


CELL_9 = """model.load_state_dict(torch.load(DRIVE_BASE / 'checkpoints' / 'best.pt'))
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
plt.savefig(DRIVE_BASE / 'final' / 'confusion_matrix.png', bbox_inches='tight'); plt.show()"""


CELL_10 = """import torch.onnx
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
print(f"FP32: {fp32_path.stat().st_size/1e6:.1f} MB; INT8: {q_path.stat().st_size/1e6:.1f} MB")"""


# NOTE: Cell 11 is itself a runtime f-string. We emit it as a literal string so
# the single-brace interpolations (e.g. {len(train_items)}) reach the .ipynb
# unchanged. Using a regular triple-quoted string here (not an f-string) avoids
# any escaping in this builder.
CELL_11 = '''card = f"""# Graffiti Classifier — Model Card

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
print(card)'''


CELL_12 = '''print(f"""
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
""")'''


def build() -> pathlib.Path:
    nb = nbformat.v4.new_notebook()
    nb.cells = [
        nbformat.v4.new_markdown_cell(CELL_1_MD),
        nbformat.v4.new_code_cell(CELL_2),
        nbformat.v4.new_code_cell(CELL_3),
        nbformat.v4.new_code_cell(CELL_4),
        nbformat.v4.new_code_cell(CELL_5),
        nbformat.v4.new_code_cell(CELL_6),
        nbformat.v4.new_code_cell(CELL_7),
        nbformat.v4.new_code_cell(CELL_8),
        nbformat.v4.new_code_cell(CELL_9),
        nbformat.v4.new_code_cell(CELL_10),
        nbformat.v4.new_code_cell(CELL_11),
        nbformat.v4.new_code_cell(CELL_12),
    ]

    out_path = pathlib.Path(__file__).parent / 'train_graffiti_classifier.ipynb'
    nbformat.write(nb, str(out_path))
    return out_path


if __name__ == '__main__':
    path = build()
    print(f"Wrote {path}")
