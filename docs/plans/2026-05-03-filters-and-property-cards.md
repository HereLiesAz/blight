# Blight Filters & Property Cards Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a multi-dim filter drawer with named presets, a per-property enrichment pipeline pulling zoning/footprint/case-history/grass-cutting from NOLA cross-datasets, a Drive-hosted Street View thumbnail per property, and tappable accordion property cards in both list views.

**Architecture:** Three sequential idempotent jobs in the existing GH Actions cron — `update_database.py` (expanded Socrata fields) → `enrich_properties.py` (cross-dataset joins, new) → `classify_graffiti.py` (extended to upload a Drive thumbnail). The static `index.html` reads ~25 sheet columns and renders a slide-out filter drawer + accordion cards in the existing list modals.

**Tech Stack:** Python 3.12 (CI) / 3.13 (dev), `gspread`, `google-api-python-client` (new), `requests`, Pillow, `shapely` (point-in-polygon for land use, new), Vanilla JS + Leaflet.

**Reference design:** [docs/plans/2026-05-03-filters-and-property-cards-design.md](2026-05-03-filters-and-property-cards-design.md)

---

# Part A — Backend enrichment

## Task A1: Expand `update_database.py` to pull more Socrata fields

**Files:** Modify `scripts/update_database.py`.

**Steps:**

1. Update the SoQL `$select`. Replace:
   ```python
   QUERY = f"?$select=geoaddress AS address, prevhearingresult AS status, casefiled AS notice_date, the_geom AS location, caseno AS casenumber&$where={BOUNDS} AND prevhearingresult IN('Guilty', 'Uncommitted')&$limit=50000"
   ```
   with:
   ```python
   SELECT_FIELDS = (
       "geoaddress AS address, prevhearingresult AS status, casefiled AS notice_date, "
       "the_geom AS location, caseno AS casenumber, geopin, initinspection, "
       "permittype AS permit_type, permitstatus AS permit_status, permitfiling AS permit_filing, "
       "nexthearingdate AS next_hearing, stage, o_c, zipcode"
   )
   QUERY = f"?$select={SELECT_FIELDS}&$where={BOUNDS} AND prevhearingresult IN('Guilty', 'Uncommitted')&$limit=50000"
   ```

2. Extend the header row written to the sheet. Add columns L–T after `Longitude`:
   ```python
   processed_data.append([
       "Address", "Neighborhood", "Name/Type", "Features & 2026 Status",
       "Previous Statuses", "Updated on", "Case Number", "Notice Date",
       "Deadline", "Latitude", "Longitude",
       "geopin", "init_inspection", "permit_type", "permit_status", "permit_filing",
       "next_hearing", "stage", "o_c", "zipcode",
   ])
   ```

3. Extend each row write with the new fields. Just before `seen_cases.add(caseno)`:
   ```python
   processed_data.append([
       address, "Lower Ninth Ward", "", status, "", current_date,
       caseno, notice_date, deadline, lat, lng,
       row.get("geopin", ""),
       _parse_socrata_date_str(row.get("initinspection")),
       row.get("permit_type", ""), row.get("permit_status", ""),
       _parse_socrata_date_str(row.get("permit_filing")),
       _parse_socrata_date_str(row.get("next_hearing")),
       row.get("stage", ""), row.get("o_c", ""), row.get("zipcode", ""),
   ])
   ```

4. Add helper `_parse_socrata_date_str` near `parse_socrata_date`:
   ```python
   def _parse_socrata_date_str(date_str):
       d = parse_socrata_date(date_str)
       return d.strftime("%m/%d/%Y") if d else ""
   ```

5. Update the `batch_clear` range from `"A:K"` to `"A:T"`.

6. Smoke-test:
   ```bash
   GOOGLE_CREDENTIALS='{}' python scripts/update_database.py 2>&1 | head -10
   ```
   Should not crash on import / parsing — credentials error is expected later.

7. Commit:
   ```bash
   git add scripts/update_database.py
   git commit -m "Pull additional Socrata fields for enrichment"
   ```

---

## Task A2: Add new dependencies

**Files:** Modify `scripts/requirements.txt`.

**Steps:**

1. Append to the ML pipeline block:
   ```
   google-api-python-client==2.149.0
   shapely==2.0.6
   ```

2. Install:
   ```bash
   pip install google-api-python-client==2.149.0 shapely==2.0.6
   ```

3. Verify import:
   ```bash
   python -c "from googleapiclient.discovery import build; from shapely.geometry import Point, shape; print('OK')"
   ```

4. Commit:
   ```bash
   git add scripts/requirements.txt
   git commit -m "Add google-api-python-client and shapely deps"
   ```

---

## Task A3: Drive uploader utility (TDD)

**Files:** Create `scripts/lib/drive_uploader.py`, `tests/test_drive_uploader.py`.

**Steps:**

1. Write failing tests:
   ```python
   # tests/test_drive_uploader.py
   import pytest
   from unittest.mock import MagicMock, patch
   from scripts.lib.drive_uploader import compress_thumbnail, public_url_for_id

   def test_compress_thumbnail_produces_jpeg_bytes_under_50kb():
       from PIL import Image
       from io import BytesIO
       img = Image.new('RGB', (1024, 1024), (200, 100, 50))
       buf = BytesIO(); img.save(buf, 'JPEG', quality=95); raw = buf.getvalue()
       out = compress_thumbnail(raw, max_dim=256, quality=70)
       assert out.startswith(b'\xff\xd8\xff'), 'JPEG magic'
       assert len(out) < 50_000, f'expected <50KB, got {len(out)}'

   def test_public_url_for_id_returns_uc_form():
       url = public_url_for_id("FILE123")
       assert url == "https://drive.google.com/uc?id=FILE123"
   ```

2. Run, expect failure:
   ```bash
   python -m pytest tests/test_drive_uploader.py -v
   ```

