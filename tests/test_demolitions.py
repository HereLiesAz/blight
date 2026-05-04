import pathlib
import responses
from scripts.lib.demolitions import (
    fetch_completed_demolitions, fetch_demolition_permits, status_for_geopin,
)

FX = pathlib.Path('tests/fixtures')


@responses.activate
def test_fetch_completed_demolitions_yields_normalized_rows():
    body = (FX / 'demolitions_completed.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/e3wd-h7q2.json",
                  body=body, status=200, content_type='application/json')
    rows = fetch_completed_demolitions()
    assert len(rows) == 1
    r = rows[0]
    assert r['status'] == 'completed'
    assert r['geopin'] == '41126231'
    assert r['lat'] is not None
    assert r['source'] == 'e3wd-h7q2'
    assert r['event_date'] == '2024-06-15'


@responses.activate
def test_fetch_demolition_permits_classifies_by_status():
    body = (FX / 'demolitions_permits.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/aib5-en5t.json",
                  body=body, status=200, content_type='application/json')
    rows = fetch_demolition_permits()
    assert len(rows) == 1
    r = rows[0]
    assert r['status'] == 'permitted'
    assert r['source'] == 'aib5-en5t'
    assert r['permit_no'] == '21-25976'


@responses.activate
def test_status_for_geopin_picks_most_advanced_lifecycle_event():
    completed = (FX / 'demolitions_completed.json').read_text()
    permits = (FX / 'demolitions_permits.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/e3wd-h7q2.json",
                  body=completed, status=200, content_type='application/json')
    responses.add(responses.GET, "https://data.nola.gov/resource/aib5-en5t.json",
                  body=permits, status=200, content_type='application/json')
    s = status_for_geopin("41126231")
    assert s['status'] == 'completed'
    assert s['date'] == '2024-06-15'


@responses.activate
def test_status_for_geopin_returns_none_when_no_matches():
    responses.add(responses.GET, "https://data.nola.gov/resource/e3wd-h7q2.json",
                  json=[], status=200)
    responses.add(responses.GET, "https://data.nola.gov/resource/aib5-en5t.json",
                  json=[], status=200)
    assert status_for_geopin("99999") is None
