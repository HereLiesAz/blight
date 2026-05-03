# Demolitions, Permits & Evictions Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ingest three new data streams — demolition lifecycle (3 NOLA Socrata sources), full per-property permit history (1 NOLA Socrata source), and eviction filings (scraped from First & Second City Court) — surfacing each as both per-property enrichment columns (filterable) and city-wide map layers (visualized).

**Architecture:** Three new orchestrator scripts mirror the existing `enrich_properties.py`/`classify_graffiti.py` pattern. They share two new utility modules (`court_scraper`, `geocoder`) and one new lib per source. The main blight tab gains 6 enrichment columns; two new worksheet tabs (`demolitions`, `evictions`) drive city-wide layers. UI gains 3 filter chips, 2 layer toggles, and 3 property-card sections.

**Tech Stack:** Python 3.12 (CI) / 3.13 (dev), `requests`, `gspread`, BeautifulSoup4 (new — for parsing court Odyssey HTML), `responses` for HTTP mocking in tests, Vanilla JS + Leaflet.

**Reference design:** [docs/plans/2026-05-03-demolitions-permits-evictions-design.md](2026-05-03-demolitions-permits-evictions-design.md)

---

## Conventions

- Pattern matches existing scripts: idempotent orchestrators that read GOOGLE_CREDENTIALS, throttle, write back via gspread, soft-fail in CI.
- Each task ends with a commit. Each new lib module ships with TDD tests. Orchestrators get a smoke-import test only — full integration is verified manually post-deploy.
- One new pip dep: `beautifulsoup4==4.13.4`.
- Forward-slash paths everywhere.

---

# Part A — Shared utilities

## Task A1: Address normalizer (TDD)

**Files:**
- Create: `scripts/lib/address.py`
- Create: `tests/test_address.py`

**Step 1: Failing test**

```python
# tests/test_address.py
import pytest
from scripts.lib.address import normalize_address

@pytest.mark.parametrize("raw,expected", [
    ("123 N. Rampart Street", "123 N RAMPART ST"),
    ("123  N Rampart  st.", "123 N RAMPART ST"),
    ("0123 St. Claude Avenue", "123 ST CLAUDE AVE"),
    ("3201 N Galvez Boulevard, NEW ORLEANS, LA 70117", "3201 N GALVEZ BLVD"),
    ("  ", ""),
    ("", ""),
    ("123 Main Drive", "123 MAIN DR"),
    ("4500 South Robertson Court", "4500 S ROBERTSON CT"),
])
def test_normalize_address(raw, expected):
    assert normalize_address(raw) == expected
```

**Step 2: Implement `scripts/lib/address.py`**

```python
"""Canonicalize property addresses for cross-dataset joins."""
from __future__ import annotations
import re

_ABBREV = {
    'STREET': 'ST', 'AVENUE': 'AVE', 'BOULEVARD': 'BLVD', 'ROAD': 'RD',
    'DRIVE': 'DR', 'COURT': 'CT', 'PLACE': 'PL', 'LANE': 'LN',
    'PARKWAY': 'PKWY', 'HIGHWAY': 'HWY', 'CIRCLE': 'CIR', 'TERRACE': 'TER',
    'NORTH': 'N', 'SOUTH': 'S', 'EAST': 'E', 'WEST': 'W',
    'SAINT': 'ST',
}
_TRAILING = re.compile(r',\s*(NEW ORLEANS|NOLA|LA|LOUISIANA)\b.*$', re.IGNORECASE)
_ZIP = re.compile(r'\b\d{5}(?:-\d{4})?\b')

def normalize_address(raw: str | None) -> str:
    if not raw:
        return ""
    s = raw.strip().upper()
    s = _TRAILING.sub('', s)
    s = _ZIP.sub('', s)
    s = s.replace('.', ' ').replace(',', ' ')
    tokens = [t for t in s.split() if t]
    out = []
    for t in tokens:
        out.append(_ABBREV.get(t, t))
    s = ' '.join(out)
    # Strip leading zeroes from house number (first token if all-digits)
    if out and out[0].isdigit():
        out[0] = str(int(out[0]))
        s = ' '.join(out)
    return s
```

**Step 3: Run, expect 8 passed**

```bash
python -m pytest tests/test_address.py -v
```

**Step 4: Commit**

```bash
git add scripts/lib/address.py tests/test_address.py
git commit -m "Add address normalizer for cross-dataset joins"
```

---

## Task A2: Nominatim geocoder (TDD)

**Files:**
- Create: `scripts/lib/geocoder.py`
- Create: `tests/test_geocoder.py`
- Create: `tests/fixtures/nominatim_match.json`
- Create: `tests/fixtures/nominatim_outside_bbox.json`

**Step 1: Fixtures**

`tests/fixtures/nominatim_match.json`:
```json
[{"place_id": 1, "lat": "29.964", "lon": "-90.007", "display_name": "1234 St Claude Ave, New Orleans, LA, USA", "importance": 0.5}]
```

`tests/fixtures/nominatim_outside_bbox.json`:
```json
[{"place_id": 2, "lat": "40.71", "lon": "-74.00", "display_name": "1234 St Claude Ave, New York, NY, USA", "importance": 0.3}]
```

**Step 2: Failing tests**

```python
# tests/test_geocoder.py
import json, pathlib
import responses
from scripts.lib.geocoder import geocode, NOLA_BBOX

FX = pathlib.Path('tests/fixtures')

@responses.activate
def test_geocode_returns_lat_lng_for_nola_match():
    body = (FX / 'nominatim_match.json').read_text()
    responses.add(responses.GET, "https://nominatim.openstreetmap.org/search", body=body, status=200, content_type='application/json')
    out = geocode("1234 St Claude Ave")
    assert out is not None
    assert abs(out['lat'] - 29.964) < 0.001
    assert abs(out['lng'] - -90.007) < 0.001

@responses.activate
def test_geocode_rejects_outside_nola_bbox():
    body = (FX / 'nominatim_outside_bbox.json').read_text()
    responses.add(responses.GET, "https://nominatim.openstreetmap.org/search", body=body, status=200, content_type='application/json')
    assert geocode("1234 St Claude Ave") is None

@responses.activate
def test_geocode_returns_none_on_empty_response():
    responses.add(responses.GET, "https://nominatim.openstreetmap.org/search", json=[], status=200)
    assert geocode("nowhere") is None

def test_nola_bbox_constants():
    assert NOLA_BBOX["south"] == 29.85
    assert NOLA_BBOX["west"] == -90.15
    assert NOLA_BBOX["north"] == 30.10
    assert NOLA_BBOX["east"] == -89.85
```

