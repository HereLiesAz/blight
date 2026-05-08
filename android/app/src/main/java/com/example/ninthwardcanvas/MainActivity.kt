package com.example.ninthwardcanvas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var mapManager: MapManager
    private lateinit var dataFetcher: DataFetcher
    private lateinit var storageManager: StorageManager
    private lateinit var filterManager: FilterManager

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var camoUi: RelativeLayout
    private lateinit var qrModal: FrameLayout
    private lateinit var blightedModal: FrameLayout
    private lateinit var listModal: FrameLayout
    private lateinit var pinCatModal: FrameLayout

    private lateinit var syncStatus: TextView
    private lateinit var scrapeStatus: TextView

    private lateinit var databaseAdapter: DatabaseAdapter
    private lateinit var blightedAdapter: DatabaseAdapter
    private lateinit var pinCategoryAdapter: PinCategoryAdapter

    private lateinit var locationOverlay: MyLocationNewOverlay

    private var graffitiFilterMode = "all" // all, graffiti, clean
    private var colorMode = "status" // status, graffiti
    private var currentPhotoPath: String? = null

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_IMAGE_CAPTURE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        storageManager = StorageManager(this)
        dataFetcher = DataFetcher()
        filterManager = FilterManager()

        initViews()
        setupMap()
        setupListeners()
        requestPermissions()

        fetchData()
        fetchExtras()
    }

    private fun fetchExtras() {
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

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        mapView = findViewById(R.id.map)
        camoUi = findViewById(R.id.camo_ui)
        qrModal = findViewById(R.id.qr_modal)
        blightedModal = findViewById(R.id.blighted_modal)
        listModal = findViewById(R.id.list_modal)
        pinCatModal = findViewById(R.id.pin_cat_modal)
        syncStatus = findViewById(R.id.sync_status)
        scrapeStatus = findViewById(R.id.scrape_status)

        // HUD Buttons
        findViewById<Button>(R.id.btn_filters).setOnClickListener { drawerLayout.open() }
        findViewById<Button>(R.id.btn_graffiti_filter).setOnClickListener { cycleGraffitiFilter() }
        findViewById<Button>(R.id.btn_color_mode).setOnClickListener { cycleColorMode() }
        findViewById<Button>(R.id.btn_pins).setOnClickListener { openPinCategories() }
        findViewById<Button>(R.id.btn_list_blighted).setOnClickListener { showBlightedList() }
        findViewById<Button>(R.id.btn_list_view).setOnClickListener { showDatabaseList() }
        findViewById<Button>(R.id.btn_update_db).setOnClickListener { triggerScrape() }
        findViewById<Button>(R.id.btn_beam_intel).setOnClickListener { generateQR() }
        findViewById<Button>(R.id.btn_track_gps).setOnClickListener { toggleGps() }
        findViewById<Button>(R.id.btn_ghost).setOnClickListener { ghostProtocol() }

        findViewById<View>(R.id.camo_trigger).setOnClickListener { toggleCamo() }
        camoUi.setOnClickListener { toggleCamo() }
        findViewById<Button>(R.id.btn_qr_close).setOnClickListener { qrModal.visibility = View.GONE }
        findViewById<View>(R.id.btn_blighted_close).setOnClickListener { blightedModal.visibility = View.GONE }
        findViewById<Button>(R.id.btn_list_close).setOnClickListener { listModal.visibility = View.GONE }
        findViewById<Button>(R.id.btn_pin_cat_close).setOnClickListener { pinCatModal.visibility = View.GONE }

        // Setup RecyclerViews
        databaseAdapter = DatabaseAdapter(emptyList()) { item -> openRoute(item.lat, item.lng) }
        val recyclerDatabase = findViewById<RecyclerView>(R.id.recycler_database)
        recyclerDatabase.layoutManager = LinearLayoutManager(this)
        recyclerDatabase.adapter = databaseAdapter

        blightedAdapter = DatabaseAdapter(emptyList()) { item -> openRoute(item.lat, item.lng) }
        val recyclerBlighted = findViewById<RecyclerView>(R.id.recycler_blighted)
        recyclerBlighted.layoutManager = LinearLayoutManager(this)
        recyclerBlighted.adapter = blightedAdapter

        pinCategoryAdapter = PinCategoryAdapter(storageManager.loadPinCategories()) { cat ->
            deleteCategory(cat)
        }
        val recyclerPinCats = findViewById<RecyclerView>(R.id.recycler_pin_cats)
        recyclerPinCats.layoutManager = LinearLayoutManager(this)
        recyclerPinCats.adapter = pinCategoryAdapter

        findViewById<Button>(R.id.btn_add_cat).setOnClickListener {
            val emoji = findViewById<EditText>(R.id.edit_cat_emoji).text.toString().trim()
            val name = findViewById<EditText>(R.id.edit_cat_name).text.toString().trim()
            if (emoji.isNotEmpty() && name.isNotEmpty()) {
                val cats = storageManager.loadPinCategories().toMutableList()
                if (cats.none { it.name == name }) {
                    cats.add(PinCategory(emoji, name))
                    storageManager.savePinCategories(cats)
                    pinCategoryAdapter.updateData(cats)
                    findViewById<EditText>(R.id.edit_cat_emoji).setText("📍")
                    findViewById<EditText>(R.id.edit_cat_name).text.clear()
                    applyFilters()
                } else {
                    Toast.makeText(this, "Name already used", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Filter actions
        findViewById<Button>(R.id.btn_filter_reset).setOnClickListener {
            filterManager.applyPreset("all")
            applyFilters()
        }
        findViewById<Button>(R.id.btn_filter_apply).setOnClickListener {
            updateFilterStateFromUI()
            applyFilters()
            drawerLayout.close()
        }

        // Timeline
        val timeSlider = findViewById<SeekBar>(R.id.time_slider)
        val timeLabel = findViewById<TextView>(R.id.time_label)
        timeSlider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val monthsBack = 12 - progress
                filterManager.state.monthsBack = monthsBack
                timeLabel.text = if (monthsBack == 0) "⏳ Timeline: ALL TIME" else "⏳ Timeline: PAST $monthsBack MONTHS"
                if (fromUser) applyFilters()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupMap() {
        mapManager = MapManager(this, mapView)

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        locationOverlay.disableMyLocation()
        mapView.overlays.add(locationOverlay)

        mapView.addMapListener(object : MapListener {
            private val debounceMs = 500L
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            private var fetchRunnable: Runnable? = null

            private fun triggerDebouncedFetch() {
                fetchRunnable?.let { handler.removeCallbacks(it) }
                fetchRunnable = Runnable { fetchData() }
                handler.postDelayed(fetchRunnable!!, debounceMs)
            }

            override fun onScroll(event: ScrollEvent?): Boolean {
                triggerDebouncedFetch()
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                triggerDebouncedFetch()
                return false
            }
        })
    }

    private fun setupListeners() {
        val mReceive = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    val cats = storageManager.loadPinCategories()
                    val cat = cats.firstOrNull()?.name ?: "📍"
                    val newPin = UserPin(
                        id = "pin-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}",
                        lat = p.latitude,
                        lng = p.longitude,
                        category = cat,
                        title = "",
                        note = "",
                        created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                    )
                    val pins = storageManager.loadUserPins().toMutableList()
                    pins.add(newPin)
                    storageManager.saveUserPins(pins)
                    applyFilters()
                    return true
                }
                return false
            }
        }
        val overlayEvents = MapEventsOverlay(mReceive)
        mapView.overlays.add(0, overlayEvents)
    }

    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun fetchData() {
        findViewById<TextView>(R.id.loading_text).visibility = View.VISIBLE
        dataFetcher.fetchSectorData(mapView.boundingBox, { items ->
            findViewById<TextView>(R.id.loading_text).visibility = View.GONE

            // Merge data avoiding duplicates
            val existingIds = filterManager.masterData.map { it.caseno }.toSet()
            val newItems = items.filter { it.caseno !in existingIds }
            if (newItems.isNotEmpty()) {
                filterManager.masterData.addAll(newItems)
                filterManager.invalidateCache()
                applyFilters()
            }
        }, {
            findViewById<TextView>(R.id.loading_text).visibility = View.GONE
            Toast.makeText(this, "Fetch error: ${it.message}", Toast.LENGTH_SHORT).show()
        })
    }

    private fun applyFilters() {
        val filtered = filterManager.applyFilters(graffitiFilterMode)
        mapManager.clearMarkers()

        val heatList = mutableListOf<GeoPoint>()

        for (item in filtered) {
            val marker = mapManager.createPropertyMarker(item, colorMode)

            marker.snippet = "Status: ${item.prop.status}\nDeadline: ${item.prop.deadline}"
            marker.infoWindow = PropertyInfoWindow(mapView, item, storageManager, ::openRoute, ::dispatchTakePictureIntent)

            if (item.prop.status == "Guilty") mapManager.guiltyLayer.add(marker)
            else mapManager.uncommittedLayer.add(marker)

            heatList.add(GeoPoint(item.lat, item.lng))

            // Hotspots
            if ((item.prop.graffitiScore ?: 0.0) >= 0.8) {
                mapManager.hotspotLayer.add(mapManager.createEmojiMarker(item.lat, item.lng, "🎨", item.prop.address))
            }
        }

        mapManager.heatPoints = heatList

        // Render User Pins
        storageManager.loadUserPins().forEach { pin ->
            val cat = storageManager.loadPinCategories().find { it.name == pin.category }
            val emoji = cat?.emoji ?: "📍"
            val marker = mapManager.createEmojiMarker(pin.lat, pin.lng, emoji, pin.title)
            marker.infoWindow = PinInfoWindow(mapView, pin, storageManager, ::applyFilters) { pinId ->
                val pins = storageManager.loadUserPins().toMutableList()
                pins.removeAll { it.id == pinId }
                storageManager.saveUserPins(pins)
                applyFilters()
            }
            mapManager.userPinsLayer.add(marker)
        }

        // Auto layers
        val groups = filterManager.ensureClusterGroups()
        for (g in groups) {
            var sLat = 0.0; var sLng = 0.0
            for (idx in g) {
                sLat += filterManager.masterData[idx].lat
                sLng += filterManager.masterData[idx].lng
            }
            val lat = sLat / g.size
            val lng = sLng / g.size
            mapManager.clusterCentersLayer.add(mapManager.createEmojiMarker(lat, lng, "🎯", "Cluster Center"))
        }

        for (item in filterManager.masterData) {
            if (filterManager.getClusterCountFor(item.caseno) == 0) {
                 mapManager.outliersLayer.add(mapManager.createEmojiMarker(item.lat, item.lng, "⊙", "Solo Outlier"))
            }
        }

        mapView.invalidate()
    }

    private fun updateFilterStateFromUI() {
        val state = filterManager.state

        state.propertyTypes.clear()
        if (findViewById<Chip>(R.id.chip_residential).isChecked) state.propertyTypes.add("residential")
        if (findViewById<Chip>(R.id.chip_commercial).isChecked) state.propertyTypes.add("commercial")
        if (findViewById<Chip>(R.id.chip_mixed).isChecked) state.propertyTypes.add("mixed")
        if (findViewById<Chip>(R.id.chip_vacant).isChecked) state.propertyTypes.add("vacant")

        state.deadlineWindow = when (findViewById<ChipGroup>(R.id.chipgroup_deadline).checkedChipId) {
            R.id.chip_dl_lt7 -> "<7"
            R.id.chip_dl_7_14 -> "7-14"
            R.id.chip_dl_gt14 -> ">14"
            R.id.chip_dl_expired -> "expired"
            else -> "all"
        }

        state.hasStructure = when (findViewById<ChipGroup>(R.id.chipgroup_structure).checkedChipId) {
            R.id.chip_struct_yes -> "yes"
            R.id.chip_struct_no -> "no"
            else -> "both"
        }

        state.activeRehab = when (findViewById<ChipGroup>(R.id.chipgroup_rehab).checkedChipId) {
            R.id.chip_rehab_exclude -> "exclude"
            R.id.chip_rehab_only -> "only"
            else -> "all"
        }

        state.cluster = when (findViewById<ChipGroup>(R.id.chipgroup_cluster).checkedChipId) {
            R.id.chip_cluster_solo -> "solo"
            R.id.chip_cluster_cluster -> "cluster"
            else -> "all"
        }

        state.demoStatus = when (findViewById<ChipGroup>(R.id.chipgroup_demo).checkedChipId) {
            R.id.chip_demo_pending -> "pending"
            R.id.chip_demo_permitted -> "permitted"
            R.id.chip_demo_completed -> "completed"
            R.id.chip_demo_none -> "none"
            else -> "all"
        }

        state.daysBlightedMin = findViewById<SeekBar>(R.id.slider_days_blighted).progress
        state.permitsMin = findViewById<SeekBar>(R.id.slider_permits).progress
        state.repeatOnly = findViewById<CheckBox>(R.id.check_repeat_offender).isChecked
    }

    private fun cycleGraffitiFilter() {
        val btn = findViewById<Button>(R.id.btn_graffiti_filter)
        graffitiFilterMode = when(graffitiFilterMode) {
            "all" -> "graffiti"
            "graffiti" -> "clean"
            else -> "all"
        }
        btn.text = "🎨 Graffiti: ${graffitiFilterMode.replaceFirstChar { it.uppercase() }}"
        applyFilters()
    }

    private fun cycleColorMode() {
        val btn = findViewById<Button>(R.id.btn_color_mode)
        colorMode = if (colorMode == "status") "graffiti" else "status"
        btn.text = "🎨 Color: ${colorMode.replaceFirstChar { it.uppercase() }}"
        applyFilters()
    }

    private fun openPinCategories() {
        pinCatModal.visibility = View.VISIBLE
    }

    private fun showBlightedList() {
        val bounds = mapView.boundingBox
        val cutoff = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -filterManager.state.monthsBack) }.time
        val visibleBlighted = filterManager.masterData.filter { item ->
            val isGuilty = item.prop.status == "Guilty"
            val isVisible = bounds.contains(item.lat, item.lng)
            val isWithinTimeline = filterManager.state.monthsBack == 0 || item.date.after(cutoff)
            isGuilty && isVisible && isWithinTimeline
        }
        blightedAdapter.updateData(visibleBlighted)
        blightedModal.visibility = View.VISIBLE
    }

    private fun showDatabaseList() {
        databaseAdapter.updateData(filterManager.masterData)
        listModal.visibility = View.VISIBLE
    }

    private fun openRoute(lat: Double, lng: Double) {
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
    }

    private fun deleteCategory(cat: PinCategory) {
        val pins = storageManager.loadUserPins()
        val inUse = pins.filter { it.category == cat.name }
        val cats = storageManager.loadPinCategories().toMutableList()

        if (inUse.isNotEmpty()) {
            cats.removeAll { it.name == cat.name }
            if (cats.isEmpty()) {
                Toast.makeText(this, "Cannot delete last category while pins exist", Toast.LENGTH_SHORT).show()
                return
            }
            val fallback = cats[0].name
            val updatedPins = pins.map { if (it.category == cat.name) it.copy(category = fallback) else it }
            storageManager.saveUserPins(updatedPins)
            storageManager.savePinCategories(cats)
            applyFilters()
        } else {
            cats.removeAll { it.name == cat.name }
            storageManager.savePinCategories(cats)
        }
        pinCategoryAdapter.updateData(cats)
    }

    private fun triggerScrape() {
        scrapeStatus.visibility = View.VISIBLE
        scrapeStatus.text = "🔄 Triggering Update..."
        scrapeStatus.setBackgroundColor(Color.parseColor("#FFBF00"))
        scrapeStatus.setTextColor(Color.BLACK)

        dataFetcher.triggerScrape({
            scrapeStatus.text = "✅ Update Complete!"
            scrapeStatus.setBackgroundColor(Color.GREEN)
            filterManager.masterData.clear()
            fetchData()
            hideScrapeStatusDelayed()
        }, { err ->
            scrapeStatus.text = "❌ $err"
            scrapeStatus.setBackgroundColor(Color.RED)
            scrapeStatus.setTextColor(Color.WHITE)
            hideScrapeStatusDelayed()
        })
    }

    private fun hideScrapeStatusDelayed() {
        scrapeStatus.postDelayed({
            scrapeStatus.visibility = View.GONE
            scrapeStatus.setBackgroundColor(Color.parseColor("#FFBF00"))
            scrapeStatus.setTextColor(Color.BLACK)
        }, 5000)
    }

    private fun generateQR() {
        val notes = storageManager.getAllNotesAsMap()
        val pins = storageManager.loadUserPins()
        val cats = storageManager.loadPinCategories()

        if (notes.isEmpty() && pins.isEmpty()) {
            Toast.makeText(this, "No intel to beam.", Toast.LENGTH_SHORT).show()
            return
        }

        val json = JSONObject()
        val stashArray = JSONArray()
        notes.forEach { (k, v) ->
            val o = JSONObject()
            o.put("a", k)
            o.put("n", v)
            stashArray.put(o)
        }
        json.put("stash", stashArray)

        val pinsArray = JSONArray()
        pins.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("lat", p.lat)
            o.put("lng", p.lng)
            o.put("cat", p.category)
            o.put("t", p.title)
            o.put("n", p.note)
            pinsArray.put(o)
        }
        json.put("pins", pinsArray)

        val catsArray = JSONArray()
        cats.forEach { c ->
            val o = JSONObject()
            o.put("e", c.emoji)
            o.put("n", c.name)
            catsArray.put(o)
        }
        json.put("cats", catsArray)

        val payload = json.toString()
        val writer = QRCodeWriter()
        try {
            val bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, 256, 256)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            findViewById<ImageView>(R.id.qr_image).setImageBitmap(bitmap)
            qrModal.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(this, "Error generating QR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleGps() {
        val btn = findViewById<Button>(R.id.btn_track_gps)
        if (locationOverlay.isMyLocationEnabled) {
            locationOverlay.disableMyLocation()
            btn.setTextColor(Color.parseColor("#EEEEEE"))
        } else {
            locationOverlay.enableMyLocation()
            btn.setTextColor(Color.parseColor("#007bff"))
            mapView.controller.setZoom(18.0)
        }
    }

    private fun toggleCamo() {
        camoUi.visibility = if (camoUi.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun ghostProtocol() {
        storageManager.clearAll()
        if (locationOverlay.isMyLocationEnabled) locationOverlay.disableMyLocation()
        finish()
        startActivity(intent)
    }

    // --- Camera Capture Helpers (Used by InfoWindows ideally) ---
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    private var currentCaptureAddress: String? = null

    private fun dispatchTakePictureIntent(address: String) {
        currentCaptureAddress = address
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.also {
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) { null }
                photoFile?.also {
                    val photoURI: Uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", it)
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
            currentPhotoPath?.let { path ->
                currentCaptureAddress?.let { address ->
                    storageManager.saveImgPath(address, "file:$path")
                    Toast.makeText(this, "Optics saved", Toast.LENGTH_SHORT).show()
                }
            }
        }
        currentCaptureAddress = null
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