3. Implement `scripts/lib/drive_uploader.py`:
   ```python
   """Upload thumbnails to a public Google Drive folder."""
   from __future__ import annotations
   import io
   from PIL import Image

   PUBLIC_URL_TEMPLATE = "https://drive.google.com/uc?id={file_id}"

   def public_url_for_id(file_id: str) -> str:
       return PUBLIC_URL_TEMPLATE.format(file_id=file_id)

   def compress_thumbnail(jpeg_bytes: bytes, *, max_dim: int = 256, quality: int = 70) -> bytes:
       img = Image.open(io.BytesIO(jpeg_bytes)).convert('RGB')
       w, h = img.size
       if w > max_dim or h > max_dim:
           if w >= h:
               new_w, new_h = max_dim, round(h * max_dim / w)
           else:
               new_w, new_h = round(w * max_dim / h), max_dim
           img = img.resize((new_w, new_h), Image.LANCZOS)
       out = io.BytesIO(); img.save(out, 'JPEG', quality=quality, optimize=True)
       return out.getvalue()


   class DriveUploader:
       """Idempotent uploader: searches folder for existing <panoid>.jpg, otherwise creates."""

       def __init__(self, credentials, folder_id: str):
           from googleapiclient.discovery import build
           self._drive = build('drive', 'v3', credentials=credentials, cache_discovery=False)
           self.folder_id = folder_id

       def upload(self, panoid: str, jpeg_bytes: bytes) -> str:
           """Upload (or reuse) <panoid>.jpg in the folder; return public URL."""
           name = f"{panoid}.jpg"
           q = (f"'{self.folder_id}' in parents and name = '{name}' "
                f"and trashed = false")
           found = self._drive.files().list(q=q, fields="files(id)").execute().get('files', [])
           if found:
               return public_url_for_id(found[0]['id'])

           from googleapiclient.http import MediaIoBaseUpload
           media = MediaIoBaseUpload(io.BytesIO(jpeg_bytes), mimetype='image/jpeg')
           created = self._drive.files().create(
               body={'name': name, 'parents': [self.folder_id]},
               media_body=media,
               fields='id',
           ).execute()
           file_id = created['id']
           # Make public
           self._drive.permissions().create(
               fileId=file_id,
               body={'type': 'anyone', 'role': 'reader'},
           ).execute()
           return public_url_for_id(file_id)
   ```

4. Run, expect green:
   ```bash
   python -m pytest tests/test_drive_uploader.py -v
   ```
   Expected: 2 passed.

5. Commit:
   ```bash
   git add scripts/lib/drive_uploader.py tests/test_drive_uploader.py
   git commit -m "Add Drive thumbnail uploader utility"
   ```

---

## Task A4: Wire thumbnail upload into `classify_graffiti.py`

**Files:** Modify `scripts/classify_graffiti.py`.

**Steps:**

1. Add imports at the top:
   ```python
   from scripts.lib.drive_uploader import DriveUploader, compress_thumbnail
   ```

2. Add the new sheet column to `GRAFFITI_COLUMNS` in `scripts/lib/sheet.py`:
   ```python
   GRAFFITI_COLUMNS = ("graffiti_score", "graffiti_panoid", "graffiti_classified_at", "streetview_thumb_url")
   ```
   (Update [tests/test_sheet.py](../../tests/test_sheet.py) `test_ensure_columns_adds_missing_headers` slice from `[11:14]` to `[11:15]`. Also adjust the `_row` helper to include `graffiti_thumb_url=""`.)

3. After the credentials are loaded in `_open_sheet()` (or in `main()` directly), instantiate the uploader if a folder ID is configured:
   ```python
   DRIVE_FOLDER_ID = os.environ.get("STREETVIEW_DRIVE_FOLDER_ID", "").strip()
   ```

   In `main()`, after `clf = GraffitiClassifier(...)`:
   ```python
   uploader = None
   if DRIVE_FOLDER_ID:
       from google.oauth2.service_account import Credentials
       creds = Credentials.from_service_account_info(
           json.loads(os.environ["GOOGLE_CREDENTIALS"]),
           scopes=['https://www.googleapis.com/auth/spreadsheets',
                   'https://www.googleapis.com/auth/drive.file'],
       )
       uploader = DriveUploader(creds, DRIVE_FOLDER_ID)
   else:
       print("STREETVIEW_DRIVE_FOLDER_ID not set; skipping thumbnail upload.")
   ```

4. After computing `score`, conditionally upload:
   ```python
   thumb_url = ""
   if uploader and panoid not in (None, "", "NO_PANO"):
       try:
           thumb_url = uploader.upload(panoid, compress_thumbnail(tile))
       except Exception as e:
           print(f"row {r_i}: thumbnail upload failed: {e}", file=sys.stderr)
   ```

5. Add to the `updates` dict:
   ```python
   if "streetview_thumb_url" in col_idx:
       updates[col_idx["streetview_thumb_url"]] = thumb_url
   ```

6. Run tests:
   ```bash
   python -m pytest -v
   ```
   Should still be all green (no Drive API hit in unit tests).

7. Commit:
   ```bash
   git add scripts/classify_graffiti.py scripts/lib/sheet.py tests/test_sheet.py
   git commit -m "Upload Street View thumbnail to Drive after classification"
   ```

---

## Task A5: Enrichment helpers in `scripts/lib/enrichment.py` (TDD)

**Files:** Create `scripts/lib/enrichment.py`, `tests/test_enrichment.py`, `tests/fixtures/futland_use_*.json` (small fixtures).

**Steps:**

1. Write fixture files:
   - `tests/fixtures/zoning_lookup.json`:
     ```json
     [{"geopin":"41126231","zoningclassification":"HMR-3","zoningdescription":"Historic Marigny/Tremé/Bywater Residential District"}]
     ```
   - `tests/fixtures/footprint_present.json`:
     ```json
     [{"geopin":"41126231","activestatus":"1","structureid":"1234"}]
     ```
   - `tests/fixtures/footprint_empty.json`: `[]`
   - `tests/fixtures/case_history.json`:
     ```json
     [{"initinspection":"20170411000000.000"},{"initinspection":"20240301000000.000"}]
     ```
   - `tests/fixtures/grass_cutting.json`:
     ```json
     [{"casefiled":"2021-01-26T15:25:13.000","caseno":"21-00471-CH66","countofmaintenancecuts":"3"}]
     ```
   - `tests/fixtures/land_use.json`:
     ```json
     [{"flu_desc":"Residential Single-Family","futlanduse":"RSF"}]
     ```