**Step 3: Implement `scripts/lib/geocoder.py`**

```python
"""Nominatim wrapper, NOLA-bbox-clipped, throttled."""
from __future__ import annotations
import time
import requests

NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
NOLA_BBOX = {"south": 29.85, "west": -90.15, "north": 30.10, "east": -89.85}
USER_AGENT = "blight-graffiti-scraper (https://github.com/nhenia/blight)"
_last_request_at = 0.0

def _throttle(min_interval_s: float = 1.1):
    """Nominatim's ToS: max 1 req/s. Be polite."""
    global _last_request_at
    elapsed = time.time() - _last_request_at
    if elapsed < min_interval_s:
        time.sleep(min_interval_s - elapsed)
    _last_request_at = time.time()

def geocode(address: str, *, session: requests.Session | None = None, timeout: float = 10.0) -> dict | None:
    """Geocode an address; return {lat, lng, display_name} or None.

    None is returned when:
    - Nominatim returns no results
    - The top result falls outside the NOLA bounding box
    - The request fails
    """
    if not address or not address.strip():
        return None
    s = session or requests
    _throttle()
    try:
        r = s.get(
            NOMINATIM_URL,
            params={"q": address, "format": "json", "limit": "1", "countrycodes": "us"},
            headers={"User-Agent": USER_AGENT},
            timeout=timeout,
        )
        r.raise_for_status()
        rows = r.json()
    except requests.RequestException:
        return None
    if not rows:
        return None
    top = rows[0]
    try:
        lat = float(top["lat"])
        lng = float(top["lon"])
    except (KeyError, ValueError):
        return None
    if not (NOLA_BBOX["south"] <= lat <= NOLA_BBOX["north"]
            and NOLA_BBOX["west"] <= lng <= NOLA_BBOX["east"]):
        return None
    return {"lat": lat, "lng": lng, "display_name": top.get("display_name", "")}
```

**Step 4: Run + commit**

```bash
python -m pytest tests/test_geocoder.py -v
git add scripts/lib/geocoder.py tests/test_geocoder.py tests/fixtures/nominatim_match.json tests/fixtures/nominatim_outside_bbox.json
git commit -m "Add NOLA-bbox-clipped Nominatim geocoder"
```

---

## Task A3: Add `beautifulsoup4` to requirements

**Files:** Modify `scripts/requirements.txt`.

**Step 1: Append (in the ML pipeline block)**

```
beautifulsoup4==4.13.4
```

**Step 2: Install + verify**

```bash
pip install beautifulsoup4==4.13.4
python -c "from bs4 import BeautifulSoup; print('OK')"
```

**Step 3: Commit**

```bash
git add scripts/requirements.txt
git commit -m "Add beautifulsoup4 dep for court HTML parsing"
```

---

# Part B — Demolitions

## Task B1: `scripts/lib/demolitions.py` (TDD)

**Files:**
- Create: `scripts/lib/demolitions.py`
- Create: `tests/test_demolitions.py`
- Create: `tests/fixtures/demolitions_completed.json`
- Create: `tests/fixtures/demolitions_permits.json`

**Step 1: Fixtures**

`tests/fixtures/demolitions_completed.json` (BlightStatus Demolitions sample):
```json
[
  {"objectid": "100", "address": "1234 N RAMPART ST", "geopin": "41126231", "demolitiondate": "2024-06-15T00:00:00.000", "the_geom": {"type": "Point", "coordinates": [-90.012, 29.957]}}
]
```

`tests/fixtures/demolitions_permits.json` (Permit Apps Demolition rows):
```json
[
  {"applicationnumber": "21-25976", "address": "5678 ST CLAUDE AVE", "geopin": "41036019", "applicationdate": "2025-09-10T00:00:00.000", "applicationstatus": "Permit Issued", "permittype": "Demolition", "the_geom": {"type": "Point", "coordinates": [-90.04, 29.96]}}
]
```

**Step 2: Failing tests**

```python
# tests/test_demolitions.py
import json, pathlib
import responses
from scripts.lib.demolitions import (
    fetch_completed_demolitions, fetch_demolition_permits, status_for_geopin,
)

FX = pathlib.Path('tests/fixtures')

@responses.activate
def test_fetch_completed_demolitions_yields_normalized_rows():
    body = (FX / 'demolitions_completed.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/e3wd-h7q2.json", body=body, status=200, content_type='application/json')
    rows = fetch_completed_demolitions()
    assert len(rows) == 1
    r = rows[0]
    assert r['status'] == 'completed'
    assert r['geopin'] == '41126231'
    assert r['lat'] is not None
    assert r['source'] == 'e3wd-h7q2'

@responses.activate
def test_fetch_demolition_permits_classifies_by_status():
    body = (FX / 'demolitions_permits.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/aib5-en5t.json", body=body, status=200, content_type='application/json')
    rows = fetch_demolition_permits()
    assert len(rows) == 1
    r = rows[0]
    assert r['status'] == 'permitted'  # Permit Issued -> permitted
    assert r['source'] == 'aib5-en5t'

@responses.activate
def test_status_for_geopin_picks_most_advanced_lifecycle_event():
    # Completed > permitted > pending; for the same geopin, return most-advanced.
    completed = (FX / 'demolitions_completed.json').read_text()
    permits = (FX / 'demolitions_permits.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/e3wd-h7q2.json", body=completed, status=200, content_type='application/json')
    responses.add(responses.GET, "https://data.nola.gov/resource/aib5-en5t.json", body=permits, status=200, content_type='application/json')
    s = status_for_geopin("41126231")
    assert s['status'] == 'completed'
    assert s['date'].startswith('2024-06-15')
```

**Step 3: Implement**

