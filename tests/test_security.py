import responses
import urllib.parse
from scripts.lib.enrichment import fetch_case_history, fetch_zoning, fetch_footprint, fetch_last_grass_cut

@responses.activate
def test_fetch_case_history_vulnerability_fixed():
    responses.add(
        responses.GET,
        "https://data.nola.gov/resource/gjzc-adg8.json",
        json=[],
        status=200,
    )

    malicious_geopin = "123' OR '1'='1"
    fetch_case_history(malicious_geopin)

    # Check the last request's URL parameters
    last_request = responses.calls[0].request
    parsed_url = urllib.parse.urlparse(last_request.url)
    params = urllib.parse.parse_qs(parsed_url.query)

    # Should now be escaped (doubled single quotes)
    expected_escaped = "123'' OR ''1''=''1"
    assert f"geopin='{expected_escaped}'" in params['$where'][0]

@responses.activate
def test_fetch_zoning_vulnerability_fixed():
    responses.add(responses.GET, "https://data.nola.gov/resource/cym7-cw5z.json", json=[], status=200)
    malicious_geopin = "123' OR '1'='1"
    fetch_zoning(malicious_geopin)
    last_request = responses.calls[0].request
    params = urllib.parse.parse_qs(urllib.parse.urlparse(last_request.url).query)
    expected_escaped = "123'' OR ''1''=''1"
    assert f"geopin='{expected_escaped}'" in params['$where'][0]

@responses.activate
def test_fetch_footprint_vulnerability_fixed():
    responses.add(responses.GET, "https://data.nola.gov/resource/prh5-qsuf.json", json=[], status=200)
    malicious_geopin = "123' OR '1'='1"
    fetch_footprint(malicious_geopin)
    last_request = responses.calls[0].request
    params = urllib.parse.parse_qs(urllib.parse.urlparse(last_request.url).query)
    expected_escaped = "123'' OR ''1''=''1"
    assert f"geopin='{expected_escaped}'" in params['$where'][0]

@responses.activate
def test_fetch_last_grass_cut_vulnerability_fixed():
    responses.add(responses.GET, "https://data.nola.gov/resource/xhih-vxs6.json", json=[], status=200)
    malicious_address = "123 Main St' OR '1'='1"
    fetch_last_grass_cut(malicious_address)
    last_request = responses.calls[0].request
    params = urllib.parse.parse_qs(urllib.parse.urlparse(last_request.url).query)
    expected_escaped = "123 Main St'' OR ''1''=''1"
    assert f"address='{expected_escaped}'" in params['$where'][0]