2. Write failing tests:
   ```python
   # tests/test_enrichment.py
   import datetime as dt
   import json, pathlib
   import pytest
   import responses
   from scripts.lib.enrichment import (
       fetch_zoning, fetch_footprint, fetch_case_history,
       fetch_last_grass_cut, fetch_land_use, days_under_blight, NOW,
   )

   FX = pathlib.Path('tests/fixtures')
   def _load(name): return json.loads((FX / name).read_text())

   @responses.activate
   def test_fetch_zoning_returns_class_and_desc():
       responses.add(responses.GET, "https://data.nola.gov/resource/cym7-cw5z.json",
                     json=_load('zoning_lookup.json'), status=200)
       z = fetch_zoning("41126231")
       assert z['zoning_class'] == "HMR-3"
       assert "Historic" in z['zoning_desc']

   @responses.activate
   def test_fetch_footprint_present_and_empty():
       responses.add(responses.GET, "https://data.nola.gov/resource/prh5-qsuf.json",
                     json=_load('footprint_present.json'), status=200)
       responses.add(responses.GET, "https://data.nola.gov/resource/prh5-qsuf.json",
                     json=_load('footprint_empty.json'), status=200)
       assert fetch_footprint("41126231") is True
       assert fetch_footprint("99999999") is False

   @responses.activate
   def test_fetch_case_history_returns_count_and_dates():
       responses.add(responses.GET, "https://data.nola.gov/resource/gjzc-adg8.json",
                     json=_load('case_history.json'), status=200)
       h = fetch_case_history("41126231")
       assert h['case_count'] == 2
       assert h['earliest_case_date'].startswith("2017-04-11")

   def test_days_under_blight_computes_against_now():
       earliest = dt.datetime(2020, 1, 1, tzinfo=dt.timezone.utc).isoformat()
       d = days_under_blight(earliest, now=dt.datetime(2026, 5, 3, tzinfo=dt.timezone.utc))
       assert 2300 < d < 2350

   @responses.activate
   def test_fetch_last_grass_cut_returns_iso_date_or_none():
       responses.add(responses.GET, "https://data.nola.gov/resource/xhih-vxs6.json",
                     json=_load('grass_cutting.json'), status=200)
       g = fetch_last_grass_cut("2061 N Tonti St")
       assert g.startswith("2021-01-26")
   ```

3. Run, expect ImportError:
   ```bash
   python -m pytest tests/test_enrichment.py -v
   ```

4. Implement `scripts/lib/enrichment.py`:
   ```python
   """Enrichment lookups against NOLA Socrata cross-datasets."""
   from __future__ import annotations
   import datetime as _dt
   import requests

   _BASE = "https://data.nola.gov/resource"
   NOW = _dt.datetime.now(_dt.timezone.utc)

   def _socrata_get(view_id: str, params: dict, timeout: float = 15.0) -> list:
       url = f"{_BASE}/{view_id}.json"
       r = requests.get(url, params=params, timeout=timeout)
       r.raise_for_status()
       return r.json()

   def fetch_zoning(geopin: str) -> dict:
       rows = _socrata_get("cym7-cw5z", {"$where": f"geopin='{geopin}'", "$limit": "1"})
       if not rows:
           return {"zoning_class": "", "zoning_desc": ""}
       return {
           "zoning_class": rows[0].get("zoningclassification", ""),
           "zoning_desc": rows[0].get("zoningdescription", ""),
       }

   def fetch_footprint(geopin: str) -> bool:
       rows = _socrata_get("prh5-qsuf",
                          {"$where": f"geopin='{geopin}' AND activestatus=1", "$limit": "1"})
       return bool(rows)

   def fetch_case_history(geopin: str) -> dict:
       rows = _socrata_get("gjzc-adg8",
                          {"$where": f"geopin='{geopin}'",
                           "$select": "initinspection",
                           "$order": "initinspection ASC", "$limit": "100"})
       if not rows:
           return {"case_count": 0, "earliest_case_date": ""}
       earliest = rows[0].get("initinspection", "")
       return {"case_count": len(rows), "earliest_case_date": _normalize_date(earliest)}

   def fetch_last_grass_cut(address: str) -> str:
       rows = _socrata_get("xhih-vxs6",
                          {"$where": f"address='{address}'",
                           "$select": "casefiled", "$order": "casefiled DESC", "$limit": "1"})
       if not rows:
           return ""
       return rows[0].get("casefiled", "")[:10]

   def fetch_land_use(lat: float, lng: float) -> str:
       rows = _socrata_get("itxd-2247",
                          {"$where": f"intersects(the_geom, 'POINT({lng} {lat})')",
                           "$select": "flu_desc", "$limit": "1"})
       if not rows:
           return ""
       return rows[0].get("flu_desc", "")

   def days_under_blight(earliest_iso: str, *, now: _dt.datetime = None) -> int:
       if not earliest_iso:
           return 0
       n = now or NOW
       try:
           d = _dt.datetime.fromisoformat(earliest_iso.replace("Z", "+00:00"))
           if d.tzinfo is None: d = d.replace(tzinfo=_dt.timezone.utc)
       except ValueError:
           return 0
       return max(0, (n - d).days)

   def _normalize_date(s: str) -> str:
       """Normalize various Socrata date formats to ISO 8601."""
       if not s: return ""
       if "T" in s: return s
       # 20170411000000.000 → 2017-04-11
       if len(s) >= 8 and s[:8].isdigit():
           return f"{s[:4]}-{s[4:6]}-{s[6:8]}T00:00:00"
       return s
   ```

5. Run, expect green (5 passed):
   ```bash
   python -m pytest tests/test_enrichment.py -v
   ```

6. Commit:
   ```bash
   git add scripts/lib/enrichment.py tests/test_enrichment.py tests/fixtures/zoning_lookup.json tests/fixtures/footprint_present.json tests/fixtures/footprint_empty.json tests/fixtures/case_history.json tests/fixtures/grass_cutting.json tests/fixtures/land_use.json
   git commit -m "Add NOLA cross-dataset enrichment lookups"
   ```

---

## Task A6: `scripts/enrich_properties.py` orchestrator

**Files:** Create `scripts/enrich_properties.py`. Modify `scripts/lib/sheet.py` to add helpers for the new columns.

**Steps:**

1. Add to `scripts/lib/sheet.py`:
   ```python
   ENRICHMENT_COLUMNS = (
       "zoning_class", "zoning_desc", "land_use_desc", "has_structure",
       "case_count", "earliest_case_date", "days_under_blight", "last_grass_cut",
   )

   def ensure_enrichment_columns(header_row: list[str]) -> list[str]:
       out = list(header_row)
       for col in ENRICHMENT_COLUMNS:
           if col not in out:
               out.append(col)
       return out

   def row_needs_enrichment(row: dict, *, max_age_days: int) -> bool:
       """True if any enrichment field is missing OR older than max_age_days."""
       if not row.get("zoning_class") and not row.get("zoning_desc"):
           return True
       # If we have a per-row timestamp, use it; otherwise ENRICHMENT_COLUMNS being non-empty is enough
       return False
   ```

