# Demolitions, Permits & Evictions — Design

**Date:** 2026-05-03
**Status:** Approved

## Goal

Add three new data streams to the blight pipeline:

1. **Demolition lifecycle** — pending / permitted / completed status per property, sourced from `aib5-en5t` (Permit Apps, Demolition rows), `nbcf-m6c2` (Building Permits 2018+), and `e3wd-h7q2` (BlightStatus Demolitions, completed).
2. **Full permit history per property** — count of permits filed at the property in the last 365 days plus the most recent 5 permit types. Joined by `geopin` against `nbcf-m6c2`.
3. **Eviction filings** — daily scrape of First City Court (Algiers) and Second City Court (East Bank) docket searches for "Rule for Possession" filings. Geocoded via Nominatim with NOLA bbox guard. Defendant names redacted to initial + last name. The user explicitly accepted the brittleness of court scraping.

All three surface as both per-property enrichment columns (filterable) and city-wide map layers (visualized).

## Architecture

```
NOLA Socrata
  ├─ gjzc-adg8 (existing) ── update_database.py → cols A-T
  ├─ e3wd-h7q2 (BlightStatus Demolitions, completed)        ─┐
  ├─ aib5-en5t (Permit Apps, demolition rows)                ├─ ingest_demolitions.py → cols + tab
  └─ nbcf-m6c2 (Building Permits 2018+, all types)           ┴─ ingest_permits.py     → cols + tab

First City Court  ─┐
Second City Court ─┴─ scrape_evictions.py (court_scraper.py utils) → cols + tab
                                  │
                                  └─ Nominatim geocode (NOLA bbox guard)
```

Three new GH Actions steps run after `enrich_properties.py`, all best-effort (any failure soft-skips and the rest of the pipeline keeps going).

## New components

| File | Purpose |
|---|---|
| `scripts/lib/court_scraper.py` | Shared HTTP session + throttle + address-normalize utility |
| `scripts/lib/demolitions.py` | Joins BlightStatus Demos + Demolition permits → status timeline |
| `scripts/lib/permits.py` | Full permit history per `geopin`; aggregates `count_365d` + `types_recent` |
| `scripts/lib/geocoder.py` | Nominatim wrapper (NOLA bbox guard, 1 req/s throttle, low-confidence reject) |
| `scripts/scrape_evictions.py` | Scrapes both courts daily, dedups by case number, geocodes addresses |
| `scripts/ingest_demolitions.py` | Per-property update + populates city-wide `demolitions` tab |
| `scripts/ingest_permits.py` | Per-property update only (no separate tab — too many rows) |

## New sheet schema

### Main blight tab — per-property enrichment columns added

| col | meaning |
|---|---|
| `demolition_status` | `pending` / `permitted` / `completed` / blank |
| `demolition_date` | ISO date of most-recent lifecycle event |
| `permit_count_365d` | int — permits filed at this property in last 365 days |
| `permit_types_recent` | comma-separated last-5 permit types |
| `eviction_count_365d` | int — eviction filings against this address in last 365 days |
| `last_eviction_date` | ISO date of most-recent eviction filing |

### New worksheet tabs (city-wide, drive the map layers)

**`demolitions`**
| col | meaning |
|---|---|
| `id` | `e3wd:<objectid>`, `nbcf:<permit_no>`, `aib5:<permit_no>` |
| `lat`, `lng` | required |
| `address` | normalized form |
| `geopin` | when known |
| `status` | `pending` / `permitted` / `completed` |
| `event_date` | ISO date |
| `source` | `e3wd-h7q2` / `nbcf-m6c2` / `aib5-en5t` |
| `permit_no` | when known |

**`evictions`**
| col | meaning |
|---|---|
| `case_no` | court case number — primary key for dedup |
| `court` | `FCC` / `SCC` |
| `lat`, `lng` | required (drop if geocoding failed AND no other geo source available) |
| `address` | normalized form |
| `filed_date` | ISO date |
| `plaintiff` | landlord / LLC name (kept as-is — public record, public-interest information) |
| `defendant_redacted` | first initial + last name (e.g. `J. Smith`) |
| `status` | `Filed` / `Judgment` / `Possession Granted` / etc. (court status text) |
| `first_seen` | UTC timestamp of first ingestion |

## Address normalization

