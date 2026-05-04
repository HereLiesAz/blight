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
