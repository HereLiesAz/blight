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
