package com.hereliesaz.redblightdistrict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.compose.rememberNavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.redblightdistrict.ui.Amber
import com.hereliesaz.redblightdistrict.ui.Blight
import com.hereliesaz.redblightdistrict.ui.CamoOverlay
import com.hereliesaz.redblightdistrict.ui.Cyan
import com.hereliesaz.redblightdistrict.ui.DataFreshnessBadge
import com.hereliesaz.redblightdistrict.ui.FiltersDrawerDialog
import com.hereliesaz.redblightdistrict.ui.MapHost
import com.hereliesaz.redblightdistrict.ui.PinCategoriesDialog
import com.hereliesaz.redblightdistrict.ui.PropertyListDialog
import com.hereliesaz.redblightdistrict.ui.QrDialog
import com.hereliesaz.redblightdistrict.ui.RedBlightDistrictTheme
import com.hereliesaz.redblightdistrict.ui.StatusPill
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var currentPhotoPath: String? = null
    private var currentCaptureAddress: String? = null

    // Modern AndroidX camera contract: hands the camera app a URI to write into
    // and returns a success boolean. Avoids the Android 11+ package-visibility
    // headaches around manual ACTION_IMAGE_CAPTURE + resolveActivity().
    val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val path = currentPhotoPath
            val address = currentCaptureAddress
            if (path != null && address != null) {
                onPictureSaved?.invoke(address, "file:$path")
            }
        }
        currentCaptureAddress = null
    }

    var onPictureSaved: ((String, String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        requestNeededPermissions()
        setContent {
            RedBlightDistrictTheme {
                BlightApp(activity = this)
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
        )
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", dir).apply {
            currentPhotoPath = absolutePath
        }
    }

    fun dispatchTakePictureIntent(address: String) {
        val file = try { createImageFile() } catch (e: IOException) { return }
        val uri: Uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
        currentCaptureAddress = address
        takePictureLauncher.launch(uri)
    }
}

/**
 * Root composable. Hosts the AzNavRail rail/menu, the osmdroid MapView, the
 * overlay badges, and every dialog/modal that the legacy XML UI exposed.
 */
