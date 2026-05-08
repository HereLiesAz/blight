import datetime as dt
import json
import pathlib
import re

import responses

from scripts.lib.enrichment import (
    _normalize_date,
    days_under_blight,
    fetch_case_history,
    fetch_footprint,
    fetch_land_use,
    fetch_last_grass_cut,
    fetch_zoning,
)

FX = pathlib.Path('tests/fixtures')


def _load(name):
    return json.loads((FX / name).read_text())


@responses.activate
def test_fetch_zoning_returns_class_and_desc():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/cym7-cw5z.json",
        json=_load('zoning_lookup.json'),
        status=200,
    )
    z = fetch_zoning("41126231")
    assert z['zoning_class'] == "HMR-3"
    assert "Historic" in z['zoning_desc']


@responses.activate
def test_fetch_zoning_returns_blank_on_no_match():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/cym7-cw5z.json",
        json=[],
        status=200,
    )
    z = fetch_zoning("99999999")
    assert z == {"zoning_class": "", "zoning_desc": ""}


@responses.activate
def test_fetch_footprint_present_returns_true():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/prh5-qsuf.json",
        json=_load('footprint_present.json'),
        status=200,
    )
    assert fetch_footprint("41126231") is True


@responses.activate
def test_fetch_footprint_empty_returns_false():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/prh5-qsuf.json",
        json=_load('footprint_empty.json'),
        status=200,
    )
    assert fetch_footprint("99999999") is False


@responses.activate
def test_fetch_case_history_returns_count_and_earliest_date():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/gjzc-adg8.json",
        json=_load('case_history.json'),
        status=200,
    )
    h = fetch_case_history("41126231")
    assert h['case_count'] == 2
    assert h['earliest_case_date'].startswith("2017-04-11")


def test_days_under_blight_computes_against_now():
    earliest = dt.datetime(2020, 1, 1, tzinfo=dt.timezone.utc).isoformat()
    d = days_under_blight(earliest, now=dt.datetime(2026, 5, 3, tzinfo=dt.timezone.utc))
    # 2020-01-01 to 2026-05-03 ~= 2314 days
    assert 2300 < d < 2350


def test_days_under_blight_returns_zero_for_blank():
    assert days_under_blight("") == 0
    assert days_under_blight(None) == 0  # type: ignore[arg-type]


def test_days_under_blight_returns_zero_for_malformed_string():
    assert days_under_blight("not-a-date") == 0


@responses.activate
def test_fetch_last_grass_cut_returns_iso_date():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/xhih-vxs6.json",
        json=_load('grass_cutting.json'),
        status=200,
    )
    g = fetch_last_grass_cut("2061 N Tonti St")
    assert g == "2021-01-26"


@responses.activate
def test_fetch_last_grass_cut_returns_blank_on_no_match():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/xhih-vxs6.json",
        json=[],
        status=200,
    )
    assert fetch_last_grass_cut("nowhere") == ""


@responses.activate
def test_fetch_land_use_returns_flu_desc():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/itxd-2247.json",
        json=_load('land_use.json'),
        status=200,
    )
    assert fetch_land_use(29.96, -90.01) == "Residential Single-Family"


def test_normalize_date():
    assert _normalize_date("") == ""
    assert _normalize_date("2023-01-01T00:00:00") == "2023-01-01T00:00:00"
    assert _normalize_date("20170411000000.000") == "2017-04-11T00:00:00"
    assert _normalize_date("20170411") == "2017-04-11T00:00:00"
    assert _normalize_date("not-a-date") == "not-a-date"
