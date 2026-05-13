import sys
from unittest.mock import MagicMock

# Mocking external dependencies before importing the module under test
sys.modules["requests"] = MagicMock()
sys.modules["gspread"] = MagicMock()
sys.modules["google.oauth2.service_account"] = MagicMock()
# Mock scripts.lib.sheet as it might also have dependencies
mock_sheet = MagicMock()
mock_sheet.SPREADSHEET_ID = "mock_id"
sys.modules["scripts.lib.sheet"] = mock_sheet

from datetime import datetime
from scripts.update_database import parse_socrata_date, _parse_socrata_date_str

def test_parse_socrata_date_iso_with_t():
    assert parse_socrata_date('2023-10-27T00:00:00') == datetime(2023, 10, 27)

def test_parse_socrata_date_hyphen():
    assert parse_socrata_date('2023-10-27') == datetime(2023, 10, 27)

def test_parse_socrata_date_compact():
    assert parse_socrata_date('20231027') == datetime(2023, 10, 27)

def test_parse_socrata_date_empty():
    assert parse_socrata_date('') is None

def test_parse_socrata_date_none():
    assert parse_socrata_date(None) is None

def test_parse_socrata_date_invalid():
    assert parse_socrata_date('invalid') is None

def test_parse_socrata_date_str_valid():
    assert _parse_socrata_date_str('2023-10-27') == '10/27/2023'

def test_parse_socrata_date_str_invalid():
    assert _parse_socrata_date_str('invalid') == ''
