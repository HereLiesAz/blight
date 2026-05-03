"""Helpers for the blight Google Sheet."""
from __future__ import annotations

import datetime

GRAFFITI_COLUMNS = ("graffiti_score", "graffiti_panoid", "graffiti_classified_at")

def ensure_columns(header_row: list[str]) -> list[str]:
    """Return header_row with GRAFFITI_COLUMNS appended if not already present."""
    out = list(header_row)
    for col in GRAFFITI_COLUMNS:
        if col not in out:
            out.append(col)
    return out


def row_needs_classification(row: dict, *, now: datetime.datetime, max_age_days: int) -> bool:
    if not row.get("graffiti_score"):
        return True
    ts = row.get("graffiti_classified_at", "")
    if not ts:
        return True
    try:
        when = datetime.datetime.fromisoformat(ts)
    except ValueError:
        return True
    if when.tzinfo is None:
        when = when.replace(tzinfo=datetime.timezone.utc)
    return (now - when).days >= max_age_days
