# 🎨 Red Blight District

Welcome to the **Red Blight District**, a mobile-first mapping tool designed for street artists and graffiti writers.

This app allows you to explore abandoned properties, view legal "burn" windows, analyze blight trends, and plan artistic interventions or community revitalization efforts—all from a fully native, high-performance Android experience.

---

## 🎯 What is it?
Red Blight District pulls real-time and enriched open data from the City of New Orleans (NOLA Open Data/Socrata) to provide a rich, interactive map of blighted and uncommitted properties.

It acts as a digital scout, helping you identify "Fresh Canvases", avoid "Repeat Offenders," and analyze spatial patterns like clusters of abandonment.

## ✨ Key Features
- **Native Android Performance:** Rewritten from the ground up in Kotlin for a buttery-smooth mapping experience, dropping the old web wrappers.
- **Tactical Dark Mode:** High-contrast CartoDB Dark Matter map tiles designed for night scouting and low-light visibility.
- **Real-Time City Data:** Direct integration with the NOLA Open Data Portal to track property statuses (`Guilty`, `Uncommitted`), case histories, and demolition lifecycles.
- **Advanced Filtering:** Slice the map via slide-out filters:
  - Timeline sliding (Past 1–12 months or all-time)
  - Quick presets: *Fresh canvases, Cluster bombs, Solo targets, About to expire*
  - Filter by structure presence, zoning, active rehab status, and more.
- **Auto-Clustering & Heatmaps:** See immediate spatial patterns using algorithmic hotspots and heatmaps.
- **ALPR Awareness:** Optional `📷 ALPR cameras` layer surfaces Automated License Plate Reader locations sourced from the [DeFlock](https://github.com/FoggedLens/deflock) crowdsourced dataset (R2 tile CDN, with an OpenStreetMap Overpass fallback). Toggle it on from the layer control to scout surveillance footprint before fieldwork.
- **Data-Freshness Badge:** A cyan pill at the top of the map shows the newest `notice_date` in the loaded dataset, so you can tell at a glance how stale the underlying city feed is.
- **Cluster Street Views:** Cluster-center 🎯 markers float a [Mapillary](https://www.mapillary.com/) crowd-sourced street-view thumbnail above the pin when coverage exists. Drop a free Mapillary token into `DataFetcher.MAPILLARY_TOKEN` to enable it; otherwise the feature stays silent.
- **Local Intel Stash:** Tap any property to take notes, assign custom emoji pins, and capture photos directly to your device (data stays local!).
- **Privacy-First:** No accounts, no cloud syncing of your notes, and GPS tracking only happens when you explicitly tap "Track GPS."
- **Ghost Protocol:** A single panic button to instantly wipe all your local intel (notes, pins, photos) and reload the app cleanly.

---

### Basic Usage
* **Pan & Zoom:** Standard map controls apply. As you move, the app automatically fetches the latest data for your current sector.
* **Filter:** Tap the **FILTERS** button in the bottom right to open the drawer and apply advanced data constraints.
* **Scout:** Tap any marker on the map to open its info window. From there, you can write notes, save photos ("Optics"), or get driving routes.
* **Custom Pins:** Use the **📂 Pins** button to create custom emoji categories, then long-press anywhere on the map to drop a pin.
* **Beam Intel:** Use the **🕸️ Beam Intel** button to generate a QR code containing all your local notes and pins, allowing you to share intel offline with teammates.

---

## 🔒 Privacy & Safety
Ninth Ward Canvas is built with your safety in mind:
- **Zero Tracking:** The app does not ping analytics servers or track your IP.
- **Local Storage:** All your "Optics" (photos), notes, and custom pins are saved directly to `SharedPreferences` on your physical device. Nothing is sent to a central server.
- **Ghost Button:** Erases all local app storage instantly. Use it if you need to wipe your digital footprint fast.

*Disclaimer: This tool provides data for informational purposes based on NOLA's public datasets. Always exercise caution and "respect" local laws when doing field work.*
