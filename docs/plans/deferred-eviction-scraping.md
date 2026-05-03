# Deferred — Eviction Scraping

**Status:** Pinned 2026-05-03. Investigation into NOLA eviction data sources captured below; not implemented.

## What was attempted

The original design ([2026-05-03-demolitions-permits-evictions-design.md](2026-05-03-demolitions-permits-evictions-design.md)) called for daily scraping of First City Court and Second City Court Odyssey portals to capture "Rule for Possession" (eviction) filings. Investigation revealed the situation is more nuanced than that.

## Findings

| Court | Coverage | Online portal | Tech | Cost |
|---|---|---|---|---|
| **First City Court** (East Bank — incl. Lower Ninth Ward) | ~95% of relevant evictions for our user base | [portal.orleansconstable.net/Case/Search](https://portal.orleansconstable.net/Case/Search) — Constable's service-of-process tracker | DigiCourt SPA (JavaScript-heavy) | Free |
| **Second City Court** (West Bank — Algiers) | ~5% of relevant evictions | **None found.** Algiers Constable has no online portal; records are in-person only at their office | — | — |
| **Orleans Civil District Court** (eviction *appeals* + civil cases generally — different scope) | Out of scope for residential evictions specifically | [remoteaccess.orleanscivilclerk.com](https://remoteaccess.orleanscivilclerk.com/) | Tyler Odyssey | **Paid: $20/day, $700/year, login required** |

Note: I had the courts mentally swapped in earlier brainstorming — First City is East Bank, Second City is Algiers. Corrected here.

## What the data actually represents

The free First City Court source is the **Constable's service-of-process tracker**, not raw court dockets. Differences:

- It tracks evictions where service was *attempted or completed* — i.e., evictions actively being enforced
- Plaintiff/defendant names are not the primary data; address + service date + case number are
- Arguably a *more actionable* signal than raw filings ("the eviction has reached the constable" = forward-momentum signal)
- But the original schema in the design doc (`plaintiff`, `defendant_redacted`, `status`) is wrong-shaped for this source

## Implementation blocker

The First City Court portal is a **DigiCourt SPA** — page renders empty HTML on first load and pulls case data via XHR after JavaScript execution. `WebFetch`-style HTML scraping returns no useful data.

Three paths forward when this is reactivated:

### Path A — Reverse-engineer the XHR endpoint

Cleanest. Requires the operator to:
1. Open the portal in Chrome/Firefox
2. Open DevTools (F12) → Network tab → filter Fetch/XHR
3. Perform a search
4. Capture the request URL, method, body, headers, and a sample of the response shape
5. Provide that capture to the implementer (or paste a `Copy as cURL` of the request)

The implementer then builds a Python script that hits that endpoint directly. Stable as long as the portal doesn't change its JS bundle.

### Path B — Headless browser (Playwright)

If reverse-engineering is blocked (unfamiliar JS, custom auth flow, DOM-coupled tokens), use Playwright to render the SPA and extract results. Adds ~half a task to the plan plus the Playwright runtime (~80 MB) on the Actions runner.

This is a **deliberate exception** to the project's "no headless browser" rule for the Street View scraper. Justification: the Constable's portal is the city's own public-records system, published for public access; the rule was about Google ToS specifically.

### Path C — Pivot away from per-property data

If both A and B are too brittle, fall back to aggregate eviction data:

- **Eviction Lab** (Princeton) — census-tract aggregates only, no per-property data. Useful as a heatmap layer ("this neighborhood has high eviction velocity") but not as per-property markers.
- **Jane Place Neighborhood Sustainability Initiative** — has historically published scraped eviction data. Worth checking if their published dataset is still updated.
- **The Lens NOLA** — has previously published eviction data analyses. Public spreadsheets may be available.

## Approved decisions to preserve

When this is reactivated, the following decisions from the original design still hold (assuming the data source supports them):

| | Decision |
|---|---|
| D1 (revised) | Only First City Court is online; Second City Court not addressable without manual records pulls. Acceptable since FCC covers ~95% of relevant evictions |
| D2 | Daily cadence |
| D3 | Defendant names redacted (first initial + last name); plaintiffs full |
| D4 | New worksheet tab `evictions` (city-wide, drives map layer) |
| D5 | Per-property aggregate columns: `eviction_count_365d`, `last_eviction_date` |
| D6 | Soft-fail without breaking the rest of the pipeline |
| D7 | Marker style: 📜 with orange border |
| D8 | Schema must be reshaped vs. the design doc — Constable's portal exposes service-of-process events, not full docket details |

## Required new secrets when reactivating

- `COURT_FCC_URL` — the actual XHR endpoint (Path A) or page URL (Path B)
- (No equivalent for Second City Court since none exists)

## Resume checklist

1. Pick Path A, B, or C
2. If Path A: do the DevTools capture and feed it to the implementer
3. Reshape the `evictions` tab schema to match service-of-process data (case_no, court, lat, lng, address, service_date, status, first_seen)
4. Reshape `parse_odyssey_results` in `scripts/lib/court_scraper.py` to match the new endpoint
5. Test against a real response before enabling the daily cron
6. Consider whether to drop the `plaintiff` / `defendant_redacted` columns or fetch them via a secondary lookup if the Constable's portal links case numbers to docket pages

## Demolitions + permits status

Independent of the eviction blocker, the demolitions and permits portions of the original design (Parts B and C of the implementation plan) are **unblocked** — both pull from NOLA Socrata datasets that are public, free, and already proven to work via our existing enrichment pipeline. They can be built standalone without touching evictions.
