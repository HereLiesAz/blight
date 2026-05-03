"""Direct-HTTP scraping of Google Street View panorama metadata and tiles.

Bypasses the Static Street View API. Endpoints are unofficial; expect breakage.
"""
from __future__ import annotations
import json
import random
import re
import time
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

TILE_URL = "https://streetviewpixels-pa.googleapis.com/v1/tile"

def fetch_tile(panoid: str, *, x: int = 0, y: int = 0, zoom: int = 0,
               session: requests.Session | None = None, timeout: float = 10.0) -> bytes:
    """Fetch a single Street View panorama tile as JPEG bytes."""
    s = session or requests
    r = s.get(TILE_URL,
              params={"cb_client": "maps_sv.tactile", "panoid": panoid,
                      "x": x, "y": y, "zoom": zoom, "nbt": 1, "fover": 2},
              timeout=timeout)
    r.raise_for_status()
    return r.content

DEFAULT_USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
]

class ScraperSession:
    """Throttled, retrying HTTP session for Street View scraping."""
    def __init__(self, *, min_interval_s: float = 3.0, max_retries: int = 3,
                 backoff_base: float = 1.5, user_agents: list[str] | None = None):
        self.min_interval_s = min_interval_s
        self.max_retries = max_retries
        self.backoff_base = backoff_base
        self.user_agents = user_agents or DEFAULT_USER_AGENTS
        self._session = requests.Session()
        self._last_request_at = 0.0

    def _throttle(self):
        elapsed = time.time() - self._last_request_at
        if elapsed < self.min_interval_s:
            time.sleep(self.min_interval_s - elapsed)

    def _get(self, url: str, params: dict, timeout: float = 10.0) -> requests.Response:
        for attempt in range(self.max_retries + 1):
            self._throttle()
            self._session.headers["User-Agent"] = random.choice(self.user_agents)
            r = self._session.get(url, params=params, timeout=timeout)
            self._last_request_at = time.time()
            if r.status_code == 200:
                return r
            if r.status_code in (429, 503) and attempt < self.max_retries:
                time.sleep(self.backoff_base * (2 ** attempt) + random.uniform(0, 0.5))
                continue
            r.raise_for_status()
        raise RuntimeError(f"Exhausted retries for {url}")

    def lookup_panoid(self, lat: float, lng: float, *, radius_m: int = 50) -> str:
        pb = (f"!1m5!1sapiv3!5sUS!11m2!1m1!1b0!2m4!1m2!3d{lat}!4d{lng}!2d{radius_m}"
              "!3m10!2m2!1sen!2sUS!9m1!1e2!11m4!1m3!1e2!2b1!3e2"
              "!4m10!1e1!1e2!1e3!1e4!1e8!1e6!5m1!1e0!6m1!1e1")
        r = self._get(SINGLE_IMAGE_SEARCH_URL, {"pb": pb})
        data = json.loads(_strip_xss_prefix(r.text))
        try:
            return data[1][0][0][1]
        except (TypeError, IndexError, KeyError):
            raise PanoramaNotFound(f"No panorama near ({lat}, {lng})")

    def fetch_tile(self, panoid: str, *, x: int = 0, y: int = 0, zoom: int = 0) -> bytes:
        r = self._get(TILE_URL, {"cb_client": "maps_sv.tactile", "panoid": panoid,
                                 "x": x, "y": y, "zoom": zoom, "nbt": 1, "fover": 2})
        return r.content