2. Implement `scripts/enrich_properties.py`:
   ```python
   """Enrich each blight property with cross-dataset NOLA data.

   Runs after update_database.py, before classify_graffiti.py.
   Required env: GOOGLE_CREDENTIALS. Idempotent: skips rows already enriched.
   Invoke: python -m scripts.enrich_properties (from repo root)
   """
   from __future__ import annotations
   import json, os, sys, time, datetime
   import gspread
   from google.oauth2.service_account import Credentials

   from scripts.lib.enrichment import (
       fetch_zoning, fetch_footprint, fetch_case_history,
       fetch_last_grass_cut, fetch_land_use, days_under_blight,
   )
   from scripts.lib.sheet import ENRICHMENT_COLUMNS, ensure_enrichment_columns, row_needs_enrichment

   SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
   MIN_INTERVAL_S = float(os.environ.get("ENRICH_MIN_INTERVAL_S", "1.0"))
   MAX_PER_RUN    = int(os.environ.get("ENRICH_MAX_PER_RUN", "300"))

   def _open_sheet():
       creds = json.loads(os.environ["GOOGLE_CREDENTIALS"])
       scopes = ['https://www.googleapis.com/auth/spreadsheets']
       client = gspread.authorize(Credentials.from_service_account_info(creds, scopes=scopes))
       return client.open_by_key(SPREADSHEET_ID).sheet1

   def main() -> int:
       sheet = _open_sheet()
       rows = sheet.get_all_values()
       if not rows:
           print("Empty sheet."); return 0

       header = ensure_enrichment_columns(rows[0])
       if header != rows[0]:
           sheet.update([header], "A1")
       col_idx = {n: i for i, n in enumerate(header)}

       processed = 0
       for r_i, row in enumerate(rows[1:], start=2):
           row += [""] * (len(header) - len(row))
           rd = dict(zip(header, row))
           if not row_needs_enrichment(rd, max_age_days=90):
               continue
           if processed >= MAX_PER_RUN:
               print(f"Hit MAX_PER_RUN={MAX_PER_RUN}"); break

           geopin = (rd.get("geopin") or "").strip()
           addr   = (rd.get("Address") or "").strip()
           try:
               lat = float(rd.get("Latitude") or 0); lng = float(rd.get("Longitude") or 0)
           except ValueError:
               lat, lng = 0.0, 0.0

           updates = {}
           try:
               if geopin:
                   z = fetch_zoning(geopin)
                   updates[col_idx["zoning_class"]] = z["zoning_class"]
                   updates[col_idx["zoning_desc"]]  = z["zoning_desc"]
                   updates[col_idx["has_structure"]] = "yes" if fetch_footprint(geopin) else "no"
                   ch = fetch_case_history(geopin)
                   updates[col_idx["case_count"]] = str(ch["case_count"])
                   updates[col_idx["earliest_case_date"]] = ch["earliest_case_date"]
                   updates[col_idx["days_under_blight"]] = str(days_under_blight(ch["earliest_case_date"]))
               if addr:
                   updates[col_idx["last_grass_cut"]] = fetch_last_grass_cut(addr)
               if lat and lng:
                   updates[col_idx["land_use_desc"]] = fetch_land_use(lat, lng)
           except Exception as e:
               print(f"row {r_i} {addr!r}: {e}", file=sys.stderr); continue

           cells = [gspread.Cell(r_i, c+1, v) for c, v in updates.items()]
           if cells:
               sheet.update_cells(cells)
               processed += 1
               print(f"row {r_i} {addr!r:40s} enriched ({len(updates)} fields)")
           time.sleep(MIN_INTERVAL_S)

       print(f"Done. processed={processed}")
       return 0

   if __name__ == "__main__":
       raise SystemExit(main())
   ```

3. Smoke-test import:
   ```bash
   python -c "import scripts.enrich_properties; print('OK')"
   ```

4. Run all existing tests:
   ```bash
   python -m pytest -v
   ```
   Expected: all previous + 5 new from A5 = 18+ passing.

5. Commit:
   ```bash
   git add scripts/enrich_properties.py scripts/lib/sheet.py
   git commit -m "Add enrich_properties orchestrator"
   ```

---

## Task A7: GH Actions workflow integration

**Files:** Modify `.github/workflows/update_database.yml`.

**Steps:**

1. Add new step between `update_database.py` and `classify_graffiti`:
   ```yaml
         - name: Enrich properties (best-effort)
           if: success()
           env:
             GOOGLE_CREDENTIALS: ${{ secrets.SHEETS_API }}
             ENRICH_MAX_PER_RUN: '100'
             ENRICH_MIN_INTERVAL_S: '1.0'
           run: python -m scripts.enrich_properties || echo "enrichment failed; continuing"
   ```

2. Add `STREETVIEW_DRIVE_FOLDER_ID` to the classify step env:
   ```yaml
         - name: Classify graffiti (best-effort)
           if: success()
           env:
             GOOGLE_CREDENTIALS: ${{ secrets.SHEETS_API }}
             STREETVIEW_DRIVE_FOLDER_ID: ${{ secrets.STREETVIEW_DRIVE_FOLDER_ID }}
             MODEL_PATH: models/model.onnx
             GRAFFITI_MAX_PER_RUN: '50'
             GRAFFITI_MIN_INTERVAL_S: '4.0'
           run: python -m scripts.classify_graffiti || echo "graffiti classification failed; continuing"
   ```

3. Validate YAML:
   ```bash
   python -c "import yaml; yaml.safe_load(open('.github/workflows/update_database.yml'))"
   ```

4. Commit:
   ```bash
   git add .github/workflows/update_database.yml
   git commit -m "Wire enrichment job + Drive folder secret into workflow"
   ```

---

# Part B — Filter drawer UI

## Task B1: Drawer chrome + state vars

**Files:** Modify `index.html`.

**Steps:**

1. Add CSS for the drawer (in the `<style>` block):
   ```css
   .filter-drawer { position: absolute; top: 0; right: -340px; width: 320px; height: 100vh; background: rgba(11,11,11,0.95); border-left: 2px solid #444; z-index: 1100; transition: right 0.25s; padding: 16px; overflow-y: auto; box-sizing: border-box; color: #eee; font-family: monospace; font-size: 13px; }
   .filter-drawer.open { right: 0; }
   .filter-section { margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid #333; }
   .filter-section h4 { color: #FFBF00; margin: 0 0 6px 0; font-size: 12px; text-transform: uppercase; letter-spacing: 0.05em; }
   .chip-group { display: flex; gap: 4px; flex-wrap: wrap; }
   .chip { padding: 4px 8px; border: 1px solid #555; border-radius: 12px; background: #222; cursor: pointer; user-select: none; font-size: 12px; }
   .chip.active { background: #007bff; border-color: #0056b3; color: #fff; }
   .filter-drawer input[type=range] { width: 100%; }
   .filter-drawer select { width: 100%; padding: 6px; background: #222; color: #eee; border: 1px solid #555; border-radius: 4px; }
   #filter-toggle-btn { z-index: 1101; }
   ```