```python
# scripts/lib/demolitions.py
"""Demolition lifecycle: ingest from BlightStatus + Permit Apps."""
from __future__ import annotations
import requests

_BASE = "https://data.nola.gov/resource"
_LIFECYCLE_RANK = {"pending": 0, "permitted": 1, "completed": 2}


def _coords(row):
    g = row.get("the_geom")
    if isinstance(g, dict) and g.get("coordinates"):
        c = g["coordinates"]
        return float(c[1]), float(c[0])  # lat, lng
    return None, None


def _classify_permit_status(permit_status: str) -> str:
    s = (permit_status or "").lower()
    if "issued" in s:
        return "permitted"
    if "complete" in s:
        return "completed"
    return "pending"


def fetch_completed_demolitions(*, session: requests.Session | None = None) -> list[dict]:
    s = session or requests
    r = s.get(f"{_BASE}/e3wd-h7q2.json", params={"$limit": "5000"}, timeout=30)
    r.raise_for_status()
    out = []
    for row in r.json():
        lat, lng = _coords(row)
        out.append({
            "id": f"e3wd:{row.get('objectid', '')}",
            "address": row.get("address", ""),
            "geopin": row.get("geopin", ""),
            "lat": lat, "lng": lng,
            "status": "completed",
            "event_date": row.get("demolitiondate", "")[:10] if row.get("demolitiondate") else "",
            "source": "e3wd-h7q2",
            "permit_no": "",
        })
    return out


def fetch_demolition_permits(*, session: requests.Session | None = None) -> list[dict]:
    s = session or requests
    r = s.get(
        f"{_BASE}/aib5-en5t.json",
        params={"$where": "permittype='Demolition'", "$limit": "5000"},
        timeout=30,
    )
    r.raise_for_status()
    out = []
    for row in r.json():
        lat, lng = _coords(row)
        out.append({
            "id": f"aib5:{row.get('applicationnumber', '')}",
            "address": row.get("address", ""),
            "geopin": row.get("geopin", ""),
            "lat": lat, "lng": lng,
            "status": _classify_permit_status(row.get("applicationstatus", "")),
            "event_date": row.get("applicationdate", "")[:10] if row.get("applicationdate") else "",
            "source": "aib5-en5t",
            "permit_no": row.get("applicationnumber", ""),
        })
    return out


def status_for_geopin(geopin: str, *, session: requests.Session | None = None) -> dict | None:
    """Return the most-advanced lifecycle status + date for a single geopin, or None."""
    if not geopin:
        return None
    rows = fetch_completed_demolitions(session=session) + fetch_demolition_permits(session=session)
    matches = [r for r in rows if r["geopin"] == geopin]
    if not matches:
        return None
    matches.sort(key=lambda r: (_LIFECYCLE_RANK[r["status"]], r["event_date"]), reverse=True)
    top = matches[0]
    return {"status": top["status"], "date": top["event_date"], "source": top["source"]}
```

**Step 4: Run, commit**

```bash
python -m pytest tests/test_demolitions.py -v
git add scripts/lib/demolitions.py tests/test_demolitions.py tests/fixtures/demolitions_completed.json tests/fixtures/demolitions_permits.json
git commit -m "Add demolition lifecycle ingest helpers"
```

---

## Task B2: `scripts/ingest_demolitions.py` orchestrator

**Files:**
- Create: `scripts/ingest_demolitions.py`
- Modify: `scripts/lib/sheet.py` (add `DEMOLITION_COLUMNS`, `EVICTION_COLUMNS`, `PERMIT_COLUMNS`)

**Step 1: Add column constants to `scripts/lib/sheet.py`**

After `ENRICHMENT_COLUMNS`:

```python
DEMOLITION_COLUMNS = ("demolition_status", "demolition_date")
PERMIT_COLUMNS = ("permit_count_365d", "permit_types_recent")
EVICTION_COLUMNS = ("eviction_count_365d", "last_eviction_date")

def ensure_demolition_columns(header_row: list[str]) -> list[str]:
    out = list(header_row)
    for col in DEMOLITION_COLUMNS + PERMIT_COLUMNS + EVICTION_COLUMNS:
        if col not in out:
            out.append(col)
    return out
```

**Step 2: Implement orchestrator**

