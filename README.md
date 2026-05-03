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

## 5. Graffiti Classifier (Optional Enrichment)

Each address can be enriched with a graffiti-presence score (0.0–1.0) derived from Street View imagery and a small CNN classifier. The score drives an in-app filter (`🎨 Graffiti: All / Tagged / Clean`) and a color-by-score mode. See [`docs/graffiti-pipeline.md`](docs/graffiti-pipeline.md) for retraining, deployment, and operational details. Model card: [`MODEL_CARD.md`](MODEL_CARD.md).