2. Add the drawer markup right after the existing `.hud-controls` div:
   ```html
   <div id="filter-drawer" class="filter-drawer">
       <div class="filter-section">
           <h4>Preset</h4>
           <select id="preset-select" onchange="applyPreset(this.value)">
               <option value="all">All properties</option>
               <option value="fresh">Fresh canvases</option>
               <option value="cluster">Cluster bombs</option>
               <option value="solo">Solo targets</option>
               <option value="repeat">Repeat offenders</option>
               <option value="long-term">Long-term abandoned</option>
               <option value="expiring">About to expire</option>
           </select>
       </div>
       <!-- Other sections injected by Tasks B2-B5 -->
   </div>
   ```

3. Add a toggle button to the existing HUD stack (top of `.hud-controls`):
   ```html
   <button id="filter-toggle-btn" class="hud-btn" onclick="document.getElementById('filter-drawer').classList.toggle('open')">🎛️ FILTERS</button>
   ```

4. Verify HTML still parses:
   ```bash
   python -c "
   import re
   c = open('index.html', encoding='utf-8').read()
   assert c.count('</html>') == 1
   print('OK')
   "
   ```

5. Commit:
   ```bash
   git add index.html
   git commit -m "Add filter drawer chrome and toggle"
   ```

---

## Task B2: Migrate filter logic to a single `applyFilters()` function

**Files:** Modify `index.html`.

**Steps:**

1. Add new state vars near the existing `let graffitiFilter` / `let colorMode` (around line 127):
   ```javascript
   const filterState = {
       monthsBack: 0,           // 0 = all time
       deadlineWindow: 'all',   // all | <7 | 7-14 | >14 | expired
       propertyTypes: new Set(['residential', 'commercial', 'mixed', 'vacant']),
       hasStructure: 'both',    // yes | no | both
       daysBlightedMin: 0,
       repeatOnly: false,
       activeRehab: 'all',      // exclude | all | only
       graffiti: 'all',         // legacy graffitiFilter, retained
       cluster: 'all',          // solo | cluster | both | all
       colorMode: 'status',     // legacy
   };
   ```

2. Add a centralized `applyFilters()` function just below `filterTimeline` (and call it instead at the existing call sites). The body merges the existing timeline filter, graffiti filter, and the new dimensions:
   ```javascript
   function applyFilters() {
       guiltyLayer.clearLayers(); uncommittedLayer.clearLayers();
       const cutoff = new Date(); cutoff.setMonth(cutoff.getMonth() - filterState.monthsBack);
       const heat = [];
       masterData.forEach(item => {
           const p = item.prop;
           // timeline
           if (filterState.monthsBack > 0 && item.date < cutoff) return;
           // graffiti (existing)
           if (!shouldShowByGraffiti(p)) return;
           // property type
           if (!filterState.propertyTypes.has(propertyCategory(p))) return;
           // has structure
           if (filterState.hasStructure !== 'both') {
               const want = filterState.hasStructure === 'yes';
               if (!!p.hasStructure !== want) return;
           }
           // days blighted
           if ((p.daysUnderBlight || 0) < filterState.daysBlightedMin) return;
           // repeat offender
           if (filterState.repeatOnly && (p.caseCount || 1) < 2) return;
           // active rehab
           if (filterState.activeRehab !== 'all') {
               const rehab = isActiveRehab(p);
               if (filterState.activeRehab === 'exclude' && rehab) return;
               if (filterState.activeRehab === 'only' && !rehab) return;
           }
           // deadline window
           if (filterState.deadlineWindow !== 'all' && !inDeadlineWindow(p, filterState.deadlineWindow)) return;
           // cluster (computed below in B5)
           if (filterState.cluster !== 'all' && !inClusterBucket(item, filterState.cluster)) return;

           heat.push([item.lat, item.lng]);
           addMarker(item.lat, item.lng, p, false);
       });
       heatLayer.setLatLngs(heat);
   }

   // Helpers (stub bodies; B5 fills inClusterBucket)
   function propertyCategory(p) {
       const desc = (p.landUseDesc || p.zoningDesc || '').toLowerCase();
       if (!p.hasStructure) return 'vacant';
       if (desc.includes('commercial')) return 'commercial';
       if (desc.includes('mixed')) return 'mixed';
       return 'residential';
   }
   function isActiveRehab(p) {
       if (!p.permitStatus || !p.permitFiling) return false;
       if (p.permitStatus.toLowerCase() !== 'permit issued') return false;
       const d = parseSocrataDate(p.permitFiling);
       const ageDays = (Date.now() - d.getTime()) / 86400000;
       return ageDays < 365 && /build|reno|repair/i.test(p.permitType || '');
   }
   function inDeadlineWindow(p, win) {
       if (!p.deadline) return win === 'expired' || win === 'all';
       const d = new Date(p.deadline);
       const days = (d.getTime() - Date.now()) / 86400000;
       if (win === 'expired') return days < 0;
       if (win === '<7') return days >= 0 && days < 7;
       if (win === '7-14') return days >= 7 && days <= 14;
       if (win === '>14') return days > 14;
       return true;
   }
   function inClusterBucket(_item, _bucket) { return true; }  // B5 implements
   ```

3. Replace the body of `window.filterTimeline` to delegate to `applyFilters`. The `time-slider` `onchange` updates `filterState.monthsBack` then calls `applyFilters`.

4. Call `applyFilters()` instead of `filterTimeline()` from the existing `addEventListener` paths and `cycleGraffitiFilter` / `cycleColorMode`.

5. Commit:
   ```bash
   git add index.html
   git commit -m "Centralize filter logic into applyFilters()"
   ```

---

## Task B3: Drawer controls + presets wiring

**Files:** Modify `index.html`.

**Steps:**

1. Inject filter sections into `#filter-drawer` (after the preset section). Each section is one filter dimension. Concrete markup:
   ```html
   <div class="filter-section">
       <h4>Property type</h4>
       <div class="chip-group" id="filter-types">
           <span class="chip active" data-type="residential">Residential</span>
           <span class="chip active" data-type="commercial">Commercial</span>
           <span class="chip active" data-type="mixed">Mixed-use</span>
           <span class="chip active" data-type="vacant">Vacant lot</span>
       </div>
   </div>
   <div class="filter-section">
       <h4>Deadline window</h4>
       <div class="chip-group" id="filter-deadline">
           <span class="chip active" data-win="all">All</span>
           <span class="chip" data-win="<7">&lt;7d</span>
           <span class="chip" data-win="7-14">7–14d</span>
           <span class="chip" data-win=">14">&gt;14d</span>
           <span class="chip" data-win="expired">Expired</span>
       </div>
   </div>
   <div class="filter-section">
       <h4>Has structure</h4>
       <div class="chip-group" id="filter-structure">
           <span class="chip" data-struct="yes">Yes</span>
           <span class="chip" data-struct="no">No</span>
           <span class="chip active" data-struct="both">Both</span>
       </div>
   </div>
   <div class="filter-section">
       <h4>Days under blight (min)</h4>
       <input type="range" id="filter-days-blighted" min="0" max="3650" step="30" value="0">
       <small id="filter-days-blighted-label">≥ 0 days</small>
   </div>
   <div class="filter-section">
       <h4><label><input type="checkbox" id="filter-repeat"> Repeat offender (≥2 cases)</label></h4>
   </div>
   <div class="filter-section">
       <h4>Active rehab</h4>
       <div class="chip-group" id="filter-rehab">
           <span class="chip" data-rehab="exclude">Exclude</span>
           <span class="chip active" data-rehab="all">All</span>
           <span class="chip" data-rehab="only">Only</span>
       </div>
   </div>
   <div class="filter-section">
       <h4>Cluster</h4>
       <div class="chip-group" id="filter-cluster">
           <span class="chip" data-cluster="solo">Solo</span>
           <span class="chip" data-cluster="cluster">Cluster</span>
           <span class="chip active" data-cluster="all">All</span>
       </div>
   </div>
   ```

