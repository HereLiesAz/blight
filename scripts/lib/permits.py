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
