"""Direct-HTTP scraping of Google Street View panorama metadata and tiles.

Bypasses the Static Street View API. Endpoints are unofficial; expect breakage.
"""
from __future__ import annotations
import json
import re
import requests

SINGLE_IMAGE_SEARCH_URL = "https://maps.googleapis.com/maps/api/js/GeoPhotoService.SingleImageSearch"

class PanoramaNotFound(Exception):
    """No Street View panorama exists near the given lat/lng."""

def _strip_xss_prefix(body: str) -> str:
    return re.sub(r"^\)\]\}'\s*", "", body, count=1)

def lookup_panoid(lat: float, lng: float, *, radius_m: int = 50, session: requests.Session | None = None, timeout: float = 10.0) -> str:
    """Return the nearest Street View panoid for (lat, lng), or raise PanoramaNotFound."""
    pb = (
        f"!1m5!1sapiv3!5sUS!11m2!1m1!1b0!2m4!1m2!3d{lat}!4d{lng}!2d{radius_m}"
        "!3m10!2m2!1sen!2sUS!9m1!1e2!11m4!1m3!1e2!2b1!3e2"
        "!4m10!1e1!1e2!1e3!1e4!1e8!1e6!5m1!1e0!6m1!1e1"
    )
    s = session or requests
    r = s.get(SINGLE_IMAGE_SEARCH_URL, params={"pb": pb}, timeout=timeout)
    r.raise_for_status()
    data = json.loads(_strip_xss_prefix(r.text))
    try:
        return data[1][0][0][1]
    except (TypeError, IndexError, KeyError):
        raise PanoramaNotFound(f"No panorama near ({lat}, {lng})")
