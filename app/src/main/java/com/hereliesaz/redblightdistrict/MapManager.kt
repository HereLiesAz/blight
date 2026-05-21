package com.hereliesaz.redblightdistrict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import org.osmdroid.api.IGeoPoint
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

class MapManager(private val context: Context, private val mapView: MapView) {

    val guiltyLayer = FolderOverlay().apply { name = "<font color='#ff0000'>Blighted</font>" }
    val uncommittedLayer = FolderOverlay().apply { name = "<font color='#FFBF00'>Uncommitted</font>" }
    val userPinsLayer = FolderOverlay().apply { name = "📍 My pins" }
    val hotspotLayer = FolderOverlay().apply { name = "🎨 Hotspots (auto)" }
    val demolitionsLayer = FolderOverlay().apply { name = "🏗️ Demolitions" }
    val clusterCentersLayer = FolderOverlay().apply { name = "🎯 Cluster centers (auto)" }
    val outliersLayer = FolderOverlay().apply { name = "⊙ Solo outliers (auto)" }
    val osmLayer = FolderOverlay().apply { name = "🏚️ OSM features (auto)" }
    val alprLayer = FolderOverlay().apply { name = "📷 ALPR cameras (DeFlock)" }

    var heatPoints = listOf<GeoPoint>()
    val heatLayer = object : Overlay() {
        private val paint = Paint().apply {
            style = Paint.Style.FILL
            alpha = 150 // Adjust transparency
        }
        private val point = Point()

        override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
            if (canvas == null || mapView == null || shadow || heatPoints.isEmpty()) return
            val projection = mapView.projection
            val zoomLevel = projection.zoomLevel.toFloat()
            val radius = 50f * zoomLevel / 15f // Scale with zoom

            val bounds = mapView.boundingBox

            for (geoPoint in heatPoints) {
                // Optimize drawing by checking bounds
                if (!bounds.contains(geoPoint.latitude, geoPoint.longitude)) continue

                projection.toPixels(geoPoint, point)
                val gradient = RadialGradient(
                    point.x.toFloat(), point.y.toFloat(), radius,
                    intArrayOf(Color.argb(200, 255, 0, 0), Color.argb(100, 255, 255, 0), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = gradient
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius, paint)
            }
        }
    }

    init {
        // Set up CartoDB Dark Matter tile source
        val darkMatter = object : OnlineTileSourceBase(
            "CartoDB_Dark",
            1, 20, 256, ".png",
            arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return "$baseUrl${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}.png"
            }
        }

