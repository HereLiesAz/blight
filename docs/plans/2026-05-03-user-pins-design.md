# User Pins & Auto-Hotspot Pins (PR-1) — Design

**Date:** 2026-05-03
**Status:** Approved

## Goal

Two coordinated additions to the existing blight map:

1. **User-placed pins**, stored per device in `localStorage`, organized by user-managed flat-list categories with emoji markers. Long-press on the map opens a category menu; tapping a category drops a pin at that location with that category.
2. **Auto-hotspot pin layer**, derived from the existing `graffitiScore` column (threshold ≥ 0.8), shown as a separate toggleable Leaflet layer.

This is the first PR of a three-phase rollout (β) the user approved earlier:

- **PR-1 (this design):** user-placed pins + auto-hotspot pins
- **PR-2 (deferred):** spatial-pattern auto-pins (cluster centroids, isolated outliers)
- **PR-3 (deferred):** OSM-derived auto-pins (abandoned buildings, alleys)

## Storage

All user data is per-device, in `localStorage`, matching the existing privacy hard-rule and the existing `txt_<addr>` / `img_<addr>` patterns.

```javascript
// localStorage["user_pins"]
[
  { id: "pin-1759543210123-7", lat: 29.964, lng: -90.007,
    category: "Target", title: "", note: "", created: "2026-05-03T18:30:00Z" },
  ...
]

// localStorage["pin_categories"]   (seeded with 6 defaults; user can add/remove)
[
  { name: "Target",       emoji: "🎯" },
  { name: "Scout",        emoji: "👁️" },
  { name: "Hit",          emoji: "🎨" },
  { name: "Photographed", emoji: "📷" },
  { name: "Done",         emoji: "✅" },
  { name: "Skip",         emoji: "🚫" }
]
```

The existing QR-export (`generateQR()` / "Beam Intel") is extended to include `pins` and `categories` alongside the existing notes, so cross-device transfer continues to work.

## Placement flow (long-press → menu → place)

```
user long-presses map
   │
   ▼  Leaflet emits `contextmenu` with latlng (works on iOS Safari, Android Chrome
   │   long-press; falls through to right-click on desktop)
   │
   ▼
L.popup at latlng with category buttons:
  [🎯 Target] [👁️ Scout] [🎨 Hit] [📷 Photographed] [✅ Done] [🚫 Skip]
  [+ New category…]   [Cancel]
   │
   ▼  user taps a category
   │
   ▼
pin appears at latlng using that category's emoji as the marker icon
(L.marker with L.divIcon — emoji as a centered glyph in a small circular bg)
```

If `contextmenu` doesn't fire on a particular browser, a fallback long-press detector (touchstart + 600 ms timer + touchend cancels) covers it. A `📍 Add pin` toolbar button is also exposed for desktop discoverability and as a guaranteed alternative.

When the user long-presses on an existing blight circle marker (not empty map), the category menu does not open — propagation is stopped at the marker so the existing popup opens instead.

## Pin interaction

Tap a user pin → popup with:

- Title field (free text)
- Notes field (free text)
- Change-category dropdown
- Delete button

Drag-to-reposition is intentionally out of scope for PR-1.

## Category management

- **Add custom category**: small modal prompts for emoji + name, appends to the localStorage list. No emoji-picker library — a single text input accepts whatever character the user types (works fine for emoji on mobile keyboards).
- **Delete category**: if any pins use it, prompt: "3 pins are in this category. Delete and reassign them to '🎯 Target'?"
- A `📂 Pin categories` button in the existing HUD opens this manager.

## Auto-hotspot pins (auto-pin type b)

A separate `L.layerGroup` named `hotspotLayer`. On every `applyFilters()` re-render, the layer is repopulated from `masterData` where `prop.graffitiScore >= 0.8`. Visual: 🎨 emoji marker (consistent with the "Hit" category) with a subtle CSS pulse animation so auto-pins are visually distinct from manually-placed Hit pins. Registered in the existing `L.control.layers` overlay control as **"🎨 Hotspots (auto)"**, toggleable, hidden by default.

## Layer toggles

The existing `L.control.layers` gains two new overlay entries:

- 📍 My pins (user-placed) — on by default
- 🎨 Hotspots (auto, score ≥ 0.8) — off by default

## Edge cases

- **Empty category list**: pin placement still works — the menu shows only `+ New category…` and `Cancel`. Forcing at least one category before placement keeps the data model consistent.
- **Storage quota**: pins are ~150 bytes each; 1000 pins ≈ 150 KB, well under the 5 MB localStorage cap.
- **Schema evolution**: `localStorage` reads are wrapped in try/catch with reset-to-defaults on parse failure, matching the existing `getNote` defensive pattern.
- **Privacy**: all pin data is per-device. No proxy/sheet writes. Matches the README's privacy hard-rule.

## Component layout

| File | Change |
|---|---|
| `index.html` | All work for PR-1 lives here — CSS, markup, script |
| (no Python changes) | The auto-hotspot derivation reads existing `prop.graffitiScore`; no backend work needed |

## Risks

- **`contextmenu` mobile inconsistency.** Some Android WebViews and older iOS may not fire `contextmenu` on long-press. Mitigated by the fallback long-press detector and the `📍 Add pin` toolbar button.
- **Emoji rendering varies by OS.** A `🎯` on Windows vs Apple looks different but is always recognizable. Acceptable.
- **localStorage corruption** on schema evolution: defensive reads + reset-to-defaults.

## Out of scope (deferred)

- Spatial-pattern auto-pins (cluster centroids, isolated outliers) → PR-2
- OSM-derived auto-pins (abandoned buildings, alleys) → PR-3
- Cloud-persistent pin sharing — would break the privacy hard-rule
- Drag-to-reposition

## Decisions

| | Decision |
|---|---|
| D1 | localStorage per-device storage (privacy rule) |
| D2 | Long-press → category menu → tap to place |
| D3 | Flat list of categories (presets + user-creatable) |
| D4 | Default presets: Target / Scout / Hit / Photographed / Done / Skip |
| D5 | Single hotspot threshold at `graffiti_score ≥ 0.8` |
| D6 | Emoji glyph as marker icon (via `L.divIcon`) |
| D7 | Phased rollout (β); PR-1 = user pins + hotspot layer; PR-2/3 deferred |