@Composable
fun BlightApp(activity: MainActivity) {
    val navController = rememberNavController()

    // ---- Long-lived helpers (mirror the original Activity-scoped instances) ----
    val storageManager = remember { StorageManager(activity) }
    val dataFetcher = remember { DataFetcher() }
    val filterManager = remember { FilterManager() }
    val mapView = remember { MapView(activity) }
    val mapManager = remember { MapManager(activity, mapView) }

    // GPS overlay
    val locationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(activity), mapView).apply {
            disableMyLocation()
            mapView.overlays.add(this)
        }
    }

    // ---- UI / data state ----
    var graffitiFilterMode by remember { mutableStateOf("all") }
    var colorMode by remember { mutableStateOf("status") }
    var gpsOn by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showPinCats by remember { mutableStateOf(false) }
    var showBlightedList by remember { mutableStateOf(false) }
    var showDatabaseList by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var camoOn by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var scrapeStatus by remember { mutableStateOf<Pair<String, androidx.compose.ui.graphics.Color>?>(null) }
    // Bumped whenever masterData merges new rows; drives the badge's remember(dataRev).
    var dataRev by remember { mutableStateOf(0) }
    var pinCategories by remember { mutableStateOf(storageManager.loadPinCategories()) }

    // ALPR (DeFlock) cache, deduped by OSM id.
    val alprCache = remember { mutableMapOf<String, AlprPoint>() }
    // Mapillary thumbnail cache. Bitmaps live in an LruCache(64) to bound
    // memory; a separate set records "asked, no coverage" keys so the layer
    // never retries those centroids.
    val clusterThumbCache = remember { android.util.LruCache<String, Bitmap>(64) }
    val clusterThumbEmpty = remember { mutableSetOf<String>() }
    val clusterThumbInFlight = remember { mutableSetOf<String>() }

    // Debounced redraw — many thumbnails landing in quick succession collapse
    // into a single applyFilters() pass instead of one per download.
    val redrawHandler = remember { Handler(Looper.getMainLooper()) }
    val redrawRunnableRef = remember { object { var value: Runnable? = null } }
    // Debounced map-move handler — same Handler instance, but a separate one-slot
    // holder so the two cadences can't cancel each other.
    val moveRunnableRef = remember { object { var value: Runnable? = null } }

    val openRoute: (Double, Double) -> Unit = { lat, lng ->
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
    activity.onPictureSaved = { address, fileUri ->
        storageManager.saveImgPath(address, fileUri)
    }

    fun clusterThumbKey(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f,%.5f", lat, lng)

    // Forward-declared via a one-slot holder so applyFilters (above) can
    // schedule itself through the same name the helpers below use.
    val applyFiltersRef = remember { object { var value: (() -> Unit)? = null } }

    fun scheduleClusterRedraw() {
        redrawRunnableRef.value?.let { redrawHandler.removeCallbacks(it) }
        val r = Runnable { applyFiltersRef.value?.invoke() }
        redrawRunnableRef.value = r
        redrawHandler.postDelayed(r, 200L)
    }

    fun applyFilters() {
        val filtered = filterManager.applyFilters(graffitiFilterMode)
        mapManager.clearMarkers()
        val heat = mutableListOf<GeoPoint>()
        for (item in filtered) {
            val marker = mapManager.createPropertyMarker(item, colorMode)
            marker.snippet = "Status: ${item.prop.status}\nDeadline: ${item.prop.deadline}"
            marker.infoWindow = PropertyInfoWindow(mapView, item, storageManager, openRoute) { addr ->
                activity.dispatchTakePictureIntent(addr)
            }
            if (item.prop.status == "Guilty") mapManager.guiltyLayer.add(marker)
            else mapManager.uncommittedLayer.add(marker)
            heat.add(GeoPoint(item.lat, item.lng))
            if ((item.prop.graffitiScore ?: 0.0) >= 0.8) {
                mapManager.hotspotLayer.add(mapManager.createEmojiMarker(item.lat, item.lng, "🎨", item.prop.address))
            }
        }
        mapManager.heatPoints = heat

        // User pins
        for (pin in storageManager.loadUserPins()) {
            val cat = pinCategories.find { it.name == pin.category }
            val emoji = cat?.emoji ?: "📍"
            val m = mapManager.createEmojiMarker(pin.lat, pin.lng, emoji, pin.title)
            m.infoWindow = PinInfoWindow(mapView, pin, storageManager, { applyFiltersRef.value?.invoke() }) { pinId ->
                val pins = storageManager.loadUserPins().toMutableList()
                pins.removeAll { it.id == pinId }
                storageManager.saveUserPins(pins)
                applyFiltersRef.value?.invoke()
            }
            mapManager.userPinsLayer.add(m)
        }

        // Cluster centers (with optional Mapillary thumbnails)
        val viewBounds = mapView.boundingBox
        val groups = filterManager.ensureClusterGroups()
        for (g in groups) {
            var sLat = 0.0; var sLng = 0.0
            for (idx in g) {
                sLat += filterManager.masterData[idx].lat
                sLng += filterManager.masterData[idx].lng
            }
            val lat = sLat / g.size
            val lng = sLng / g.size
            val key = clusterThumbKey(lat, lng)
            val cachedThumb = clusterThumbCache[key]
            mapManager.clusterCentersLayer.add(mapManager.createClusterCenterMarker(lat, lng, cachedThumb))

            // Only fetch for centroids inside the current viewport, and only
            // when we have neither a cached bitmap nor a known-empty sentinel.
            if (cachedThumb == null
                && key !in clusterThumbEmpty
                && key !in clusterThumbInFlight
                && viewBounds.contains(lat, lng)
            ) {
                clusterThumbInFlight.add(key)
                dataFetcher.fetchNearestMapillaryThumb(lat, lng) { bmp ->
                    if (bmp != null) clusterThumbCache.put(key, bmp) else clusterThumbEmpty.add(key)
                    clusterThumbInFlight.remove(key)
                    if (bmp != null) scheduleClusterRedraw()
                }
            }
        }

        // Solo outliers
        for (item in filterManager.masterData) {
            if (filterManager.getClusterCountFor(item.caseno) == 0) {
                mapManager.outliersLayer.add(mapManager.createEmojiMarker(item.lat, item.lng, "⊙", "Solo Outlier"))
            }
        }

        // ALPR cameras
        for (alpr in alprCache.values) {
            mapManager.alprLayer.add(mapManager.createAlprMarker(alpr))
        }

        mapView.invalidate()
        dataRev++  // recompose badge / list dialogs that depend on masterData
    }
    applyFiltersRef.value = ::applyFilters

    fun fetchExtras() {
        dataFetcher.fetchDemolitions { rows ->
            for (d in rows) {
                mapManager.demolitionsLayer.add(mapManager.createEmojiMarker(d.lat, d.lng, "🏗️", "Demolition"))
            }
            mapView.invalidate()
        }
        dataFetcher.fetchOsmFeatures { rows ->
            for (d in rows) {
                mapManager.osmLayer.add(mapManager.createEmojiMarker(d.lat, d.lng, d.category ?: "🏚️", d.name ?: "OSM"))
            }
            mapView.invalidate()
        }
    }

    fun fetchAlprForCurrentView() {
        if (mapView.zoomLevelDouble < 11.0) return
        dataFetcher.fetchAlprPoints(mapView.boundingBox, { points ->
            var added = 0
            for (p in points) if (alprCache.put(p.id, p) == null) added++
            if (added > 0) applyFilters()
        }, { /* silent: ALPR layer is best-effort */ })
    }

    fun refreshClusterThumbnailsForViewport() {
        if (filterManager.masterData.isEmpty()) return
        val viewBounds = mapView.boundingBox
        val groups = filterManager.ensureClusterGroups()
        for (g in groups) {
            var sLat = 0.0; var sLng = 0.0
            for (idx in g) {
                sLat += filterManager.masterData[idx].lat
                sLng += filterManager.masterData[idx].lng
            }
            val lat = sLat / g.size
            val lng = sLng / g.size
            if (!viewBounds.contains(lat, lng)) continue
            val key = clusterThumbKey(lat, lng)
            if (clusterThumbCache[key] != null || key in clusterThumbEmpty || key in clusterThumbInFlight) continue
            clusterThumbInFlight.add(key)
            dataFetcher.fetchNearestMapillaryThumb(lat, lng) { bmp ->
                if (bmp != null) clusterThumbCache.put(key, bmp) else clusterThumbEmpty.add(key)
                clusterThumbInFlight.remove(key)
                if (bmp != null) scheduleClusterRedraw()
            }
        }
    }

    fun fetchData() {
        loading = true
        dataFetcher.fetchSectorData(mapView.boundingBox, { items ->
            loading = false
            val existing = filterManager.masterData.map { it.caseno }.toSet()
            val newItems = items.filter { it.caseno !in existing }
            if (newItems.isNotEmpty()) {
                filterManager.masterData.addAll(newItems)
                filterManager.invalidateCache()
                applyFilters()
            }
            dataRev++
        }, {
            loading = false
        })
        fetchAlprForCurrentView()
    }

    // Long-press on map → drop a user pin at that GeoPoint.
    DisposableEffect(Unit) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                val firstCat = pinCategories.firstOrNull()?.name ?: "📍"
                val newPin = UserPin(
                    id = "pin-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}",
                    lat = p.latitude,
                    lng = p.longitude,
                    category = firstCat,
                    title = "",
                    note = "",
                    created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                )
                val pins = storageManager.loadUserPins().toMutableList()
                pins.add(newPin)
                storageManager.saveUserPins(pins)
                applyFilters()
                return true
            }
        }
        val overlay = MapEventsOverlay(receiver)
        mapView.overlays.add(0, overlay)
        onDispose { mapView.overlays.remove(overlay) }
    }

    LaunchedEffect(Unit) {
        fetchData()
        fetchExtras()
    }

    // Debounced refetch on every map move.
    val onMapMoved: () -> Unit = {
        moveRunnableRef.value?.let { redrawHandler.removeCallbacks(it) }
        val r = Runnable {
            fetchData()
            refreshClusterThumbnailsForViewport()
        }
        moveRunnableRef.value = r
        redrawHandler.postDelayed(r, 500L)
    }

    // ---- AzNavRail-hosted layout ----
    AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
        azConfig(
            dockingSide = AzDockingSide.RIGHT,
            packButtons = true,
            displayAppName = true,
        )
        azTheme(activeColor = Amber)

        azRailItem(id = "filters", text = "FILTERS", content = Icons.Default.FilterAlt, onClick = { showFilters = true })
        azRailItem(id = "graffiti", text = "🎨 ${graffitiFilterMode.replaceFirstChar { it.uppercase() }}", onClick = {
            graffitiFilterMode = when (graffitiFilterMode) {
                "all" -> "graffiti"; "graffiti" -> "clean"; else -> "all"
            }
            applyFilters()
        })
        azRailItem(id = "color", text = "🎨 ${colorMode.replaceFirstChar { it.uppercase() }}", onClick = {
            colorMode = if (colorMode == "status") "graffiti" else "status"
            applyFilters()
        })
        azRailItem(id = "pins", text = "📂 Pins", content = Icons.Default.PushPin, onClick = { showPinCats = true })
        azRailItem(id = "blighted", text = "📋 BLIGHTED", onClick = { showBlightedList = true })
        azRailItem(id = "list", text = "📋 List", content = Icons.Default.Visibility, onClick = { showDatabaseList = true })
        azRailItem(id = "update", text = "🔄 Update", content = Icons.Default.Refresh, onClick = {
            scrapeStatus = "🔄 Triggering Update..." to Amber
            dataFetcher.triggerScrape({
                scrapeStatus = "✅ Update Complete!" to androidx.compose.ui.graphics.Color.Green
                filterManager.masterData.clear()
                fetchData()
                redrawHandler.postDelayed({ scrapeStatus = null }, 5000)
            }, { err ->
                scrapeStatus = "❌ $err" to Blight
                redrawHandler.postDelayed({ scrapeStatus = null }, 5000)
            })
        })
        azRailItem(id = "beam", text = "🕸️ Beam Intel", content = Icons.Default.QrCode, onClick = {
            qrBitmap = generateQrBitmap(storageManager)
            if (qrBitmap != null) showQr = true
        })
        azRailItem(
            id = "gps",
            text = "📍 GPS",
            content = Icons.Default.MyLocation,
            color = if (gpsOn) Cyan else null,
            onClick = {
                gpsOn = !gpsOn
                if (gpsOn) {
                    locationOverlay.enableMyLocation()
                    mapView.controller.setZoom(18.0)
                } else {
                    locationOverlay.disableMyLocation()
                }
            },
        )
        azMenuItem(id = "camo", text = "👁 Camo", content = Icons.Default.Visibility, onClick = { camoOn = true })
        azMenuItem(
            id = "ghost",
            text = "⚠️ GHOST",
            content = Icons.Default.Delete,
            color = Blight,
            onClick = {
                storageManager.clearAll()
                alprCache.clear()
                clusterThumbCache.evictAll()
                clusterThumbEmpty.clear()
                clusterThumbInFlight.clear()
                redrawRunnableRef.value?.let { redrawHandler.removeCallbacks(it) }
                moveRunnableRef.value?.let { redrawHandler.removeCallbacks(it) }
                if (locationOverlay.isMyLocationEnabled) locationOverlay.disableMyLocation()
                activity.finish()
                activity.startActivity(activity.intent)
            },
        )

        onscreen(alignment = Alignment.TopStart) {
            Box(modifier = Modifier.fillMaxSize()) {
                MapHost(mapView = mapView, onMapMoved = onMapMoved)

                // Freshness badge: recompute the max date only when dataRev
                // bumps (i.e. when new data merges in), not on every recomposition.
                val newestDate = remember(dataRev) {
                    filterManager.masterData.maxByOrNull { it.date.time }?.date
                }
                DataFreshnessBadge(
                    newest = newestDate,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                )

                if (loading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(androidx.compose.ui.graphics.Color(0xCC0B0B0B))
                            .padding(12.dp),
                    ) { Text("Scanning Sector...", color = Amber) }
                }

                scrapeStatus?.let { (text, bg) ->
                    StatusPill(text = text, color = bg, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
                }

                // Camo trigger (invisible 80dp box top-left)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(80.dp)
                        .clickable { camoOn = true },
                )
            }
        }
    }

    // ---- Dialogs ----
    if (showFilters) {
        FiltersDrawerDialog(
            filterManager = filterManager,
            onDismiss = { showFilters = false },
            onApply = { applyFilters() },
            onReset = { applyFilters() },
        )
    }
    if (showPinCats) {
        PinCategoriesDialog(
            categories = pinCategories,
            onAdd = { cat ->
                if (pinCategories.none { it.name == cat.name }) {
                    val updated = pinCategories.toMutableList().apply { add(cat) }
                    storageManager.savePinCategories(updated)
                    pinCategories = updated
                    applyFilters()
                }
            },
            onDelete = { cat ->
                val pins = storageManager.loadUserPins()
                val inUse = pins.filter { it.category == cat.name }
                val updated = pinCategories.toMutableList().apply { removeAll { it.name == cat.name } }
                if (inUse.isNotEmpty() && updated.isEmpty()) return@PinCategoriesDialog  // keep last
                if (inUse.isNotEmpty()) {
                    val fallback = updated[0].name
                    storageManager.saveUserPins(pins.map { if (it.category == cat.name) it.copy(category = fallback) else it })
                }
                storageManager.savePinCategories(updated)
                pinCategories = updated
                applyFilters()
            },
            onDismiss = { showPinCats = false },
        )
    }
    if (showBlightedList) {
        val visible = filterManager.masterData.filter {
            it.prop.status == "Guilty" && mapView.boundingBox.contains(it.lat, it.lng)
        }
        PropertyListDialog(
            title = "Blighted Properties in View",
            items = visible,
            onDismiss = { showBlightedList = false },
            onRouteClick = { lat, lng -> openRoute(lat, lng) },
        )
    }
    if (showDatabaseList) {
        PropertyListDialog(
            title = "Database Records",
            items = filterManager.masterData,
            onDismiss = { showDatabaseList = false },
            onRouteClick = { lat, lng -> openRoute(lat, lng) },
        )
    }
    if (showQr) {
        QrDialog(bitmap = qrBitmap, onDismiss = { showQr = false })
    }
    if (camoOn) {
        CamoOverlay(onDismiss = { camoOn = false })
    }
}

private fun generateQrBitmap(storageManager: StorageManager): Bitmap? {
    val notes = storageManager.getAllNotesAsMap()
    val pins = storageManager.loadUserPins()
    val cats = storageManager.loadPinCategories()
    if (notes.isEmpty() && pins.isEmpty()) return null
    val json = JSONObject()
    val stashArray = JSONArray()
    notes.forEach { (k, v) ->
        val o = JSONObject(); o.put("a", k); o.put("n", v); stashArray.put(o)
    }
    json.put("stash", stashArray)
    val pinsArray = JSONArray()
    pins.forEach { p ->
        val o = JSONObject()
        o.put("id", p.id); o.put("lat", p.lat); o.put("lng", p.lng)
        o.put("cat", p.category); o.put("t", p.title); o.put("n", p.note)
        pinsArray.put(o)
    }
    json.put("pins", pinsArray)
    val catsArray = JSONArray()
    cats.forEach { c ->
        val o = JSONObject(); o.put("e", c.emoji); o.put("n", c.name); catsArray.put(o)
    }
    json.put("cats", catsArray)
    return try {
        val matrix = QRCodeWriter().encode(json.toString(), BarcodeFormat.QR_CODE, 256, 256)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) AColor.BLACK else AColor.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}
