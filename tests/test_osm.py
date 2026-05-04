import json
import pathlib
import responses
from scripts.lib.osm import (
    build_query, categorize, fetch_features, parse_response, OVERPASS_URL,
)

FX = pathlib.Path('tests/fixtures')


def test_build_query_includes_bbox_and_tags():
    q = build_query("1,2,3,4")
    assert "(1,2,3,4)" in q
    assert '"abandoned"="yes"' in q
    assert '"building"="ruins"' in q
    assert '"landuse"="brownfield"' in q
    assert "out center tags" in q


def test_categorize():
    assert categorize({"abandoned": "yes"}) == "abandoned"
    assert categorize({"ruins": "yes"}) == "ruins"
    assert categorize({"building": "ruins"}) == "ruins"
    assert categorize({"disused": "yes"}) == "disused"
    assert categorize({"landuse": "brownfield"}) == "brownfield"
    assert categorize({"unknown": "tag"}) == "other"


def test_parse_response_handles_nodes_and_ways():
    data = json.loads((FX / 'overpass_sample.json').read_text())
    rows = parse_response(data)
    # Skip the way with no center (id 22222) -> 3 of 4 elements
    assert len(rows) == 3
    abandoned = [r for r in rows if r["category"] == "abandoned"][0]
    assert abandoned["lat"] == 29.964
    assert abandoned["name"] == "Old Mill"
    assert abandoned["id"] == "node:12345"
    ruins = [r for r in rows if r["id"] == "way:67890"][0]
    assert ruins["category"] == "ruins"
    assert ruins["lat"] == 29.97


def test_parse_response_handles_empty_elements():
    assert parse_response({"elements": []}) == []
    assert parse_response({}) == []


@responses.activate
def test_fetch_features_posts_query_and_parses():
    body = (FX / 'overpass_sample.json').read_text()
    responses.add(responses.POST, OVERPASS_URL, body=body, status=200, content_type='application/json')
    rows = fetch_features()
    assert len(rows) == 3