        mapView.setTileSource(darkMatter)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(29.965, -90.007))

        // Add layers to map
        mapView.overlays.add(heatLayer)
        mapView.overlays.add(guiltyLayer)
        mapView.overlays.add(uncommittedLayer)
        mapView.overlays.add(userPinsLayer)
        mapView.overlays.add(hotspotLayer)
        mapView.overlays.add(demolitionsLayer)
        mapView.overlays.add(clusterCentersLayer)
        mapView.overlays.add(outliersLayer)
        mapView.overlays.add(osmLayer)
        mapView.overlays.add(alprLayer)
    }

    fun clearMarkers() {
        guiltyLayer.items.clear()
        uncommittedLayer.items.clear()
        userPinsLayer.items.clear()
        hotspotLayer.items.clear()
        demolitionsLayer.items.clear()
        clusterCentersLayer.items.clear()
        outliersLayer.items.clear()
        osmLayer.items.clear()
        alprLayer.items.clear()
        heatPoints = emptyList()
        mapView.invalidate()
    }

    fun createAlprMarker(point: AlprPoint): Marker {
        val marker = Marker(mapView)
        marker.position = GeoPoint(point.lat, point.lng)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        val drawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_camera)?.mutate()
        if (drawable != null) {
            drawable.setTint(Color.parseColor("#00E5FF"))
            marker.icon = drawable
        }

        val title = buildString {
            append("📷 ")
            append(point.type ?: "ALPR")
            point.operator?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        }
        marker.title = title
        marker.snippet = buildString {
            point.direction?.takeIf { it.isNotBlank() }?.let { append("Direction: ").append(it).append('\n') }
            point.mount?.takeIf { it.isNotBlank() }?.let { append("Mount: ").append(it).append('\n') }
            point.ref?.takeIf { it.isNotBlank() }?.let { append("Ref: ").append(it).append('\n') }
            point.tagsSummary?.let { append(it) }
        }.trim()
        return marker
    }

    fun createPropertyMarker(item: MapItem, colorMode: String): Marker {
        val marker = Marker(mapView)
        marker.position = GeoPoint(item.lat, item.lng)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

        // Marker Drawable (circle)
        val drawable = ContextCompat.getDrawable(context, android.R.drawable.presence_online)?.mutate()
        if (drawable != null) {
            val color = if (colorMode == "graffiti") {
                if (item.prop.graffitiScore == null) Color.parseColor("#777777")
                else {
                    val r = (255 * item.prop.graffitiScore).toInt()
                    val b = (255 * (1 - item.prop.graffitiScore)).toInt()
                    Color.rgb(r, 80, b)
                }
            } else {
                if (item.prop.status == "Guilty") Color.parseColor("#FF0000") else Color.parseColor("#FFBF00")
            }
            drawable.setTint(color)
            marker.icon = drawable
        }

        marker.title = item.prop.address
        return marker
    }

    /**
     * Per-thumbnail cache of the composited cluster-center BitmapDrawable, so
     * pan/zoom-driven redraws don't reallocate the composite bitmap on every
     * applyFilters() call. Weakly keyed on the source Bitmap so entries
     * disappear automatically when the caller (MainActivity's thumbnail cache)
     * lets a bitmap go.
     */
    private val clusterDrawableCache: MutableMap<Bitmap, BitmapDrawable> =
        java.util.WeakHashMap()

    /**
     * Cluster-center marker. When [thumbnail] is non-null, composites the
     * Mapillary street-view image (red-bordered, 96x64 dp) above the standard
     * 🎯 pin glyph; otherwise falls back to a plain pin like [createEmojiMarker].
     */
    fun createClusterCenterMarker(lat: Double, lng: Double, thumbnail: Bitmap?): Marker {
        if (thumbnail == null) {
            return createEmojiMarker(lat, lng, "🎯", "Cluster Center")
        }

        val cached = clusterDrawableCache[thumbnail]
        if (cached != null) {
            val marker = Marker(mapView)
            marker.position = GeoPoint(lat, lng)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = cached
            marker.title = "🎯 Cluster Center"
            return marker
        }

        val density = context.resources.displayMetrics.density
        val thumbW = (96 * density).roundToInt()
        val thumbH = (64 * density).roundToInt()
        val border = (2 * density).roundToInt()
        val gap = (8 * density).roundToInt()
        val pinH = (32 * density).roundToInt()
        val pinW = pinH

        val totalW = maxOf(thumbW, pinW)
        val totalH = thumbH + gap + pinH

        val out = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Thumbnail with red border, top-center
        val thumbLeft = (totalW - thumbW) / 2f
        val thumbRect = RectF(thumbLeft, 0f, thumbLeft + thumbW, thumbH.toFloat())
        val src = Rect(0, 0, thumbnail.width, thumbnail.height)
        val innerDst = RectF(
            thumbRect.left + border, thumbRect.top + border,
            thumbRect.right - border, thumbRect.bottom - border
        )
        canvas.drawBitmap(thumbnail, src, innerDst, Paint(Paint.FILTER_BITMAP_FLAG))
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = border.toFloat()
            color = Color.parseColor("#FF3366")
        }
        canvas.drawRect(thumbRect, borderPaint)

        // Tinted stock pin, bottom-center, matching createEmojiMarker's glyph.
        val pinDrawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_myplaces)?.mutate()
        if (pinDrawable != null) {
            pinDrawable.setTint(Color.parseColor("#FFBF00"))
            val left = (totalW - pinW) / 2
            val top = thumbH + gap
            pinDrawable.setBounds(left, top, left + pinW, top + pinH)
            pinDrawable.draw(canvas)
        }

        val drawable = BitmapDrawable(context.resources, out)
        clusterDrawableCache[thumbnail] = drawable

        val marker = Marker(mapView)
        marker.position = GeoPoint(lat, lng)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.icon = drawable
        marker.title = "🎯 Cluster Center"
        return marker
    }

    fun createEmojiMarker(lat: Double, lng: Double, emoji: String, title: String): Marker {
        val marker = Marker(mapView)
        marker.position = GeoPoint(lat, lng)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        val drawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_myplaces)?.mutate()
        if (drawable != null) {
            drawable.setTint(Color.parseColor("#FFBF00"))
            marker.icon = drawable
        }

        marker.title = "$emoji $title"
        return marker
    }
}
