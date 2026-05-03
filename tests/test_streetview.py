import pytest
import responses
from scripts.lib.streetview import lookup_panoid, PanoramaNotFound, SINGLE_IMAGE_SEARCH_URL

@responses.activate
def test_lookup_panoid_returns_panoid_id():
    body = open('tests/fixtures/photometa_response.txt').read()
    responses.add(responses.GET, SINGLE_IMAGE_SEARCH_URL, body=body, status=200)
    assert lookup_panoid(29.964, -90.007) == "TEST_PANOID_ABC123"

@responses.activate
def test_lookup_panoid_raises_when_no_pano():
    body = ")]}'\n[[\"apiv3\"],[]]"
    responses.add(responses.GET, SINGLE_IMAGE_SEARCH_URL, body=body, status=200)
    with pytest.raises(PanoramaNotFound):
        lookup_panoid(0.0, 0.0)

from scripts.lib.streetview import fetch_tile, TILE_URL

@responses.activate
def test_fetch_tile_returns_bytes():
    body = open('tests/fixtures/sample_tile.jpg', 'rb').read()
    responses.add(responses.GET, TILE_URL, body=body, status=200, content_type='image/jpeg')
    out = fetch_tile("PANO_X", x=0, y=0, zoom=0)
    assert out == body
    assert out[:3] == b'\xff\xd8\xff'  # JPEG magic

import time as _time
from scripts.lib.streetview import ScraperSession

@responses.activate
def test_scraper_retries_on_429(monkeypatch):
    sleeps = []
    monkeypatch.setattr(_time, 'sleep', lambda s: sleeps.append(s))
    responses.add(responses.GET, TILE_URL, status=429)
    responses.add(responses.GET, TILE_URL, status=429)
    responses.add(responses.GET, TILE_URL, body=b'\xff\xd8\xff\xe0', status=200)
    sess = ScraperSession(min_interval_s=0, max_retries=3, backoff_base=0.0)
    out = sess.fetch_tile("PANO_X")
    assert out.startswith(b'\xff\xd8\xff')
    assert len(sleeps) >= 2  # at least one back-off sleep per retry

@responses.activate
def test_scraper_throttles_between_requests(monkeypatch):
    sleeps = []
    monkeypatch.setattr(_time, 'sleep', lambda s: sleeps.append(s))
    responses.add(responses.GET, TILE_URL, body=b'\xff\xd8\xff', status=200)
    responses.add(responses.GET, TILE_URL, body=b'\xff\xd8\xff', status=200)
    sess = ScraperSession(min_interval_s=3.0, max_retries=0, backoff_base=0.0)
    sess.fetch_tile("A"); sess.fetch_tile("B")
    assert any(s >= 2.5 for s in sleeps), f"expected a throttle sleep ~3s, got {sleeps}"