```python
# scripts/ingest_demolitions.py
"""Per-property + city-wide demolition lifecycle ingest.

Updates main blight tab with demolition_status / demolition_date.
Replaces the contents of the `demolitions` worksheet with the full city-wide list.
"""
from __future__ import annotations
import json
import os
import sys
import gspread
from google.oauth2.service_account import Credentials

from scripts.lib.demolitions import (
    fetch_completed_demolitions, fetch_demolition_permits, _LIFECYCLE_RANK,
)
from scripts.lib.sheet import (
    DEMOLITION_COLUMNS, PERMIT_COLUMNS, EVICTION_COLUMNS, ensure_demolition_columns,
)

SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'


def _open_book():
    creds = json.loads(os.environ["GOOGLE_CREDENTIALS"])
    scopes = ['https://www.googleapis.com/auth/spreadsheets']
    return gspread.authorize(Credentials.from_service_account_info(creds, scopes=scopes)).open_by_key(SPREADSHEET_ID)


def _get_or_create_tab(book, name: str, header: list[str]):
    try:
        ws = book.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        ws = book.add_worksheet(title=name, rows=2000, cols=max(len(header), 12))
        ws.update([header], "A1")
    return ws


def main() -> int:
    book = _open_book()
    main_sheet = book.sheet1

    print("Fetching demolitions...")
    completed = fetch_completed_demolitions()
    permits = fetch_demolition_permits()
    all_rows = completed + permits
    print(f"  completed={len(completed)} permits={len(permits)} total={len(all_rows)}")

    # Build geopin -> most-advanced
    by_geopin: dict[str, dict] = {}
    for r in all_rows:
        g = r["geopin"]
        if not g:
            continue
        cur = by_geopin.get(g)
        if cur is None or _LIFECYCLE_RANK[r["status"]] > _LIFECYCLE_RANK[cur["status"]]:
            by_geopin[g] = r

    # Update main blight tab columns
    rows = main_sheet.get_all_values()
    if rows:
        header = ensure_demolition_columns(rows[0])
        if header != rows[0]:
            main_sheet.update([header], "A1")
        col_idx = {n: i for i, n in enumerate(header)}
        cells = []
        for r_i, row in enumerate(rows[1:], start=2):
            row += [""] * (len(header) - len(row))
            rd = dict(zip(header, row))
            geopin = (rd.get("geopin") or "").strip()
            if not geopin:
                continue
            entry = by_geopin.get(geopin)
            if not entry:
                continue
            current_status = rd.get("demolition_status", "")
            current_date = rd.get("demolition_date", "")
            if current_status == entry["status"] and current_date == entry["event_date"]:
                continue
            cells.append(gspread.Cell(r_i, col_idx["demolition_status"] + 1, entry["status"]))
            cells.append(gspread.Cell(r_i, col_idx["demolition_date"] + 1, entry["event_date"]))
        if cells:
            main_sheet.update_cells(cells)
            print(f"Updated {len(cells)//2} blight rows with demolition status.")

    # Replace the city-wide tab
    DEMO_TAB_HEADER = ["id", "address", "geopin", "lat", "lng", "status", "event_date", "source", "permit_no"]
    demo_ws = _get_or_create_tab(book, "demolitions", DEMO_TAB_HEADER)
    payload = [DEMO_TAB_HEADER] + [
        [r["id"], r["address"], r["geopin"], r["lat"] or "", r["lng"] or "",
         r["status"], r["event_date"], r["source"], r["permit_no"]]
        for r in all_rows
    ]
    demo_ws.clear()
    demo_ws.update(payload, "A1")
    print(f"Wrote {len(all_rows)} rows to `demolitions` tab.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

**Step 3: Smoke test, commit**

```bash
python -c "import scripts.ingest_demolitions; print('OK')"
python -m pytest -v 2>&1 | tail -3
git add scripts/ingest_demolitions.py scripts/lib/sheet.py
git commit -m "Add demolitions orchestrator and per-property update"
```

---

# Part C — Permits

## Task C1: `scripts/lib/permits.py` (TDD)

**Files:**
- Create: `scripts/lib/permits.py`
- Create: `tests/test_permits.py`
- Create: `tests/fixtures/permits_by_geopin.json`

**Step 1: Fixture** — `tests/fixtures/permits_by_geopin.json`:
```json
[
  {"applicationnumber": "25-100", "permittype": "Renovation", "applicationdate": "2025-12-01T00:00:00.000"},
  {"applicationnumber": "25-080", "permittype": "Mechanical HVAC", "applicationdate": "2025-08-15T00:00:00.000"},
  {"applicationnumber": "24-300", "permittype": "Electrical", "applicationdate": "2024-11-20T00:00:00.000"},
  {"applicationnumber": "20-005", "permittype": "Renovation", "applicationdate": "2020-03-01T00:00:00.000"}
]
```

**Step 2: Failing tests**

```python
# tests/test_permits.py
import datetime as dt
import json, pathlib
import responses
from scripts.lib.permits import fetch_permits_for_geopin, summarize_permits

FX = pathlib.Path('tests/fixtures')

