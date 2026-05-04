"""Overpass API client for OSM-derived blight-adjacent features in NOLA.

Pulls abandoned buildings, ruins, brownfields, and related tags within the NOLA
bounding box. Returns normalized point records suitable for sheet append.
"""
from __future__ import annotations
import requests

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
NOLA_BBOX = "29.85,-90.15,30.10,-89.85"


def build_query(bbox: str = NOLA_BBOX, timeout_s: int = 60) -> str:
    """Build the Overpass QL query for blight-adjacent features."""
    b = f"({bbox})"
    return f"""
[out:json][timeout:{timeout_s}];
(
  node["abandoned"="yes"]{b};
  way["abandoned"="yes"]{b};
  node["ruins"="yes"]{b};
  way["ruins"="yes"]{b};
  way["building"="ruins"]{b};
  node["disused"="yes"]{b};
  way["disused"="yes"]{b};
  way["landuse"="brownfield"]{b};
);
out center tags;
""".strip()


def categorize(tags: dict) -> str:
    """Map a tags dict to a single high-level category."""
    if tags.get("abandoned") == "yes":
        return "abandoned"
    if tags.get("ruins") == "yes" or tags.get("building") == "ruins":
        return "ruins"
    if tags.get("disused") == "yes":
        return "disused"
    if tags.get("landuse") == "brownfield":
        return "brownfield"
    return "other"


def parse_response(data: dict) -> list[dict]:
    """Convert raw Overpass JSON response into normalized records.

    Skips elements that have no geographic anchor (no `lat`/`lon` and no `center`).
    """
    out = []
    for el in data.get("elements", []):
        tags = el.get("tags") or {}
        if "lat" in el and "lon" in el:
            lat, lng = float(el["lat"]), float(el["lon"])
        elif "center" in el and isinstance(el["center"], dict):
            lat, lng = float(el["center"]["lat"]), float(el["center"]["lon"])
        else:
            continue
        out.append({
            "id": f"{el.get('type', 'unk')}:{el.get('id', '')}",
            "osm_type": el.get("type", ""),
            "category": categorize(tags),
            "lat": lat,
            "lng": lng,
            "name": tags.get("name", ""),
            "tags_summary": ",".join(f"{k}={v}" for k, v in sorted(tags.items()) if k in {
                "abandoned", "ruins", "disused", "building", "landuse", "amenity", "name",
            }),
        })
    return out


def fetch_features(*, session: requests.Session | None = None, timeout: float = 90.0) -> list[dict]:
    """Run the Overpass query and return normalized records."""
    s = session or requests
    r = s.post(OVERPASS_URL, data={"data": build_query()}, timeout=timeout)
    r.raise_for_status()
    return parse_response(r.json())