2. Wire chip groups generically — one delegated handler near the bottom of the script:
   ```javascript
   document.querySelectorAll('.chip-group').forEach(group => {
       group.addEventListener('click', e => {
           const t = e.target.closest('.chip'); if (!t) return;
           if (group.id === 'filter-types') {
               // multi-select
               t.classList.toggle('active');
               filterState.propertyTypes = new Set(
                   [...group.querySelectorAll('.chip.active')].map(c => c.dataset.type)
               );
           } else {
               // single-select per group
               group.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
               t.classList.add('active');
               if (group.id === 'filter-deadline') filterState.deadlineWindow = t.dataset.win;
               if (group.id === 'filter-structure') filterState.hasStructure = t.dataset.struct;
               if (group.id === 'filter-rehab') filterState.activeRehab = t.dataset.rehab;
               if (group.id === 'filter-cluster') filterState.cluster = t.dataset.cluster;
           }
           applyFilters();
       });
   });

   document.getElementById('filter-days-blighted').addEventListener('input', e => {
       filterState.daysBlightedMin = parseInt(e.target.value, 10);
       document.getElementById('filter-days-blighted-label').textContent = `≥ ${filterState.daysBlightedMin} days`;
       applyFilters();
   });
   document.getElementById('filter-repeat').addEventListener('change', e => {
       filterState.repeatOnly = e.target.checked; applyFilters();
   });
   ```

3. Add `applyPreset(name)`:
   ```javascript
   const PRESETS = {
       all: {},
       fresh: { graffiti: 'clean', activeRehab: 'exclude', hasStructure: 'yes', deadlineWindow: '>14' },
       cluster: { cluster: 'cluster' },
       solo: { cluster: 'solo' },
       repeat: { repeatOnly: true },
       'long-term': { daysBlightedMin: 365, activeRehab: 'exclude' },
       expiring: { deadlineWindow: '<7' },
   };
   window.applyPreset = function(name) {
       const baseline = { monthsBack: 0, deadlineWindow: 'all',
           propertyTypes: new Set(['residential','commercial','mixed','vacant']),
           hasStructure: 'both', daysBlightedMin: 0, repeatOnly: false,
           activeRehab: 'all', graffiti: 'all', cluster: 'all', colorMode: filterState.colorMode };
       Object.assign(filterState, baseline, PRESETS[name] || {});
       syncDrawerControlsFromState();
       applyFilters();
   };

   function syncDrawerControlsFromState() {
       // Toggle chip .active classes to match filterState
       const update = (sel, attr, value) => {
           document.querySelectorAll(sel + ' .chip').forEach(c => {
               c.classList.toggle('active', c.dataset[attr] === value);
           });
       };
       update('#filter-deadline', 'win', filterState.deadlineWindow);
       update('#filter-structure', 'struct', filterState.hasStructure);
       update('#filter-rehab', 'rehab', filterState.activeRehab);
       update('#filter-cluster', 'cluster', filterState.cluster);
       document.querySelectorAll('#filter-types .chip').forEach(c => {
           c.classList.toggle('active', filterState.propertyTypes.has(c.dataset.type));
       });
       document.getElementById('filter-days-blighted').value = filterState.daysBlightedMin;
       document.getElementById('filter-days-blighted-label').textContent = `≥ ${filterState.daysBlightedMin} days`;
       document.getElementById('filter-repeat').checked = filterState.repeatOnly;
   }
   ```

4. Smoke-test in browser (manual): open `index.html` via `python -m http.server 8000`, click 🎛️ FILTERS, toggle a chip, confirm markers respond.

5. Commit:
   ```bash
   git add index.html
   git commit -m "Wire filter drawer controls and presets"
   ```

---

## Task B4: Cluster density (filter dimension #9)

**Files:** Modify `index.html`.

**Steps:**

1. Add a Haversine helper near `applyFilters`:
   ```javascript
   function haversineMeters(lat1, lng1, lat2, lng2) {
       const R = 6371000; const toRad = x => x * Math.PI / 180;
       const dLat = toRad(lat2 - lat1); const dLng = toRad(lng2 - lng1);
       const a = Math.sin(dLat/2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng/2) ** 2;
       return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
   }
   ```

2. Add a once-per-render-pass cluster-count cache (cleared at the start of `applyFilters`):
   ```javascript
   let _clusterCountCache = null;
   function ensureClusterCounts() {
       if (_clusterCountCache) return;
       _clusterCountCache = new Map();
       for (let i = 0; i < masterData.length; i++) {
           let count = 0;
           for (let j = 0; j < masterData.length; j++) {
               if (i === j) continue;
               if (haversineMeters(masterData[i].lat, masterData[i].lng,
                                   masterData[j].lat, masterData[j].lng) <= 100) count++;
           }
           _clusterCountCache.set(masterData[i].caseno, count);
       }
   }
   ```

3. Replace the stub `inClusterBucket`:
   ```javascript
   function inClusterBucket(item, bucket) {
       ensureClusterCounts();
       const n = _clusterCountCache.get(item.caseno) || 0;
       if (bucket === 'solo') return n === 0;
       if (bucket === 'cluster') return n >= 3;
       return true;
   }
   ```

4. Invalidate the cache when `masterData` changes — at the bottom of `fetchSectorData()` before the final `applyFilters()`:
   ```javascript
   _clusterCountCache = null;
   ```

5. (Optional) For 1000+ properties, the O(n²) is ~1M comparisons — fine in modern JS but worth measuring. Add a `console.time`/`console.timeEnd` around `ensureClusterCounts` for quick visibility:
   ```javascript
   function ensureClusterCounts() {
       if (_clusterCountCache) return;
       console.time('clusterCounts');
       /* ... existing body ... */
       console.timeEnd('clusterCounts');
   }
   ```

