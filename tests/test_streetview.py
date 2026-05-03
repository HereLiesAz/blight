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
