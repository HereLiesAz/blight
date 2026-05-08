"""Enrichment lookups against NOLA Socrata cross-datasets.

All endpoints are public; throttling is the caller's responsibility.
"""
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


def _escape_soql(s: str) -> str:
    """Escape single quotes for SoQL by doubling them."""
    return s.replace("'", "''")


def fetch_zoning(geopin: str) -> dict:
    geopin = _escape_soql(geopin)
    rows = _socrata_get("cym7-cw5z", {"$where": f"geopin='{geopin}'", "$limit": "1"})
    if not rows:
        return {"zoning_class": "", "zoning_desc": ""}
    return {
        "zoning_class": rows[0].get("zoningclassification", ""),
        "zoning_desc": rows[0].get("zoningdescription", ""),
    }


def fetch_footprint(geopin: str) -> bool:
    geopin = _escape_soql(geopin)
    rows = _socrata_get(
        "prh5-qsuf",
        {"$where": f"geopin='{geopin}' AND activestatus=1", "$limit": "1"},
    )
    return bool(rows)


def fetch_case_history(geopin: str) -> dict:
    geopin = _escape_soql(geopin)
    rows = _socrata_get(
        "gjzc-adg8",
        {
            "$where": f"geopin='{geopin}'",
            "$select": "initinspection",
            "$order": "initinspection ASC",
            "$limit": "100",
        },
    )
    if not rows:
        return {"case_count": 0, "earliest_case_date": ""}
    return {
        "case_count": len(rows),
        "earliest_case_date": _normalize_date(rows[0].get("initinspection", "")),
    }


def fetch_last_grass_cut(address: str) -> str:
    address = _escape_soql(address)
    rows = _socrata_get(
        "xhih-vxs6",
        {
            "$where": f"address='{address}'",
            "$select": "casefiled",
            "$order": "casefiled DESC",
            "$limit": "1",
        },
    )
    if not rows:
        return ""
    return rows[0].get("casefiled", "")[:10]


def fetch_land_use(lat: float, lng: float) -> str:
    rows = _socrata_get(
        "itxd-2247",
        {
            "$where": f"intersects(the_geom, 'POINT({lng} {lat})')",
            "$select": "flu_desc",
            "$limit": "1",
        },
    )
    if not rows:
        return ""
    return rows[0].get("flu_desc", "")


def days_under_blight(earliest_iso: str, *, now: _dt.datetime | None = None) -> int:
    if not earliest_iso:
        return 0
    n = now or _dt.datetime.now(_dt.timezone.utc)
    try:
        d = _dt.datetime.fromisoformat(earliest_iso.replace("Z", "+00:00"))
        if d.tzinfo is None:
            d = d.replace(tzinfo=_dt.timezone.utc)
    except ValueError:
        return 0
    return max(0, (n - d).days)


def _normalize_date(s: str) -> str:
    """Normalize Socrata date formats to ISO 8601."""
    if not s:
        return ""
    if "T" in s:
        return s
    # 20170411000000.000 -> 2017-04-11T00:00:00
    if len(s) >= 8 and s[:8].isdigit():
        return f"{s[:4]}-{s[4:6]}-{s[6:8]}T00:00:00"
    return s
