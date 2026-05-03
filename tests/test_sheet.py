from scripts.lib.sheet import ensure_columns, GRAFFITI_COLUMNS

def test_ensure_columns_adds_missing_headers():
    header_row = ["Address", "Neighborhood", "Name/Type", "Features & 2026 Status",
                  "Previous Statuses", "Updated on", "Case Number", "Notice Date",
                  "Deadline", "Latitude", "Longitude"]
    new_header = ensure_columns(header_row)
    assert new_header[:11] == header_row
    assert tuple(new_header[11:14]) == GRAFFITI_COLUMNS

def test_ensure_columns_idempotent():
    header_row = ["Address", "Latitude", "Longitude"] + list(GRAFFITI_COLUMNS)
    assert ensure_columns(header_row) == header_row

import datetime as _dt
from scripts.lib.sheet import row_needs_classification

NOW = _dt.datetime(2026, 5, 3, tzinfo=_dt.timezone.utc)

def _row(score="", classified_at=""):
    return {"Address": "1 X St", "Latitude": "29.96", "Longitude": "-90.01",
            "graffiti_score": score, "graffiti_panoid": "", "graffiti_classified_at": classified_at}

def test_row_needs_classification_when_no_score():
    assert row_needs_classification(_row(), now=NOW, max_age_days=30) is True

def test_row_skipped_when_recent_score():
    recent = (NOW - _dt.timedelta(days=5)).isoformat()
    assert row_needs_classification(_row(score="0.42", classified_at=recent), now=NOW, max_age_days=30) is False

def test_row_needs_reclassification_when_stale():
    stale = (NOW - _dt.timedelta(days=60)).isoformat()
    assert row_needs_classification(_row(score="0.42", classified_at=stale), now=NOW, max_age_days=30) is True
