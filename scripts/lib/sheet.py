"""Helpers for the blight Google Sheet."""
from __future__ import annotations

import datetime

GRAFFITI_COLUMNS = ("graffiti_score", "graffiti_panoid", "graffiti_classified_at", "streetview_thumb_url")

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


ENRICHMENT_COLUMNS = (
    "zoning_class", "zoning_desc", "land_use_desc", "has_structure",
    "case_count", "earliest_case_date", "days_under_blight", "last_grass_cut",
)


def ensure_enrichment_columns(header_row: list[str]) -> list[str]:
    """Append ENRICHMENT_COLUMNS if not already present. Idempotent."""
    out = list(header_row)
    for col in ENRICHMENT_COLUMNS:
        if col not in out:
            out.append(col)
    return out


def row_needs_enrichment(row: dict) -> bool:
    """True if the property has not been enriched (zoning fields blank)."""
    return not row.get("zoning_class") and not row.get("zoning_desc") and not row.get("land_use_desc")


DEMOLITION_COLUMNS = ("demolition_status", "demolition_date")
PERMIT_HISTORY_COLUMNS = ("permit_count_365d", "permit_types_recent")


def ensure_demolition_columns(header_row: list[str]) -> list[str]:
    out = list(header_row)
    for col in DEMOLITION_COLUMNS + PERMIT_HISTORY_COLUMNS:
        if col not in out:
            out.append(col)
    return out
