"""Per-blight-property permit history ingest. No city-wide tab (would be too large).

Required env: GOOGLE_CREDENTIALS.
Invoke: python -m scripts.ingest_permits
"""
from __future__ import annotations
import json
import os
import sys
import time
import gspread
from google.oauth2.service_account import Credentials

from scripts.lib.permits import fetch_permits_for_geopin, summarize_permits
from scripts.lib.sheet import ensure_demolition_columns

SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
MIN_INTERVAL_S = float(os.environ.get("PERMITS_MIN_INTERVAL_S", "0.5"))
MAX_PER_RUN = int(os.environ.get("PERMITS_MAX_PER_RUN", "100"))


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
            print(f"Hit MAX_PER_RUN={MAX_PER_RUN}; stopping.", file=sys.stderr); break
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

    print(f"Done. processed={processed}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