6. Smoke-test (browser): apply preset "Solo targets" — markers thin out; "Cluster bombs" — only see properties with ≥3 nearby blighted neighbors.

7. Commit:
   ```bash
   git add index.html
   git commit -m "Add cluster-density filter (solo/cluster buckets)"
   ```

---

# Part C — Property cards in list views

## Task C1: Card CSS + render helper

**Files:** Modify `index.html`.

**Steps:**

1. Add CSS:
   ```css
   .prop-row { padding: 12px; border-bottom: 1px solid #333; cursor: pointer; }
   .prop-row.expanded { background: #181818; }
   .prop-card { display: none; padding: 10px 0; border-top: 1px solid #333; margin-top: 10px; }
   .prop-row.expanded .prop-card { display: block; }
   .prop-card img.thumb { display: block; max-width: 256px; max-height: 256px; border-radius: 6px; margin-bottom: 10px; }
   .prop-card dl { margin: 0; font-size: 13px; }
   .prop-card dt { color: #FFBF00; font-weight: bold; margin-top: 6px; }
   .prop-card dd { margin: 2px 0 0 0; }
   .prop-card .actions { display: flex; gap: 8px; margin-top: 10px; }
   .prop-card .actions a, .prop-card .actions button { padding: 6px 10px; background: #222; color: #FFBF00; border: 1px solid #FFBF00; text-decoration: none; border-radius: 4px; cursor: pointer; font-size: 12px; }
   .prop-card .note-stash, .prop-card .img-preview { margin-top: 10px; }
   ```

2. Add a single `renderPropCard(item)` function that returns the card HTML. Used by both list-view modals.
   ```javascript
   function fmtField(label, value) {
       if (value === undefined || value === null || value === '') return '';
       return `<dt>${label}</dt><dd>${value}</dd>`;
   }
   function renderPropCard(item) {
       const p = item.prop;
       const safeAddr = p.address.replace(/'/g, "\\'");
       const note = getNote(safeAddr);
       const img = getImg(safeAddr);
       const thumb = p.streetviewThumbUrl
           ? `<img class="thumb" src="${p.streetviewThumbUrl}" loading="lazy" alt="Street View">`
           : `<div style="color:#666;font-style:italic;margin-bottom:10px">No street view image</div>`;
       const dirsUrl = `https://www.google.com/maps/dir/?api=1&destination=${item.lat},${item.lng}`;
       const mapsUrl = `https://maps.google.com/?q=${item.lat},${item.lng}`;
       const score = p.graffitiScore != null ? `${(p.graffitiScore*100).toFixed(0)}%` : '—';
       return `
       <div class="prop-card">
           ${thumb}
           <dl>
               ${fmtField('Status', p.status)}
               ${fmtField('Notice date', p.noticeDate)}
               ${fmtField('30-day deadline', p.deadline)}
               ${fmtField('Days under blight', p.daysUnderBlight)}
               ${fmtField('Case count', p.caseCount)}
               ${fmtField('Stage', p.stage)}
               ${fmtField('Land use', p.landUseDesc)}
               ${fmtField('Zoning', p.zoningDesc ? `${p.zoningClass} — ${p.zoningDesc}` : p.zoningClass)}
               ${fmtField('Has structure', p.hasStructure)}
               ${fmtField('Last grass cut', p.lastGrassCut)}
               ${fmtField('Last permit', p.permitType ? `${p.permitType} (${p.permitStatus})` : '')}
               ${fmtField('Next hearing', p.nextHearing)}
               ${fmtField('Graffiti likelihood', score)}
           </dl>
           <div class="actions">
               <a href="${mapsUrl}" target="_blank" rel="noopener">Open in Maps</a>
               <a href="${dirsUrl}" target="_blank" rel="noopener">Directions</a>
           </div>
           <textarea class="note-stash" placeholder="Intel stash..." onchange="saveNote('${safeAddr}', this.value)">${note}</textarea>
           <input type="file" accept="image/*" capture="environment" id="cam2_${safeAddr}" style="display:none;" onchange="captureOptics(event, '${safeAddr}', 'img2_${safeAddr}')">
           <button onclick="document.getElementById('cam2_${safeAddr}').click()">📸 Optics</button>
           <img id="img2_${safeAddr}" class="img-preview" src="${img}" style="display:${img ? 'block' : 'none'};max-width:256px;">
       </div>`;
   }
   ```

3. Commit:
   ```bash
   git add index.html
   git commit -m "Add property card renderer"
   ```

---

## Task C2: Convert `#blighted-modal` list to accordion rows

**Files:** Modify `index.html`.

**Steps:**

1. Replace the body of `showBlightedList`. Currently it builds a `<div class="list-item">` with address + deadline. Change to:
   ```javascript
   listContainer.innerHTML = visibleBlighted.length > 0
       ? visibleBlighted.map((item, i) => `
           <div class="prop-row" onclick="this.classList.toggle('expanded')">
               <strong>${item.prop.address}</strong><br>
               <small>Deadline: ${item.prop.deadline} · Score: ${item.prop.graffitiScore != null ? (item.prop.graffitiScore*100).toFixed(0)+'%' : '—'}</small>
               ${renderPropCard(item)}
           </div>`).join('')
       : '<p>No blighted properties in view.</p>';
   ```

2. Smoke-test (browser): open list, tap a row, card expands inline; tap again, collapses.

3. Commit:
   ```bash
   git add index.html
   git commit -m "Tappable accordion cards in blighted list"
   ```

---

## Task C3: Convert `#list-modal` table to accordion rows

**Files:** Modify `index.html`.

**Steps:**

1. The current `#list-modal` uses a `<table>`. Replace `toggleListView`'s body section that builds `<tr>` rows with the same `prop-row` pattern from C2. Keep the modal chrome (the `<h2>Database Records</h2>` etc.) but drop the `<thead>` since accordion items don't need column headers:
   ```javascript
   const container = document.querySelector('#list-modal') || document.getElementById('list-modal');
   // Replace the table-body wiring with:
   const list = document.createElement('div');
   list.id = 'list-modal-body';
   list.innerHTML = masterData.map(item => `
       <div class="prop-row" onclick="this.classList.toggle('expanded')">
           <strong>${item.prop.address}</strong>
           <small style="color:#888;display:block">${item.caseno} · ${item.prop.status} · ${item.prop.deadline}</small>
           ${renderPropCard(item)}
       </div>`).join('');
   const oldBody = document.querySelector('#list-modal #list-modal-body, #list-modal table');
   if (oldBody) oldBody.replaceWith(list);
   else document.getElementById('list-modal').appendChild(list);
   modal.style.display = 'block';
   ```

   Note: this preserves the existing `#list-modal` div container; only the table is replaced.

