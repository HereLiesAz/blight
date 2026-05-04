"""Per-property + city-wide demolition lifecycle ingest.

Updates main blight tab with demolition_status / demolition_date.
Replaces the contents of the `demolitions` worksheet with the full city-wide list.

Required env: GOOGLE_CREDENTIALS.
Invoke: python -m scripts.ingest_demolitions
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
from scripts.lib.sheet import ensure_demolition_columns

SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
DEMO_TAB_HEADER = ["id", "address", "geopin", "lat", "lng", "status", "event_date", "source", "permit_no"]


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

    print("Fetching demolitions...", file=sys.stderr)
    completed = fetch_completed_demolitions()
    permits = fetch_demolition_permits()
    all_rows = completed + permits
    print(f"  completed={len(completed)} permits={len(permits)} total={len(all_rows)}", file=sys.stderr)

    by_geopin: dict[str, dict] = {}
    for r in all_rows:
        g = r["geopin"]
        if not g:
            continue
        cur = by_geopin.get(g)
        if cur is None or _LIFECYCLE_RANK[r["status"]] > _LIFECYCLE_RANK[cur["status"]]:
            by_geopin[g] = r

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
            if rd.get("demolition_status", "") == entry["status"] and rd.get("demolition_date", "") == entry["event_date"]:
                continue
            cells.append(gspread.Cell(r_i, col_idx["demolition_status"] + 1, entry["status"]))
            cells.append(gspread.Cell(r_i, col_idx["demolition_date"] + 1, entry["event_date"]))
        if cells:
            main_sheet.update_cells(cells)
            print(f"Updated {len(cells)//2} blight rows with demolition status.", file=sys.stderr)

    demo_ws = _get_or_create_tab(book, "demolitions", DEMO_TAB_HEADER)
    payload = [DEMO_TAB_HEADER] + [
        [r["id"], r["address"], r["geopin"], r["lat"] or "", r["lng"] or "",
         r["status"], r["event_date"], r["source"], r["permit_no"]]
        for r in all_rows
    ]
    demo_ws.clear()
    demo_ws.update(payload, "A1")
    print(f"Wrote {len(all_rows)} rows to `demolitions` tab.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
