"""Iterate addresses in the blight sheet, score each via Street View + ONNX, write results back.

Designed to be run from GitHub Actions after `update_database.py` succeeds.
Idempotent: skips rows with a recent graffiti_score.

Required env: GOOGLE_CREDENTIALS (same as update_database.py), MODEL_PATH (default models/graffiti_classifier.onnx).

Invoke as: `python -m scripts.classify_graffiti` from the repo root.
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
        print(f"Model not found at {model_path} - skipping classification.", file=sys.stderr)
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
