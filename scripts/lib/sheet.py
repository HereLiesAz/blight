"""Helpers for the blight Google Sheet."""
from __future__ import annotations

GRAFFITI_COLUMNS = ("graffiti_score", "graffiti_panoid", "graffiti_classified_at")

def ensure_columns(header_row: list[str]) -> list[str]:
    """Return header_row with GRAFFITI_COLUMNS appended if not already present."""
    out = list(header_row)
    for col in GRAFFITI_COLUMNS:
        if col not in out:
            out.append(col)
    return out
