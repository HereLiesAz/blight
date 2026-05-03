"""Enrich each blight property with cross-dataset NOLA data.

Runs after update_database.py, before classify_graffiti.py.
Idempotent: skips rows already enriched (zoning + land use both blank => needs enrichment).

Required env: GOOGLE_CREDENTIALS.
Invoke: python -m scripts.enrich_properties (from repo root).
"""
from __future__ import annotations
import json
import os
import sys
import time
import gspread
from google.oauth2.service_account import Credentials

from scripts.lib.enrichment import (
    days_under_blight, fetch_case_history, fetch_footprint,
    fetch_land_use, fetch_last_grass_cut, fetch_zoning,
)
from scripts.lib.sheet import (
    ENRICHMENT_COLUMNS, ensure_enrichment_columns, row_needs_enrichment,
)

SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
MIN_INTERVAL_S = float(os.environ.get("ENRICH_MIN_INTERVAL_S", "1.0"))
MAX_PER_RUN = int(os.environ.get("ENRICH_MAX_PER_RUN", "300"))


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
        if not row_needs_enrichment(rd):
            continue
        if processed >= MAX_PER_RUN:
            print(f"Hit MAX_PER_RUN={MAX_PER_RUN}; stopping."); break

        geopin = (rd.get("geopin") or "").strip()
        addr = (rd.get("Address") or "").strip()
        try:
            lat = float(rd.get("Latitude") or 0)
            lng = float(rd.get("Longitude") or 0)
        except ValueError:
            lat, lng = 0.0, 0.0

        updates: dict[int, str] = {}
        try:
            if geopin:
                z = fetch_zoning(geopin)
                updates[col_idx["zoning_class"]] = z["zoning_class"]
                updates[col_idx["zoning_desc"]] = z["zoning_desc"]
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

        cells = [gspread.Cell(r_i, c + 1, v) for c, v in updates.items()]
        if cells:
            sheet.update_cells(cells)
            processed += 1
            print(f"row {r_i} {addr!r:40s} enriched ({len(updates)} fields)")
        time.sleep(MIN_INTERVAL_S)

    print(f"Done. processed={processed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