Both court records and the blight DB use uppercase abbreviated addresses (`123 N RAMPART ST`) but spacing and abbreviations vary (`ST.` vs `ST`, ordinal suffixes, etc.). A `normalize_address(s)` function in `court_scraper.py` produces a canonical form: uppercase, no periods, common abbreviations expanded then re-collapsed (`STREET`→`ST`, `AVENUE`→`AVE`, etc.), no leading zeroes on house numbers, no zip. Imperfect but tractable.

Unjoined evictions still appear in the `evictions` tab and on the map layer; only the per-property aggregate column undercounts. Acceptable.

## UI additions

### Filter drawer — new chips

- `🏗️ Demolition` — `pending` / `permitted` / `completed` / `none` (multi-select)
- `📜 Eviction` — `recent (≤365d)` / `historic` / `none`
- `🔧 Recent permits` — slider `0–5+` (filters `permit_count_365d`)

### Map layer toggles (new entries in `L.control.layers`)

- `🏗️ Demolitions` — pink-edged 🏗️ markers from the `demolitions` tab
- `📜 Evictions` — orange-edged 📜 markers from the `evictions` tab

### Property card (new fields shown when present)

- Demolition status + date
- Permits in last year (count + last 5 types)
- Eviction count + last date

## Eviction scraper realities

Both courts use Tyler Technologies' Odyssey portal (typical for Louisiana parish courts). Strategy:

1. **Daily date-window queries.** Query "case type = Rule for Possession, filed in last 7 days" each run; dedup against existing `evictions` tab. Robust to DOM tweaks (small set re-fetched, not the whole archive).
2. **Throttle hard** — 1 req per 8 s, exponential backoff on 429/503.
3. **User-agent rotation** — same pattern as the Street View scraper.
4. **CAPTCHA detection** — if a CAPTCHA / WAF block page comes back, the script logs the error and exits 0 (soft-fail; column goes stale that day).
5. **Geocoding** — addresses go through Nominatim with NOLA bbox guard. Drop low-confidence results.
6. **Privacy** — defendant names redacted to first initial + last name. Plaintiff (LLC owner) kept as-is — public-record landlord behavior is information-of-public-interest.

## GH Actions integration

Three new steps after `enrich_properties.py`, before `classify_graffiti.py`:

```yaml
- name: Ingest demolitions (best-effort)
- name: Ingest permits (best-effort)
- name: Scrape evictions (best-effort)
```

Each independent; any failure soft-skips with an `|| echo` per the existing pattern.

## Decisions

| | Decision |
|---|---|
| D1 | Both courts (First + Second) for evictions |
| D2 | Full demolition lifecycle (pending + permitted + completed) |
| D3 | Full permit history per blight property; no city-wide permit tab |
| D4 | Both columns (filterable) AND layers (visualized) |
| D5 | Daily cadence for everything, including the eviction scraper |
| D6 | Defendant names → first initial + last name; plaintiffs kept as-is |
| D7 | All three new ingest steps soft-fail without breaking the pipeline |
| D8 | `evictions` tab and `demolitions` tab are city-wide; the existing main tab gets per-property aggregate columns only |

## Risks

- **Court scraper is the brittlest single component in the project.** DOM/portal changes will break it; some weeks the eviction column will go stale. Accepted per the user's (b) choice in Q1.
- **CAPTCHA wall.** If either court adds CAPTCHA on the public docket search, that scraper is dead. Pivot: switch that source to a scraped weekly-docket PDF if available, or (last resort) drop that court.
- **Daily IP-ban risk** for the GH Actions runner. Mitigated by tight throttle and narrow date window. If the runner is banned, scraping fails and the rest of the pipeline keeps working.
- **Sheet width.** Adding 6 columns brings us to ~AL. Still under Sheets' 18,278-column limit; the existing JS handles `row_dict` lookups by name, not index, so column order is fine.
- **Address-normalization false negatives.** Some evictions won't join to the blight DB. They still appear in the `evictions` tab and on the map layer; only the per-property aggregate undercounts.
- **Privacy edge cases.** Initial+last-name redaction is conservative for an open-source repo; the plaintiff side stays full because plaintiff conduct is the public-policy story. Worth re-visiting if anyone challenges it.

## Out of scope

- PDF docket parsing as an alternative scrape path (fallback only if Odyssey turns out to be CAPTCHA-walled)
- Aggregator pivots (Jane Place's eviction tracker, Eviction Lab tract data)
- Auto-pin layers (PR-2/PR-3) — these data layers are toggleable but not auto-pinned
- City-wide permits tab — joining permits to existing blight is sufficient
- Manual eviction look-up UI
