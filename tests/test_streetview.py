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
