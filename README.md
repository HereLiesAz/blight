# 📑 Master Technical Specification: Ninth Ward Canvas (v1.0)

## 1. Project Vision & Constraints
* **Objective:** A mobile-first, tactical mapping tool for artists to identify abandoned properties and their 30-day legal "burn" windows in the Lower Ninth Ward.
* **Tone:** Professional, warm, and utility-driven.
* **Privacy Hard-Rules:** * No user accounts or data persistence.
    * No tracking of user movement or IP addresses.
    * GPS is opt-in via a manual toggle; it must never start automatically.
* **Aesthetic:** High-contrast, "Tactical Dark Mode" (e.g., CartoDB Dark Matter tiles). No decorative fluff.

## 2. Technical Stack & Environment
* **Architecture:** Single-file `index.html` (HTML5, CSS3, and Vanilla JavaScript).
* **Dependencies:** Leaflet.js (CSS and JS loaded via CDN in the `<head>`). No external local folders.
* **Styling:** Internal `<style>` block. The map must fill the full viewport (`100vh`).
* **Deployment:** Optimized for simple static hosting like GitHub Pages or Netlify Drop.

## 3. Data Pipeline & Logic (NOLA Open Data)
* **Source:** NOLA Open Data Portal (Socrata API).
* **Endpoint:** `https://data.nola.gov/resource/gjzc-adg8.json`
* **SoQL Query Parameters:**
    * `$where`: `neighborhood='LOWER NINTH WARD' AND status IN('Guilty', 'Uncommitted')`
    * `$select`: `address, status, notice_date, location, casenumber`
* **Processing Rules:** * **Skip Logic:** If a record is missing `location` (latitude/longitude), do not map it.
    * **Deadline Calculation:** Parse the `notice_date` and calculate the "Deadline" by adding exactly 30 days to it.

## 4. Map & UI Requirements
* **Initial View:** Center map at coordinates `[29.965, -90.007]` with a zoom level of `15`.
* **Marker Visuals:** * `Guilty` status = Red Circle Marker.
    * `Uncommitted` status = Amber/Orange Circle Marker.
* **Popup Architecture:** Triggered on tap. Must use this HTML structure:
    ```html
    <div class="popup-content">
      <h3>${property.address}</h3>
      <p><b>Status:</b> ${property.status}</p>
      <p><b>Notice Date:</b> ${formattedNoticeDate}</p>
      <p><b>30-Day Deadline:</b> ${formattedDeadline}</p>
    </div>
    ```
* **Tactical Controls:** * Add a single "Follow GPS" toggle button in the bottom-right corner.
    * Touch targets for all interactive elements must be at least `44px` for mobile accessibility.

## 5. Property Enrichment & Filters

Each address is enriched from NOLA Socrata cross-datasets and visual classification:

* **Property type, zoning, land-use, has-structure** (Tabular Address Points + Future Land Use + Building Footprint)
* **Days under blight, case count, repeat-offender flag** (full case history from Code Enforcement)
* **Active-rehab signal** (recent permit type + status from the Code Enforcement record)
* **Last grass-cutting case** (Lot Abatement Chapter 66 — overgrowth proxy)
* **Demolition lifecycle** (BlightStatus Demolitions + Permit Apps Demolition rows): pending / permitted / completed
* **Recent permit history** (Building Permits 2018+ joined by `geopin`): permit count in last 365 days + most recent 5 permit types
* **OSM-derived blight-adjacent features** (Overpass API): abandoned buildings, ruins, brownfields, disused structures across the city, surfaced as a toggleable map layer
* **Spatial-pattern auto-pins**: cluster centroids (geographic centers of dense blighted clusters) and solo outliers (isolated targets) computed in-browser from existing lat/lng
* **Graffiti likelihood** (Street View tile + EfficientNet-B0 ONNX classifier)
* **Street View thumbnail** (cached in a public Google Drive folder)

The map provides a slide-out filter drawer with named presets — "Fresh canvases", "Cluster bombs", "Solo targets", "Repeat offenders", "Long-term abandoned", "About to expire" — and tappable property cards in both list views (in-view blighted list + full database list) showing the thumbnail and every enrichment field for the selected property.

See [`docs/graffiti-pipeline.md`](docs/graffiti-pipeline.md) for setup details (including the one-time `STREETVIEW_DRIVE_FOLDER_ID` secret) and operator runbooks. Model card: [`MODEL_CARD.md`](MODEL_CARD.md).
