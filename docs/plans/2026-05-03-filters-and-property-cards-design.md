# Blight Filters & Property Cards — Design

**Date:** 2026-05-03
**Status:** Approved

## Goal

Two coordinated additions to the existing blight map:

1. **A robust multi-dimensional filter panel with named presets**, driven by enriched per-property data (commercial/residential, days-blighted, repeat-offender, active-rehab, has-structure, etc.) sourced from cross-joins against NOLA's open data catalog.
2. **Tappable property cards in both list views** (`#blighted-modal`, `#list-modal`) that expand inline to show a Street View thumbnail and every field we hold for that property, plus existing note and photo-capture controls.

## Pipeline shape

```
Socrata gjzc-adg8 ──[update_database.py]──▶ Sheet cols A–S (case fields, geopin, permits, stage)
                                                          │
                                                          ▼
NOLA cross-datasets (cym7-cw5z, prh5-qsuf, itxd-2247,    Sheet cols T–AA (zoning, has_structure,
xhih-vxs6, full gjzc-adg8 history)                        ──[enrich_properties.py]──▶ days_blighted,
                                                          case_count, last_grass_cut, land_use_desc, …)
                                                          │
                                                          ▼
Street View tile  ──[classify_graffiti.py]──▶ score   ────▶ thumbnail upload to Drive ──▶ Sheet cols AB–AE
                                                                                          (graffiti_score,
                                                                                          panoid, classified_at,
                                                                                          streetview_thumb_url)
                                                          │
                                                          ▼
                                            index.html: filter drawer + preset dropdown +
                                                        list view + tappable property card
```

Three Actions steps run in order, each idempotent. Order matters because the classifier's thumbnail upload is downstream of enrichment (cards display all enriched fields).

## Sheet schema after this work

| Cols | Source | Fields |
|---|---|---|
| **A–K** | existing (`update_database.py`) | Address, Neighborhood, Name/Type, Status, Previous Statuses, Updated on, Case Number, Notice Date, Deadline, Latitude, Longitude |
| **L–S** | new — `update_database.py` expansion | `geopin`, `init_inspection`, `permit_type`, `permit_status`, `permit_filing`, `next_hearing`, `stage`, `o_c`, `zipcode` (9 fields, drop `Name/Type` parity if needed) |
| **T–AA** | new — `enrich_properties.py` | `zoning_class`, `zoning_desc`, `land_use_desc`, `has_structure`, `case_count`, `earliest_case_date`, `days_under_blight`, `last_grass_cut` |
| **AB–AE** | existing/extended — `classify_graffiti.py` | `graffiti_score`, `graffiti_panoid`, `graffiti_classified_at`, `streetview_thumb_url` (new) |

(Exact column letters may shift; the `ensure_columns` helper handles append-when-missing semantics.)

## Components

### 1. `update_database.py` — pull more Socrata fields

Add these to the SoQL `$select`: `geopin`, `initinspection`, `permittype`, `permitstatus`, `permitfiling`, `nexthearingdate`, `stage`, `o_c`, `zipcode`. Write them as new columns on every row. Idempotent re-write of header + all rows (existing pattern).

### 2. `scripts/enrich_properties.py` — new orchestrator

Iterates sheet rows missing enrichment columns. Per row, with the same throttle/retry pattern as `classify_graffiti.py`:

| Lookup | Endpoint | Fields written |
|---|---|---|
| Zoning | `cym7-cw5z?$where=geopin='<g>'` | `zoning_class`, `zoning_desc` |
| Building footprint | `prh5-qsuf?$where=geopin='<g>' AND activestatus=1` (count > 0 ⇒ has structure) | `has_structure` |
| Case history | `gjzc-adg8?$where=geopin='<g>'$select=initinspection&$order=initinspection ASC` | `case_count`, `earliest_case_date`, `days_under_blight` |
| Grass-cutting | `xhih-vxs6?$where=address='<addr>'&$order=casefiled DESC&$limit=1` | `last_grass_cut` |
| Land use (point-in-polygon) | `itxd-2247?$where=intersects(the_geom,'POINT(<lng> <lat>)')` | `land_use_desc` |

Skip rows already fully enriched. Re-enrich a row only if `geopin` changes or older than `MAX_AGE_DAYS` (default 90 — these fields rarely change).

### 3. `scripts/lib/drive_uploader.py` — small utility

Single function: `upload_thumbnail(folder_id, panoid, jpeg_bytes) → public_url`. Uses the existing service-account `Credentials` (extended scope `https://www.googleapis.com/auth/drive.file`), writes a 256×256 q70 JPEG, sets file permissions to `anyone:reader`, returns a `https://drive.google.com/uc?id=FILE_ID` URL.

Idempotent: searches the folder for an existing file named `<panoid>.jpg` first.

Library: `google-api-python-client==2.149.0` — adding to `scripts/requirements.txt`.

### 4. `classify_graffiti.py` — extends to upload thumbnail

