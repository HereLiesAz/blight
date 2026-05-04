import datetime as dt
import json
import pathlib
import responses
from scripts.lib.permits import fetch_permits_for_geopin, summarize_permits

FX = pathlib.Path('tests/fixtures')


@responses.activate
def test_fetch_permits_for_geopin():
    body = (FX / 'permits_by_geopin.json').read_text()
    responses.add(responses.GET, "https://data.nola.gov/resource/nbcf-m6c2.json",
                  body=body, status=200, content_type='application/json')
    rows = fetch_permits_for_geopin("41126231")
    assert len(rows) == 4


def test_fetch_permits_for_geopin_returns_empty_for_blank_geopin():
    assert fetch_permits_for_geopin("") == []


def test_summarize_permits_counts_within_365_days():
    rows = json.loads((FX / 'permits_by_geopin.json').read_text())
    now = dt.datetime(2026, 5, 4, tzinfo=dt.timezone.utc)
    s = summarize_permits(rows, now=now)
    # 25-100 (2025-12), 25-080 (2025-08) within 365d; 24-300 (2024-11) is ~530d, OUT
    assert s['count_365d'] == 2
    assert s['types_recent'] == "Renovation,Mechanical HVAC,Electrical,Renovation"


def test_summarize_permits_handles_empty():
    assert summarize_permits([]) == {"count_365d": 0, "types_recent": ""}


def test_summarize_permits_handles_malformed_dates():
    rows = [{"applicationnumber": "x", "permittype": "Bad", "applicationdate": "not-a-date"}]
    s = summarize_permits(rows, now=dt.datetime(2026, 5, 4, tzinfo=dt.timezone.utc))
    assert s['count_365d'] == 0
    assert s['types_recent'] == "Bad"