@responses.activate
def test_fetch_permits_for_geopin():
    body = (FX / 'permits_by_geopin.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/nbcf-m6c2.json", body=body, status=200, content_type='application/json')
    rows = fetch_permits_for_geopin("41126231")
    assert len(rows) == 4

def test_summarize_permits_counts_within_365_days():
    rows = json.loads((FX / 'permits_by_geopin.json').read_text())
    now = dt.datetime(2026, 5, 3, tzinfo=dt.timezone.utc)
    s = summarize_permits(rows, now=now)
    # 25-100, 25-080, 24-300 within 365d? 24-300 was 2024-11-20, now 2026-05-03 -> ~530 days, OUT
    # 25-100 (Dec 2025), 25-080 (Aug 2025) -> 2 within 365d
    assert s['count_365d'] == 2
    # types_recent is the last-5 in date-desc order
    assert s['types_recent'] == "Renovation,Mechanical HVAC,Electrical,Renovation"

def test_summarize_permits_handles_empty():
    s = summarize_permits([])
    assert s == {"count_365d": 0, "types_recent": ""}
```

**Step 3: Implement**

```python
# scripts/lib/permits.py
"""Per-property permit history aggregates."""
from __future__ import annotations
import datetime as _dt
import requests

_BASE = "https://data.nola.gov/resource"


def fetch_permits_for_geopin(geopin: str, *, session: requests.Session | None = None) -> list[dict]:
    if not geopin:
        return []
    s = session or requests
    r = s.get(
        f"{_BASE}/nbcf-m6c2.json",
        params={
            "$where": f"geopin='{geopin}'",
            "$select": "applicationnumber, permittype, applicationdate, applicationstatus",
            "$order": "applicationdate DESC",
            "$limit": "200",
        },
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def summarize_permits(rows: list[dict], *, now: _dt.datetime | None = None) -> dict:
    if not rows:
        return {"count_365d": 0, "types_recent": ""}
    n = now or _dt.datetime.now(_dt.timezone.utc)
    cutoff = n - _dt.timedelta(days=365)
    count = 0
    for r in rows:
        d_str = (r.get("applicationdate") or "")[:10]
        try:
            d = _dt.datetime.fromisoformat(d_str).replace(tzinfo=_dt.timezone.utc)
        except ValueError:
            continue
        if d >= cutoff:
            count += 1
    types = [r.get("permittype", "") for r in rows[:5] if r.get("permittype")]
    return {"count_365d": count, "types_recent": ",".join(types)}
```

**Step 4: Run, commit**

```bash
python -m pytest tests/test_permits.py -v
git add scripts/lib/permits.py tests/test_permits.py tests/fixtures/permits_by_geopin.json
git commit -m "Add per-geopin permit history helpers"
```

---

## Task C2: `scripts/ingest_permits.py` orchestrator

**Files:**
- Create: `scripts/ingest_permits.py`

**Step 1: Implement**

```python
# scripts/ingest_permits.py
"""Per-blight-property permit history ingest. No city-wide tab (would be too large)."""
from __future__ import annotations
import json
import os
import sys
import time
import gspread
from google.oauth2.service_account import Credentials

from scripts.lib.permits import fetch_permits_for_geopin, summarize_permits
from scripts.lib.sheet import (
    DEMOLITION_COLUMNS, PERMIT_COLUMNS, EVICTION_COLUMNS, ensure_demolition_columns,
)

SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
MIN_INTERVAL_S = float(os.environ.get("PERMITS_MIN_INTERVAL_S", "0.5"))
MAX_PER_RUN = int(os.environ.get("PERMITS_MAX_PER_RUN", "300"))


def _open_sheet():
    creds = json.loads(os.environ["GOOGLE_CREDENTIALS"])
    scopes = ['https://www.googleapis.com/auth/spreadsheets']
    return gspread.authorize(Credentials.from_service_account_info(creds, scopes=scopes)).open_by_key(SPREADSHEET_ID).sheet1


def main() -> int:
    sheet = _open_sheet()
    rows = sheet.get_all_values()
    if not rows:
        print("Empty sheet."); return 0

    header = ensure_demolition_columns(rows[0])
    if header != rows[0]:
        sheet.update([header], "A1")
    col_idx = {n: i for i, n in enumerate(header)}

    processed = 0
    for r_i, row in enumerate(rows[1:], start=2):
        row += [""] * (len(header) - len(row))
        rd = dict(zip(header, row))
        geopin = (rd.get("geopin") or "").strip()
        if not geopin:
            continue
        if processed >= MAX_PER_RUN:
            print(f"Hit MAX_PER_RUN={MAX_PER_RUN}; stopping."); break
        try:
            permits = fetch_permits_for_geopin(geopin)
            summary = summarize_permits(permits)
        except Exception as e:
            print(f"row {r_i} {geopin}: {e}", file=sys.stderr); continue
        cells = [
            gspread.Cell(r_i, col_idx["permit_count_365d"] + 1, str(summary["count_365d"])),
            gspread.Cell(r_i, col_idx["permit_types_recent"] + 1, summary["types_recent"]),
        ]
        sheet.update_cells(cells)
        processed += 1
        time.sleep(MIN_INTERVAL_S)

    print(f"Done. processed={processed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

**Step 2: Smoke test, commit**

```bash
python -c "import scripts.ingest_permits; print('OK')"
git add scripts/ingest_permits.py
git commit -m "Add per-property permit history orchestrator"
```

---

# Part D — Evictions

## Task D1: `scripts/lib/court_scraper.py` (TDD)

**Files:**
- Create: `scripts/lib/court_scraper.py`
- Create: `tests/test_court_scraper.py`
- Create: `tests/fixtures/odyssey_results_page.html` (fake HTML representative of Odyssey)
- Create: `tests/fixtures/odyssey_captcha_page.html` (fake CAPTCHA-block page)

**Step 1: Fixtures**

`tests/fixtures/odyssey_results_page.html`:
```html
<html><body>
<table class="odyResults">
  <tr><th>Case Number</th><th>Filed</th><th>Type</th><th>Plaintiff</th><th>Defendant</th><th>Address</th></tr>
  <tr><td>2025-EV-00123</td><td>2025-12-15</td><td>Rule for Possession</td><td>Holdings LLC</td><td>JANE SMITH</td><td>123 N RAMPART ST</td></tr>
  <tr><td>2025-EV-00124</td><td>2025-12-16</td><td>Rule for Possession</td><td>Acme Realty</td><td>JOHN A DOE</td><td>456 St Claude Ave</td></tr>
</table>
</body></html>
```

`tests/fixtures/odyssey_captcha_page.html`:
```html
<html><body>
<div class="g-recaptcha"></div>
<p>Please verify you are not a robot.</p>
</body></html>
```

**Step 2: Failing tests**

```python
# tests/test_court_scraper.py
import pathlib
import pytest
from scripts.lib.court_scraper import (
    parse_odyssey_results, looks_like_captcha, redact_defendant,
)

FX = pathlib.Path('tests/fixtures')

def test_parse_odyssey_results_extracts_eviction_rows():
    html = (FX / 'odyssey_results_page.html').read_text()
    rows = parse_odyssey_results(html, court='SCC')
    assert len(rows) == 2
    r = rows[0]
    assert r['case_no'] == '2025-EV-00123'
    assert r['court'] == 'SCC'
    assert r['filed_date'] == '2025-12-15'
    assert r['plaintiff'] == 'Holdings LLC'
    assert r['defendant_redacted'] == 'J. SMITH'
    assert r['address'] == '123 N RAMPART ST'  # already normalized

def test_parse_odyssey_handles_middle_initial_in_defendant():
    html = (FX / 'odyssey_results_page.html').read_text()
    rows = parse_odyssey_results(html, court='SCC')
    assert rows[1]['defendant_redacted'] == 'J. DOE'  # 'JOHN A DOE' -> first initial + last name

def test_parse_odyssey_returns_empty_on_no_table():
    rows = parse_odyssey_results("<html><body><p>No results.</p></body></html>", court='FCC')
    assert rows == []

def test_looks_like_captcha():
    assert looks_like_captcha((FX / 'odyssey_captcha_page.html').read_text())
    assert not looks_like_captcha((FX / 'odyssey_results_page.html').read_text())

def test_redact_defendant_handles_edge_cases():
    assert redact_defendant("JANE SMITH") == "J. SMITH"
    assert redact_defendant("JOHN A DOE") == "J. DOE"
    assert redact_defendant("MADONNA") == "M."
    assert redact_defendant("") == ""
    assert redact_defendant("  jane  smith  ") == "J. SMITH"
```

**Step 3: Implement**

```python
# scripts/lib/court_scraper.py
"""Shared utilities for First/Second City Court Odyssey scraping."""
from __future__ import annotations
import re
from bs4 import BeautifulSoup

from scripts.lib.address import normalize_address

_CAPTCHA_HINTS = ("g-recaptcha", "are not a robot", "captcha")


def looks_like_captcha(html: str) -> bool:
    h = (html or "").lower()
    return any(h.find(s) >= 0 for s in _CAPTCHA_HINTS)


def redact_defendant(name: str) -> str:
    if not name:
        return ""
    parts = re.split(r"\s+", name.strip().upper())
    parts = [p for p in parts if p]
    if not parts:
        return ""
    if len(parts) == 1:
        return f"{parts[0][0]}."
    return f"{parts[0][0]}. {parts[-1]}"


def parse_odyssey_results(html: str, *, court: str) -> list[dict]:
    """Parse a typical Odyssey case-search results HTML page into a list of eviction rows.

    The actual Odyssey markup varies; this function targets the common pattern of
    <table class="odyResults"> with header row + data rows. Adjust selectors as needed
    when scraper goes live.
    """
    soup = BeautifulSoup(html, "html.parser")
    table = soup.find("table", class_=re.compile(r"odyResults", re.IGNORECASE))
    if not table:
        return []
    out = []
    for tr in table.find_all("tr"):
        ths = tr.find_all("th")
        if ths:
            continue
        tds = [td.get_text(strip=True) for td in tr.find_all("td")]
        if len(tds) < 6:
            continue
        case_no, filed_date, case_type, plaintiff, defendant, address = tds[:6]
        if "rule for possession" not in case_type.lower():
            continue
        out.append({
            "case_no": case_no,
            "court": court,
            "filed_date": filed_date,
            "case_type": case_type,
            "plaintiff": plaintiff,
            "defendant_redacted": redact_defendant(defendant),
            "address": normalize_address(address),
        })
    return out
```

**Step 4: Run, commit**

```bash
python -m pytest tests/test_court_scraper.py -v
git add scripts/lib/court_scraper.py tests/test_court_scraper.py tests/fixtures/odyssey_results_page.html tests/fixtures/odyssey_captcha_page.html
git commit -m "Add court scraper utilities (Odyssey HTML parsing, defendant redaction)"
```

---

## Task D2: `scripts/scrape_evictions.py` orchestrator

**Files:**
- Create: `scripts/scrape_evictions.py`

**Step 1: Implement** (read all of this carefully — the actual court URL constants are placeholders the user will fill in once we know the live URLs)

```python
# scripts/scrape_evictions.py
"""Daily eviction scraper for First & Second City Court (NOLA Odyssey portals).

REQUIRED env: GOOGLE_CREDENTIALS.
Optional env: COURT_FCC_URL, COURT_SCC_URL (must be set or this script soft-skips).

Strategy: query each court for "Rule for Possession" cases filed in the last 7 days,
dedup against the existing `evictions` tab by case_no, geocode addresses via Nominatim,
and append new rows.

CAPTCHA / WAF block pages cause a soft-skip for that court only.

Invoke: python -m scripts.scrape_evictions
"""
from __future__ import annotations
import datetime as _dt
import json
import os
import sys
import time
import gspread
import requests
from google.oauth2.service_account import Credentials

from scripts.lib.court_scraper import looks_like_captcha, parse_odyssey_results
from scripts.lib.geocoder import geocode
from scripts.lib.streetview import DEFAULT_USER_AGENTS  # reuse rotation list

SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
TAB_NAME = 'evictions'
TAB_HEADER = ["case_no", "court", "lat", "lng", "address", "filed_date", "plaintiff", "defendant_redacted", "status", "first_seen"]

COURTS = [
    {"name": "FCC", "url": os.environ.get("COURT_FCC_URL", "")},
    {"name": "SCC", "url": os.environ.get("COURT_SCC_URL", "")},
]

THROTTLE_S = float(os.environ.get("EVICTION_MIN_INTERVAL_S", "8.0"))
DAYS_BACK = int(os.environ.get("EVICTION_DAYS_BACK", "7"))


def _open_book():
    creds = json.loads(os.environ["GOOGLE_CREDENTIALS"])
    scopes = ['https://www.googleapis.com/auth/spreadsheets']
    return gspread.authorize(Credentials.from_service_account_info(creds, scopes=scopes)).open_by_key(SPREADSHEET_ID)


def _get_or_create_tab(book):
    try:
        ws = book.worksheet(TAB_NAME)
    except gspread.exceptions.WorksheetNotFound:
        ws = book.add_worksheet(title=TAB_NAME, rows=2000, cols=len(TAB_HEADER))
        ws.update([TAB_HEADER], "A1")
    return ws


def _existing_case_nos(ws) -> set[str]:
    rows = ws.get_all_values()
    if not rows:
        return set()
    header = rows[0]
    try:
        idx = header.index("case_no")
    except ValueError:
        return set()
    return {r[idx] for r in rows[1:] if len(r) > idx and r[idx]}


def _scrape_court(court: dict, days_back: int) -> list[dict]:
    if not court["url"]:
        print(f"{court['name']}: no URL configured; skipping.", file=sys.stderr)
        return []
    end = _dt.date.today()
    start = end - _dt.timedelta(days=days_back)
    headers = {
        "User-Agent": DEFAULT_USER_AGENTS[0],
        "Accept": "text/html,application/xhtml+xml",
    }
    # The actual Odyssey query string is portal-specific. Override via subclass or
    # update this section once we have the live portal URL pattern.
    params = {
        "FromDate": start.strftime("%m/%d/%Y"),
        "ToDate": end.strftime("%m/%d/%Y"),
        "CaseType": "Rule for Possession",
    }
    try:
        r = requests.get(court["url"], params=params, headers=headers, timeout=30)
        r.raise_for_status()
    except requests.RequestException as e:
        print(f"{court['name']}: HTTP error: {e}", file=sys.stderr)
        return []
    if looks_like_captcha(r.text):
        print(f"{court['name']}: CAPTCHA / WAF block detected. Soft-skipping.", file=sys.stderr)
        return []
    rows = parse_odyssey_results(r.text, court=court["name"])
    print(f"{court['name']}: parsed {len(rows)} eviction rows.")
    return rows


def main() -> int:
    if not any(c["url"] for c in COURTS):
        print("Neither COURT_FCC_URL nor COURT_SCC_URL is set; skipping.", file=sys.stderr)
        return 0

    book = _open_book()
    ws = _get_or_create_tab(book)
    seen = _existing_case_nos(ws)
    new_rows: list[dict] = []
    for c in COURTS:
        for r in _scrape_court(c, DAYS_BACK):
            if r["case_no"] in seen:
                continue
            seen.add(r["case_no"])
            new_rows.append(r)
        time.sleep(THROTTLE_S)

    if not new_rows:
        print("No new eviction filings."); return 0

    print(f"Geocoding {len(new_rows)} addresses...")
    appended: list[list[str]] = []
    now_iso = _dt.datetime.now(_dt.timezone.utc).isoformat(timespec='seconds')
    for r in new_rows:
        loc = geocode(r["address"])
        if loc is None:
            continue
        appended.append([
            r["case_no"], r["court"], loc["lat"], loc["lng"], r["address"],
            r["filed_date"], r["plaintiff"], r["defendant_redacted"], "", now_iso,
        ])

    if appended:
        ws.append_rows(appended, value_input_option="RAW")
        print(f"Appended {len(appended)} eviction rows.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

**Step 2: Smoke test**

```bash
python -c "import scripts.scrape_evictions; print('OK')"
# Run it without env to confirm it soft-skips:
python -m scripts.scrape_evictions
```

Expected: prints `Neither COURT_FCC_URL nor COURT_SCC_URL is set; skipping.` exit 0.

**Step 3: Commit**

```bash
git add scripts/scrape_evictions.py
git commit -m "Add eviction scraper orchestrator (FCC + SCC)"
```

---

# Part E — UI

## Task E1: Wire new fields through `fetchSectorData`

**Files:** Modify `index.html`.

In the proxy branch of `fetchSectorData`, extend the `prop` object with:

```javascript
demolitionStatus:    row.demolition_status || null,
demolitionDate:      row.demolition_date || null,
permitCount365d:     parseIntOrNull(row.permit_count_365d),
permitTypesRecent:   row.permit_types_recent || null,
evictionCount365d:   parseIntOrNull(row.eviction_count_365d),
lastEvictionDate:    row.last_eviction_date || null,
```

Mirror the same defaults (`null`) in the Socrata-fallback branch.

Commit:

```bash
git add index.html
git commit -m "Parse demolition/permit/eviction columns into masterData"
```

---

## Task E2: New filter chips in the drawer

**Files:** Modify `index.html`.

Add to the `#filter-drawer` markup (after the existing Cluster section):

```html
<div class="filter-section">
    <h4>Demolition</h4>
    <div class="chip-group" id="filter-demo">
        <span class="chip active" data-demo="all">All</span>
        <span class="chip" data-demo="pending">Pending</span>
        <span class="chip" data-demo="permitted">Permitted</span>
        <span class="chip" data-demo="completed">Completed</span>
        <span class="chip" data-demo="none">None</span>
    </div>
</div>
<div class="filter-section">
    <h4>Eviction</h4>
    <div class="chip-group" id="filter-evict">
        <span class="chip active" data-evict="all">All</span>
        <span class="chip" data-evict="recent">Recent (≤365d)</span>
        <span class="chip" data-evict="none">None</span>
    </div>
</div>
<div class="filter-section">
    <h4>Recent permits (min)</h4>
    <input type="range" id="filter-permits" min="0" max="10" step="1" value="0">
    <small id="filter-permits-label">≥ 0 permits</small>
</div>
```

Extend `filterState`:

```javascript
demoStatus: 'all',     // 'all' | 'pending' | 'permitted' | 'completed' | 'none'
evictRecency: 'all',   // 'all' | 'recent' | 'none'
permitsMin: 0,
```

Update the chip-group click delegate (to handle new groups):

```javascript
if (group.id === 'filter-demo') filterState.demoStatus = t.dataset.demo;
if (group.id === 'filter-evict') filterState.evictRecency = t.dataset.evict;
```

Add range listener:

```javascript
document.getElementById('filter-permits').addEventListener('input', e => {
    filterState.permitsMin = parseInt(e.target.value, 10);
    document.getElementById('filter-permits-label').textContent = `≥ ${filterState.permitsMin} permits`;
    applyFilters();
});
```

Add to `applyFilters()` (just before the marker push):

```javascript
// demolition
if (filterState.demoStatus !== 'all') {
    const ds = p.demolitionStatus || 'none';
    if (ds !== filterState.demoStatus) return;
}
// eviction recency
if (filterState.evictRecency !== 'all') {
    const c = p.evictionCount365d || 0;
    if (filterState.evictRecency === 'recent' && c === 0) return;
    if (filterState.evictRecency === 'none' && c > 0) return;
}
// recent permits
if ((p.permitCount365d || 0) < filterState.permitsMin) return;
```

Update `syncDrawerControlsFromState`:

```javascript
update('#filter-demo', 'demo', filterState.demoStatus);
update('#filter-evict', 'evict', filterState.evictRecency);
document.getElementById('filter-permits').value = filterState.permitsMin;
document.getElementById('filter-permits-label').textContent = `≥ ${filterState.permitsMin} permits`;
```

Commit:

```bash
git add index.html
git commit -m "Add demolition/eviction/permits filter chips"
```

---

## Task E3: Map layers for `demolitions` and `evictions` tabs

**Files:** Modify `index.html`. Modify `google_apps_script.js` (proxy must serve the new tabs).

### Proxy update

The current proxy presumably serves the main tab via `?` or default. Extend it to accept `?tab=demolitions` and `?tab=evictions`.

Edit `google_apps_script.js`:
- Inspect existing structure first.
- Add a switch on the request `tab` parameter that returns the matching worksheet's rows.
- Document the contract: `?tab=demolitions` → array of row objects; `?tab=evictions` → array of row objects; default → main blight rows (unchanged).

(Exact diff depends on current proxy code — read it before editing.)

### Frontend layers

Add to `index.html` near the existing layer-group declarations:

```javascript
const demolitionsLayer = L.layerGroup();
const evictionsLayer = L.layerGroup();
```

Register them in the `L.control.layers` overlays:

```javascript
"🏗️ Demolitions": demolitionsLayer,
"📜 Evictions": evictionsLayer,
```

Add lazy-fetch on first toggle (so we don't pay the proxy cost unless the user wants it):

```javascript
let _demoLoaded = false, _evictLoaded = false;
map.on('overlayadd', async (e) => {
    if (e.layer === demolitionsLayer && !_demoLoaded) { _demoLoaded = true; await loadDemolitions(); }
    if (e.layer === evictionsLayer && !_evictLoaded) { _evictLoaded = true; await loadEvictions(); }
});

async function loadDemolitions() {
    if (PROXY_URL === 'PLACEHOLDER_PROXY_URL') return;
    const r = await fetch(`${PROXY_URL}?tab=demolitions`);
    const rows = await r.json();
    rows.forEach(d => {
        const lat = parseFloat(d.lat), lng = parseFloat(d.lng);
        if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
        const m = L.marker([lat, lng], { icon: makePinIcon('🏗️', 'demo') });
        m.bindPopup(`<div class="popup-content"><h3>🏗️ Demolition</h3><p><b>Status:</b> ${d.status}</p><p><b>Date:</b> ${d.event_date}</p><p><b>Address:</b> ${d.address}</p></div>`);
        demolitionsLayer.addLayer(m);
    });
}

async function loadEvictions() {
    if (PROXY_URL === 'PLACEHOLDER_PROXY_URL') return;
    const r = await fetch(`${PROXY_URL}?tab=evictions`);
    const rows = await r.json();
    rows.forEach(e => {
        const lat = parseFloat(e.lat), lng = parseFloat(e.lng);
        if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
        const m = L.marker([lat, lng], { icon: makePinIcon('📜', 'evict') });
        m.bindPopup(`<div class="popup-content"><h3>📜 Eviction filing</h3><p><b>Court:</b> ${e.court}</p><p><b>Filed:</b> ${e.filed_date}</p><p><b>Plaintiff:</b> ${e.plaintiff}</p><p><b>Address:</b> ${e.address}</p></div>`);
        evictionsLayer.addLayer(m);
    });
}
```

Add CSS for the new icon variants:

```css
.user-pin-icon.demo { border-color: #ff44aa; }
.user-pin-icon.evict { border-color: #ffaa00; }
```

Commit:

```bash
git add index.html google_apps_script.js
git commit -m "Add demolition + eviction map layers (lazy-loaded)"
```

---

## Task E4: Property-card additions

**Files:** Modify `index.html`.

In `renderPropCard`, add:

```javascript
const demoText = p.demolitionStatus ? `${p.demolitionStatus}${p.demolitionDate ? ` (${p.demolitionDate})` : ''}` : '';
const permitsText = p.permitCount365d != null ? `${p.permitCount365d} in last year${p.permitTypesRecent ? ` — ${p.permitTypesRecent}` : ''}` : '';
const evictText = p.evictionCount365d != null && p.evictionCount365d > 0 ? `${p.evictionCount365d} filed${p.lastEvictionDate ? ` (last: ${p.lastEvictionDate})` : ''}` : '';
```

Insert these `fmtField` calls after the existing rows in the `<dl>`:

```javascript
${fmtField('Demolition', demoText)}
${fmtField('Recent permits', permitsText)}
${fmtField('Evictions', evictText)}
```

Commit:

```bash
git add index.html
git commit -m "Show demolition/permit/eviction fields on property cards"
```

---

# Part F — GH Actions integration

## Task F1: New steps in workflow

**Files:** Modify `.github/workflows/update_database.yml`.

Add three new steps between `Enrich properties` and `Classify graffiti`:

```yaml
      - name: Ingest demolitions (best-effort)
        if: success()
        env:
          GOOGLE_CREDENTIALS: ${{ secrets.SHEETS_API }}
        run: python -m scripts.ingest_demolitions || echo "demolition ingest failed; continuing"

      - name: Ingest permits (best-effort)
        if: success()
        env:
          GOOGLE_CREDENTIALS: ${{ secrets.SHEETS_API }}
          PERMITS_MIN_INTERVAL_S: '0.5'
          PERMITS_MAX_PER_RUN: '100'
        run: python -m scripts.ingest_permits || echo "permits ingest failed; continuing"

      - name: Scrape evictions (best-effort)
        if: success()
        env:
          GOOGLE_CREDENTIALS: ${{ secrets.SHEETS_API }}
          COURT_FCC_URL: ${{ secrets.COURT_FCC_URL }}
          COURT_SCC_URL: ${{ secrets.COURT_SCC_URL }}
          EVICTION_MIN_INTERVAL_S: '8.0'
          EVICTION_DAYS_BACK: '7'
        run: python -m scripts.scrape_evictions || echo "eviction scrape failed; continuing"
```

Validate, commit:

```bash
python -c "import yaml; yaml.safe_load(open('.github/workflows/update_database.yml'))"
git add .github/workflows/update_database.yml
git commit -m "Wire demolitions/permits/evictions ingest into workflow"
```

---

# Part G — Documentation

## Task G1: Operator setup doc

**Files:** Create `docs/demolitions-permits-evictions.md`.

Capture the new operator setup:

1. The two new Actions secrets the user must add: `COURT_FCC_URL`, `COURT_SCC_URL` — the live URLs of each court's Odyssey case-search portal.
2. Note that without those secrets, eviction scraping soft-skips and the rest of the pipeline keeps working.
3. Note the brittle nature: if the eviction column goes stale for >2 weeks, check `scripts/lib/court_scraper.py::parse_odyssey_results` selectors against the live HTML.
4. Source dataset table.

Commit:

```bash
git add docs/demolitions-permits-evictions.md
git commit -m "Document demolition/permit/eviction setup and operations"
```

## Task G2: README pointer

Update Section 5 of `README.md` to mention the new data streams in the bullet list. Commit:

```bash
git add README.md
git commit -m "Reference new data streams in README"
```

---

# Part H — Final verification

## Task H1: Test suite + import smoke

```bash
python -m pytest -v
python -c "import scripts.ingest_demolitions, scripts.ingest_permits, scripts.scrape_evictions, scripts.lib.demolitions, scripts.lib.permits, scripts.lib.court_scraper, scripts.lib.geocoder, scripts.lib.address; print('OK')"
```

Expected: previous 28 + 8 (address) + 4 (geocoder) + 3 (demolitions) + 3 (permits) + 5 (court_scraper) = 51 passed; imports OK.

## Task H2: Push

```bash
git push origin claude/vibrant-hawking-dd3434
```

---

# Notes for the implementer

- The eviction scraper's `_scrape_court` uses placeholder query parameters (`FromDate`, `ToDate`, `CaseType`). The actual Odyssey portal at each court uses different param names. Once the user provides the live URL, you'll need to inspect the portal's case-search form and update the params + the table-class selector in `parse_odyssey_results`.
- Both court URLs are stored as Actions secrets, not hardcoded — keeps the implementation reusable across court portal changes.
- The `evictions` tab is appended-only, never wholesale-rewritten. The `demolitions` tab is wholesale-rewritten each run (it's small and changes infrequently).
- `ingest_permits.py` sends one Socrata query per blight property — slow but the cap (`PERMITS_MAX_PER_RUN=100`) keeps any single run bounded.
- The proxy update in Task E3 requires reading `google_apps_script.js` first to understand its current structure. The plan documents the contract; the diff depends on what's already there.