After scoring, if a Drive folder ID is configured, downsample the scraped JPEG and upload via the new util. Write the URL to a fourth sheet column `streetview_thumb_url`. Soft-no-op when Drive folder ID is absent (matches the model-missing soft-skip).

### 5. UI — filter drawer

Replaces the bottom-right HUD-button stack with a slide-out drawer toggled by a single `🎛️ FILTERS` button. Drawer contents:

- **Preset** dropdown (top): "All" / "Fresh Canvases" / "Cluster Bombs" / "Solo Targets" / "Repeat Offenders" / "Long-term Abandoned" / "About to Expire". Picking a preset sets the rest of the drawer's controls in one shot; the user can then tweak.
- **Days since notice** range slider [0…N max]
- **Days remaining in window** chip group: `<7 / 7-14 / >14 / expired`
- **Property type** chip group: `residential / commercial / mixed-use / vacant lot`
- **Has structure** chip group: `yes / no / both`
- **Days under blight** range slider
- **Repeat offender** toggle: case_count ≥ 2
- **Active rehab** chip group: `exclude rehabbing / include all / show only rehabbing`
- **Has graffiti** chip group: `tagged / clean / both / unknown` (preserves existing G3)
- **Cluster size** chip group: `solo (no neighbors within 50m) / cluster (3+ within 100m) / both`
- **Color by**: `status / graffiti score` (preserves existing G4)

State stored in module-scope vars; preset application sets them all at once and triggers a single re-render via existing `filterTimeline()` (renamed to `applyFilters()` since timeline is now one of many).

### 6. UI — tappable property cards

Both `#blighted-modal` (in-view list) and `#list-modal` (full database list) render their items as collapsed rows. Tapping a row toggles an inline expansion that contains:

- 256×256 Street View thumbnail (`<img src="${row.streetview_thumb_url}" loading="lazy">`), placeholder block with address text if URL missing
- All sheet fields formatted as a definition list:
  - Status, notice date, 30-day deadline, days remaining
  - Days under blight, case count
  - Property type (zoning + land use), has structure
  - Last grass cutting (if any), recent permits
  - Graffiti likelihood (existing)
- Two action buttons: **Open in Google Maps** (`https://maps.google.com/?q=lat,lng`) + **Get directions** (existing `?api=1&destination=` URL)
- Existing `note-stash` textarea + `Optics` photo-capture, both backed by `localStorage` (unchanged)

Implementation note: the popup that opens from a marker tap continues to use the abbreviated 3-line format. The new card layout is list-view-only, where vertical real estate is plentiful.

## Decisions

| | Decision | Rationale |
|---|---|---|
| D1 | All enrichment fields go on the existing single sheet | One source of truth; JS proxy code stays simple |
| D2 | Both Future Land Use (current) and Tabular Address Points (stale 2018) used; FLU primary, address points fallback | Best of current + historical |
| D3 | Filter drawer slides in over the map (not pushed alongside) | Mobile-first, the README's hard rule |
| D4 | Cluster density (#9 from buffet) included in this PR | "Solo target" / "cluster bomb" presets need it |
| D5 | Property thumbnails uploaded to a public Google Drive folder | User pick — clean git, modest extra setup |
| D6 | Tap-to-expand uses inline accordion (not modal-on-modal) | Existing modals are already scrollable containers |
| D7 | Card uses the same fields the popup shows + everything else; popup stays terse | Map popup is a glance; card is the deep dive |

## Out of scope

- WS2 (Mapillary/KartaView fallback for the scraper) — separate PR
- WS3 (graffiti-tagged photos of NOLA index) — separate PR
- Static quantization of the model — separate workstream
- Any change to the existing graffiti pipeline beyond adding the thumbnail upload column

## Operator setup

This PR introduces one new manual setup step the user must complete:

1. Create a public Google Drive folder (one-time)
2. Share it with the service account email (Editor)
3. Copy the folder ID from the Drive URL
4. Add as GitHub Actions secret: `STREETVIEW_DRIVE_FOLDER_ID`
5. Ensure the GCP project has Drive API enabled

The pipeline soft-no-ops the upload step when the folder ID is missing — the rest of the pipeline keeps working without thumbnails.

## Risks

- **Drive quota.** Service account default is 15 GB. Thumbnails at 30 KB × 1000 properties = 30 MB. Plenty of headroom. Worst case: ten years of churn at 50% address turnover/yr = ~150 MB. Still fine.
- **Drive public-link rate limits.** Drive's `uc?id=` redirector is sometimes rate-limited under heavy traffic. Acceptable for a hobby map, but if it becomes a problem, swap to a Cloudflare Worker proxy in front of Drive.
- **Cross-dataset Socrata calls.** Each blight property triggers ~5 Socrata calls during enrichment. ~1000 properties × 5 = 5000 calls. Socrata's free quota is generous (1000/hr per app token, more without one). We'll throttle to ≤1 req/s and finish enrichment over the course of the nightly cron.
- **Field-name drift.** Socrata datasets are maintained by the city; field names occasionally change. The enrichment script catches and logs per-row failures without crashing the whole job.