2. Add CSS to ensure the modal is scrollable on mobile:
   ```css
   #list-modal { display: none; position: fixed; inset: 0; background: #0b0b0b; z-index: 2000; padding: 60px 20px 20px 20px; overflow-y: auto; color: #eee; }
   #list-close { position: fixed; top: 10px; right: 10px; padding: 8px 14px; background: #900; color: #fff; border: none; border-radius: 4px; cursor: pointer; z-index: 2001; }
   ```

3. Smoke-test in browser.

4. Commit:
   ```bash
   git add index.html
   git commit -m "Tappable accordion cards in database list view"
   ```

---

## Task C4: Wire new fields through `fetchSectorData`

**Files:** Modify `index.html`.

**Steps:**

1. In the proxy branch of `fetchSectorData`, extend the `prop` object construction with all new sheet columns:
   ```javascript
   masterData.push({
       caseno, lat, lng, date: noticeDate,
       prop: {
           address, status,
           noticeDate: noticeDate.toLocaleDateString(),
           deadline: new Date(noticeDate.getTime() + 30*86400000).toLocaleDateString(),
           graffitiScore:        parseFloatOrNull(row.graffiti_score),
           graffitiPanoid:       row.graffiti_panoid || null,
           graffitiClassifiedAt: row.graffiti_classified_at || null,
           streetviewThumbUrl:   row.streetview_thumb_url || null,
           geopin:               row.geopin || null,
           initInspection:       row.init_inspection || null,
           permitType:           row.permit_type || null,
           permitStatus:         row.permit_status || null,
           permitFiling:         row.permit_filing || null,
           nextHearing:          row.next_hearing || null,
           stage:                row.stage || null,
           oc:                   row.o_c || null,
           zipcode:              row.zipcode || null,
           zoningClass:          row.zoning_class || null,
           zoningDesc:           row.zoning_desc || null,
           landUseDesc:          row.land_use_desc || null,
           hasStructure:         row.has_structure === 'yes' ? true : (row.has_structure === 'no' ? false : null),
           caseCount:            parseIntOrNull(row.case_count),
           earliestCaseDate:     row.earliest_case_date || null,
           daysUnderBlight:      parseIntOrNull(row.days_under_blight),
           lastGrassCut:         row.last_grass_cut || null,
       }
   });
   ```

2. Add helpers near `parseSocrataDate`:
   ```javascript
   function parseFloatOrNull(v) { if (v === undefined || v === null || v === '') return null; const n = parseFloat(v); return Number.isFinite(n) ? n : null; }
   function parseIntOrNull(v) { if (v === undefined || v === null || v === '') return null; const n = parseInt(v, 10); return Number.isFinite(n) ? n : null; }
   ```

3. Manual test: load the page, open the list view, expand a row — card shows all populated fields, hides empty ones (per `fmtField` early return).

4. Commit:
   ```bash
   git add index.html
   git commit -m "Parse all enrichment columns into masterData"
   ```

---

# Part D — Documentation

## Task D1: Operator setup for Drive folder

**Files:** Modify `docs/graffiti-pipeline.md`.

**Steps:**

1. Add a new section after the "Architecture" section:
   ```markdown
   ## Drive thumbnail folder (one-time setup)

   The classifier uploads a 256×256 thumbnail per property to a public Google Drive folder. Property cards in the map's list views render these inline.

   **One-time:**
   1. Create a folder in Google Drive (any name).
   2. Share it with the service account email used by `GOOGLE_CREDENTIALS` (Editor permission).
   3. Copy the folder ID from the URL: `https://drive.google.com/drive/folders/<FOLDER_ID>`.
   4. Add it as a GitHub Actions secret named `STREETVIEW_DRIVE_FOLDER_ID`.
   5. Ensure the GCP project has the Drive API enabled (alongside the Sheets API).

   When the secret is missing, `classify_graffiti.py` skips the upload step and the rest of the pipeline keeps working — list cards just show "No street view image" placeholders.
   ```

2. Add an `enrich_properties.py` row to the Files table.

3. Commit:
   ```bash
   git add docs/graffiti-pipeline.md
   git commit -m "Document Drive folder setup and enrichment job"
   ```

---

## Task D2: README update

**Files:** Modify `README.md`.

**Steps:**

1. Update Section 5 to mention the new filters and enrichment surface:
   ```markdown
   ## 5. Graffiti Classifier & Filters (Optional Enrichment)

   Each address is enriched with:
   - Property type, zoning, has-structure (NOLA cross-datasets)
   - Days under blight, case count, repeat-offender flag
   - Active-rehab signal (recent permits)
   - Last grass-cutting case (overgrowth proxy)
   - Graffiti likelihood (Street View + EfficientNet-B0)
   - Street View thumbnail (Google Drive cache)

   The map provides a multi-dim filter drawer with named presets ("Fresh canvases", "Cluster bombs", "Solo targets", etc.) and tappable property cards in both list views.

   See [`docs/graffiti-pipeline.md`](docs/graffiti-pipeline.md) for setup details (including the one-time `STREETVIEW_DRIVE_FOLDER_ID` secret). Model card: [`MODEL_CARD.md`](MODEL_CARD.md).
   ```

2. Commit:
   ```bash
   git add README.md
   git commit -m "Document filters and enrichments in README"
   ```

---

# Part E — Final verification

## Task E1: Suite + manual smoke

**Steps:**

1. Run full test suite:
   ```bash
   python -m pytest -v
   ```
   Expected: previous 13 + 5 enrichment + 2 drive uploader = 20 passed.

2. All imports clean:
   ```bash
   python -c "import scripts.update_database, scripts.enrich_properties, scripts.classify_graffiti, scripts.streetview_scrape; print('OK')"
   ```

3. Open `index.html` in a local server, manually verify:
   - 🎛️ FILTERS button opens drawer
   - Each chip group toggles correctly
   - Each preset applies the expected combination
   - Both list views render accordion rows; tap expands; card shows fields + thumbnail placeholder (no thumbnails yet without Drive)
   - All previous functionality (timeline, GPS, popups, ghost) still works

## Task E2: Push and PR

```bash
git push origin claude/vibrant-hawking-dd3434
```

Open PR: [github.com/nhenia/blight/pull/new/claude/vibrant-hawking-dd3434](https://github.com/nhenia/blight/pull/new/claude/vibrant-hawking-dd3434).

PR body summarizes WS1: enrichment pipeline + filter drawer + property cards + Drive thumbnail integration. Marks WS2 (Mapillary fallback) and WS3 (graffiti-photos index) as out-of-scope follow-ups.
