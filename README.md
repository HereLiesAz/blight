# 🎨 Ninth Ward Canvas

Welcome to **Ninth Ward Canvas**, a tactical, mobile-first mapping tool designed for artists, organizers, and community members in the Lower Ninth Ward.

This app allows you to explore abandoned properties, view legal "burn" windows, analyze blight trends, and plan artistic interventions or community revitalization efforts—all from a fully native, high-performance Android experience.

---

## 🎯 What is it?
Ninth Ward Canvas pulls real-time and enriched open data from the City of New Orleans (NOLA Open Data/Socrata) to provide a rich, interactive map of blighted and uncommitted properties.

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
- **Local Intel Stash:** Tap any property to take notes, assign custom emoji pins, and capture photos directly to your device (data stays local!).
- **Privacy-First:** No accounts, no cloud syncing of your notes, and GPS tracking only happens when you explicitly tap "Track GPS."
- **Ghost Protocol:** A single panic button to instantly wipe all your local intel (notes, pins, photos) and reload the app cleanly.

---

## 🚀 Getting Started

### Installation
1. Go to the [Releases](https://github.com/HereLiesAz/blight/releases) page.
2. Download the latest `app-release.apk` directly to your Android device.
3. Open the APK and tap **Install** (you may need to allow installations from unknown sources in your Android settings).

### Basic Usage
* **Pan & Zoom:** Standard map controls apply. As you move, the app automatically fetches the latest data for your current sector.
* **Filter:** Tap the **FILTERS** button in the bottom right to open the drawer and apply advanced data constraints.
* **Scout:** Tap any marker on the map to open its info window. From there, you can write notes, save photos ("Optics"), or get driving routes.
* **Custom Pins:** Use the **📂 Pins** button to create custom emoji categories, then long-press anywhere on the map to drop a pin.
* **Beam Intel:** Use the **🕸️ Beam Intel** button to generate a QR code containing all your local notes and pins, allowing you to share intel offline with teammates.

---

## 🛠 For Developers

Want to build it yourself? The app is built with Kotlin, using Gradle, and targets modern Android SDKs.

### Prerequisites
- Android Studio (Electric Eel or newer recommended)
- Java Development Kit (JDK) 17
- Android SDK 34

### Build Instructions
1. Clone the repository: `git clone https://github.com/HereLiesAz/blight.git`
2. Open the `android/` directory in Android Studio.
3. Let Gradle sync the dependencies (including `osmdroid`, `OkHttp`, and `Gson`).
4. Hit **Run** (`Shift + F10`) to build the app and deploy it to an emulator or plugged-in Android device.

*Note on Python Pipeline:* The repository also contains the `scripts/` folder, which handles the machine-learning pipeline for graffiti classification and dataset enrichment. See `docs/graffiti-pipeline.md` for ML setup instructions.

---

## 🔒 Privacy & Safety
Ninth Ward Canvas is built with your safety in mind:
- **Zero Tracking:** The app does not ping analytics servers or track your IP.
- **Local Storage:** All your "Optics" (photos), notes, and custom pins are saved directly to `SharedPreferences` on your physical device. Nothing is sent to a central server.
- **Ghost Button:** Erases all local app storage instantly. Use it if you need to wipe your digital footprint fast.

*Disclaimer: This tool provides data for informational purposes based on NOLA's public datasets. Always exercise caution and respect local laws when doing field work.*