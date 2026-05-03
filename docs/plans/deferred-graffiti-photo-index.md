# Deferred — Graffiti Photo Index (WS3)

**Status:** Pinned 2026-05-03. Design discussion captured below; not implemented.

## Goal

Maintain a running, geolocated index of graffiti photos in New Orleans from public sources, displayed as a separate Leaflet layer on the map. Independent of the blight pipeline; complements the classifier by providing a community-tagged ground-truth set of "verified graffiti" locations.

## Approved decisions

| | Decision | Notes |
|---|---|---|
| D1 | Reddit posts without EXIF GPS get geocoded from title/body text | Nominatim, NOLA bbox guard, 1 req/s throttle, drop low-confidence results |
| D2 | New worksheet tab `graffiti_photos` in the existing Google Sheet | Reuses gspread + the existing proxy (extended with `?tab=photos`) |
| D3 | Daily cadence, same nightly cron as the blight pipeline | New step in `.github/workflows/update_database.yml` |
| D4 | Run the existing ONNX classifier on every candidate; keep score ≥ 0.5 | Filters Mapillary noise + catches mis-tagged Flickr/Reddit posts. Byproduct: free domain-shift evaluation set |
| D5 | New Leaflet layer "🎨 Graffiti spotted" with pink-dot markers; popup shows thumbnail + attribution + source link | Toggleable via the existing `L.control.layers` |
| D6 | Hybrid cache to Drive: cache CC-licensed photos (Mapillary, Wikimedia, Flickr-CC), hot-link the rest with attribution | License-correct + resilient where allowed |

## Source decisions

Confirmed in:

- **Mapillary** — kept. Always-geolocated CC-BY-SA street-level imagery. Free API key.
- **Reddit** — kept. Mostly no GPS, but text-geocoding fallback per D1. OAuth required (script-type app).
- **Flickr** — **dropped** (free tier retired May 2024; paid commercial license starts at ~$1,500/yr).
- **OpenVerse + Wikimedia Commons** — open as drop-in substitutes when reactivating WS3. Both free, no key. OpenVerse aggregates CC-licensed images from many sources including Flickr's CC pool.
- **Instagram + Facebook** — closed-API as of 2025 (Meta locked down both, CrowdTangle shut down August 2024, hashtag search removed 2020). Realistic option is a manual URL-paste UI: user pastes a public IG/FB post URL, the script fetches OpenGraph metadata (`og:image`, `og:description`), classifies, geocodes, inserts.

When picking up this work, the natural source set is:

- Automated: **Mapillary + Reddit + OpenVerse + Wikimedia Commons**
- Semi-manual: **IG/FB URL paste** companion feature (~50 LOC + a button in the UI)

## Architecture sketch

```
Mapillary  ─┐
Reddit     ─┤
OpenVerse  ─┼─▶ scripts/ingest_graffiti_photos.py ─┬─▶ classifier (existing ONNX) ─┬─▶ Sheet tab `graffiti_photos`
Wikimedia  ─┤                                      │   geocode if needed (Nominatim)│
IG/FB URL  ─┘                                      │   bbox-clip to NOLA            │
   (manual)                                        │   dedup (source, photo_id)     │
                                                    └────────────────────────────────┘
                                                                                     │
                                                                                     ▼
                                                        Proxy `?tab=photos`  ──▶  index.html layer
```

## NOLA bounding box

`29.85, -90.15, 30.10, -89.85` — broader than Lower Ninth so the layer captures all of New Orleans. Geocoded results outside this bbox are rejected.

## Sheet schema (new tab `graffiti_photos`)

| col | meaning |
|---|---|
| `photo_id` | namespaced: `mapillary:abc`, `reddit:t3_xyz`, `openverse:uuid`, `wikimedia:File:...`, `manual:hash` |
| `source` | mapillary / reddit / openverse / wikimedia / instagram / facebook |
| `lat`, `lng` | required — drop if both blank |
| `geo_source` | exif / text / api |
| `geo_confidence` | high / medium / low |
| `captured_date` | ISO 8601 |
| `photo_url` | direct image URL |
| `thumb_url` | Drive URL if cached, else blank |
| `source_link` | URL back to source post / photo page |
| `attribution` | photographer + license string (rendered verbatim in popup) |
| `title` | post or photo title (truncated) |
| `tags` | comma-separated source tags |
| `classified_score` | our ONNX score (kept only ≥ 0.5) |
| `first_seen` | UTC timestamp of first ingestion |
| `cached` | boolean — true if `thumb_url` is ours (Drive) |

## Required new secrets when reactivating

- `MAPILLARY_ACCESS_TOKEN`
- `REDDIT_CLIENT_ID` + `REDDIT_CLIENT_SECRET`
- (Nominatim: no key, just a User-Agent string `blight-graffiti-scraper`)
- (OpenVerse, Wikimedia: no key)

## Open follow-up scope (also deferred)

- Heatmap layer variant (D5b) — easy add once dot density justifies it
- Backfill historical photos from each source's archives — only forward-looking ingest is in the design above
- Manual moderation queue — too heavy for the pipeline
- Trend analytics ("graffiti hotspot of the week")

## Known unknowns

- Volume per day from each source — need a one-day pilot run to size the classifier load
- Mapillary's NOLA coverage density — should sample before committing to per-tile querying
- Reddit's text-geocoding hit rate — likely <30% of posts will have parseable location strings
- OpenVerse's "graffiti new orleans" result set is unmeasured

## How to resume

1. Read this doc + the conversation thread that produced it (commits ~2026-05-03 on branch `claude/vibrant-hawking-dd3434`).
2. Move to the writing-plans skill with the design above as the spec.
3. Either keep the design as-is or revisit D1–D6 if external constraints have changed (Reddit API policy, etc.).
