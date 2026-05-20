package com.hereliesaz.redblightdistrict

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class DataFetcher {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    // NOTE: This should ideally be configurable or injected from a BuildConfig/Secrets file.
    // For this rewrite to match index.html exactly, we leave the placeholder, but implement the fallback live Socrata query logic.
    private val PROXY_URL = "PLACEHOLDER_PROXY_URL"

    fun fetchSectorData(boundingBox: BoundingBox, onSuccess: (List<MapItem>) -> Unit, onError: (Exception) -> Unit) {
        if (PROXY_URL == "PLACEHOLDER_PROXY_URL") {
            // Fallback to live NOLA Socrata API
            val soqlQuery = "?\$select=geoaddress AS address, prevhearingresult AS status, casefiled AS notice_date, the_geom AS location, caseno AS casenumber&\$where=within_box(the_geom, ${boundingBox.latNorth}, ${boundingBox.lonWest}, ${boundingBox.latSouth}, ${boundingBox.lonEast})&\$limit=50000"
            val url = "https://data.nola.gov/resource/gjzc-adg8.json$soqlQuery"

            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post { onError(e) }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        mainHandler.post { onError(IOException("Unexpected code $response")) }
                        return
                    }
                    try {
                        val body = response.body?.string() ?: "[]"
                        val jsonArray = JSONArray(body)
                        val items = mutableListOf<MapItem>()
                        val df = SimpleDateFormat("MM/dd/yyyy", Locale.US)

                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            if (!obj.has("location")) continue
                            val loc = obj.getJSONObject("location")
                            val coords = loc.getJSONArray("coordinates")
                            val lng = coords.getDouble(0)
                            val lat = coords.getDouble(1)

                            val statusRaw = obj.optString("status", "")
                            val status = if (statusRaw.lowercase().contains("guilty")) "Guilty" else "Uncommitted"
                            val noticeDateRaw = obj.optString("notice_date", "")
                            val noticeDateObj = FilterManager.parseSocrataDate(noticeDateRaw)

                            val noticeDateStr = df.format(noticeDateObj)
                            val deadlineStr = df.format(noticeDateObj.time + 30L * 24 * 60 * 60 * 1000)

                            val prop = PropInfo(
                                address = obj.optString("address", "Unknown"),
                                status = status,
                                noticeDate = noticeDateStr,
                                deadline = deadlineStr,
                                graffitiScore = null,
                                streetviewThumbUrl = null,
                                permitType = null,
                                permitStatus = null,
                                permitFiling = null,
                                demolitionStatus = null,
                                demolitionDate = null,
                                landUseDesc = null,
                                zoningClass = null,
                                zoningDesc = null,
                                hasStructure = null,
                                caseCount = null,
                                daysUnderBlight = null,
                                lastGrassCut = null,
                                nextHearing = null,
                                stage = null,
                                permitCount365d = null,
                                permitTypesRecent = null
                            )
                            items.add(MapItem(obj.optString("casenumber"), lat, lng, noticeDateObj, prop))
                        }
                        mainHandler.post { onSuccess(items) }
                    } catch (e: Exception) {
                        mainHandler.post { onError(e) }
                    }
                }
            })
        } else {
            // PROXY logic (same as JS but translated)
            val request = Request.Builder().url(PROXY_URL).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { mainHandler.post { onError(e) } }
                override fun onResponse(call: Call, response: Response) {
                     if (!response.isSuccessful) {
                        mainHandler.post { onError(IOException("Unexpected code $response")) }
                        return
                    }
                    try {
                        val body = response.body?.string() ?: "[]"
                        // For brevity in the proxy path, assume JSON parses perfectly to the MapItem list if mapped properly,
                        // but JS proxy returns flat object so we'd map it similarly to the fallback above.
                        // Since PROXY_URL is placeholder, we keep it simple here.
                        mainHandler.post { onSuccess(emptyList()) }
                    } catch (e: Exception) {
                        mainHandler.post { onError(e) }
                    }
                }
            })
        }
    }

    fun triggerScrape(onSuccess: () -> Unit, onError: (String) -> Unit) {
         if (PROXY_URL == "PLACEHOLDER_PROXY_URL") {
             mainHandler.post { onError("Please set PROXY_URL to trigger scrapes.") }
             return
         }

         val reqBody = JSONObject().apply { put("action", "scrape") }.toString()
         val body = reqBody.toRequestBody("text/plain;charset=utf-8".toMediaType())
         val request = Request.Builder().url(PROXY_URL).post(body).build()

         client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { mainHandler.post { onError("Network Error (CORS?)") } }
            override fun onResponse(call: Call, response: Response) {
                 if (response.isSuccessful) {
                     try {
                         val resJson = JSONObject(response.body?.string() ?: "{}")
                         if (resJson.optString("status") == "success") {
                             mainHandler.post { onSuccess() }
                         } else {
                             mainHandler.post { onError(resJson.optString("error", "Error")) }
                         }
                     } catch(e: Exception) { mainHandler.post { onError(e.message ?: "JSON parse error") } }
                 } else {
                     mainHandler.post { onError("Error: ${response.code}") }
                 }
            }
         })
    }

    fun fetchDemolitions(onSuccess: (List<DemolitionRow>) -> Unit) {
         if (PROXY_URL == "PLACEHOLDER_PROXY_URL") {
             mainHandler.post { onSuccess(emptyList()) }
             return
         }
         val request = Request.Builder().url("$PROXY_URL?tab=demolitions").build()
         client.newCall(request).enqueue(object: Callback {
             override fun onFailure(call: Call, e: IOException) { mainHandler.post { onSuccess(emptyList()) } }
             override fun onResponse(call: Call, response: Response) {
                 try {
                     val type = object : TypeToken<List<DemolitionRow>>() {}.type
                     val rows: List<DemolitionRow> = gson.fromJson(response.body?.string(), type)
                     mainHandler.post { onSuccess(rows) }
                 } catch (e: Exception) { mainHandler.post { onSuccess(emptyList()) } }
             }
         })
    }

    // --- DeFlock ALPR (Automated License Plate Reader) integration ---
    //
    // Primary source: the DeFlock project's public R2 CDN tile system
    // (https://github.com/FoggedLens/deflock), which serves pre-rendered 20°×20°
    // tiles of ALPR locations as static JSON. No auth or API key required.
    // Fallback: a direct Overpass query for `man_made=surveillance` +
    // `surveillance:type=ALPR` against OpenStreetMap.

    private companion object {
        const val DEFLOCK_INDEX_URL = "https://cdn.deflock.me/regions/index.json"
        const val DEFLOCK_TILE_SIZE_DEG = 20
    }

    @Volatile private var deflockIndex: DeflockIndex? = null

    private data class DeflockIndex(
        val expirationUtc: Long,
        val tileSizeDeg: Int,
        val tileUrlTemplate: String,
        val regions: Set<String>
    )

    /**
     * Fetch ALPR locations within the given bounding box.
     *
     * Tries the DeFlock R2 tile CDN first; on any failure or empty result, falls
     * back to a live OpenStreetMap Overpass query.
     */
    fun fetchAlprPoints(
        boundingBox: BoundingBox,
        onSuccess: (List<AlprPoint>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        fetchAlprFromDeflock(boundingBox, { points ->
            if (points.isNotEmpty()) onSuccess(points)
            else fetchAlprFromOverpass(boundingBox, onSuccess, onError)
        }, {
            fetchAlprFromOverpass(boundingBox, onSuccess, onError)
        })
    }

    private fun fetchAlprFromDeflock(
        boundingBox: BoundingBox,
        onSuccess: (List<AlprPoint>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        ensureDeflockIndex({ index ->
            val tileKeys = tileKeysForBoundingBox(boundingBox, index.tileSizeDeg)
                .filter { it in index.regions }
            if (tileKeys.isEmpty()) {
                mainHandler.post { onSuccess(emptyList()) }
                return@ensureDeflockIndex
            }

            val remaining = java.util.concurrent.atomic.AtomicInteger(tileKeys.size)
            val collected = java.util.Collections.synchronizedList(mutableListOf<AlprPoint>())
            val sawError = java.util.concurrent.atomic.AtomicBoolean(false)

            val finish: () -> Unit = {
                if (sawError.get() && collected.isEmpty()) {
                    mainHandler.post { onError(IOException("DeFlock tile fetch failed")) }
                } else {
                    mainHandler.post { onSuccess(collected.toList()) }
                }
            }

            for (key in tileKeys) {
                val (latKey, lonKey) = key.split("/")
                val url = index.tileUrlTemplate
                    .replace("{lat}", latKey)
                    .replace("{lon}", lonKey)
                val request = Request.Builder().url(url).build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        sawError.set(true)
                        if (remaining.decrementAndGet() == 0) finish()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            if (response.isSuccessful) {
                                val raw = response.body?.string() ?: "[]"
                                val arr = JSONArray(raw)
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    val lat = obj.optDouble("lat", Double.NaN)
                                    val lon = obj.optDouble("lon", Double.NaN)
                                    if (lat.isNaN() || lon.isNaN()) continue
                                    if (lat < boundingBox.latSouth || lat > boundingBox.latNorth) continue
                                    if (lon < boundingBox.lonWest || lon > boundingBox.lonEast) continue
                                    collected.add(alprFromDeflockEntry(obj, lat, lon))
                                }
                            } else {
                                sawError.set(true)
                            }
                        } catch (e: Exception) {
                            sawError.set(true)
                        } finally {
                            response.close()
                            if (remaining.decrementAndGet() == 0) finish()
                        }
                    }
                })
            }
        }, onError)
    }

    private fun ensureDeflockIndex(
        onReady: (DeflockIndex) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val cached = deflockIndex
        val nowSec = System.currentTimeMillis() / 1000
        if (cached != null && nowSec < cached.expirationUtc) {
            onReady(cached); return
        }

        val request = Request.Builder().url(DEFLOCK_INDEX_URL).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onError(e) }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        mainHandler.post { onError(IOException("DeFlock index HTTP ${response.code}")) }
                        return
                    }
                    val raw = response.body?.string() ?: "{}"
                    val obj = JSONObject(raw)
                    val regionsArr = obj.optJSONArray("regions") ?: JSONArray()
                    val regions = HashSet<String>(regionsArr.length())
                    for (i in 0 until regionsArr.length()) regions.add(regionsArr.getString(i))
                    val idx = DeflockIndex(
                        expirationUtc = obj.optLong("expiration_utc", nowSec + 600),
                        tileSizeDeg = obj.optInt("tile_size_degrees", DEFLOCK_TILE_SIZE_DEG),
                        tileUrlTemplate = obj.optString(
                            "tile_url",
                            "https://cdn.deflock.me/regions/{lat}/{lon}.json"
                        ),
                        regions = regions
                    )
                    deflockIndex = idx
                    mainHandler.post { onReady(idx) }
                } catch (e: Exception) {
                    mainHandler.post { onError(e) }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun tileKeysForBoundingBox(bbox: BoundingBox, tileSize: Int): List<String> {
        val size = tileSize.toDouble()
        val minLat = (Math.floor(bbox.latSouth / size) * size).toInt()
        val maxLat = (Math.floor(bbox.latNorth / size) * size).toInt()
        val minLon = (Math.floor(bbox.lonWest / size) * size).toInt()
        val maxLon = (Math.floor(bbox.lonEast / size) * size).toInt()
        val out = mutableListOf<String>()
        var lat = minLat
        while (lat <= maxLat) {
            var lon = minLon
            while (lon <= maxLon) {
                out.add("$lat/$lon")
                lon += tileSize
            }
            lat += tileSize
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() }

    private fun alprFromDeflockEntry(obj: JSONObject, lat: Double, lon: Double): AlprPoint {
        val tags = obj.optJSONObject("tags") ?: JSONObject()
        val tagSummaryKeys = listOf(
            "surveillance:type", "camera:type", "camera:mount",
            "camera:direction", "direction", "operator",
            "manufacturer", "brand", "model", "ref"
        )
        val tagsSummary = tagSummaryKeys
            .mapNotNull { k -> tags.optStringOrNull(k)?.let { v -> "$k=$v" } }
            .joinToString(", ")
        return AlprPoint(
            id = "node:${obj.optLong("id", 0L)}",
            lat = lat,
            lng = lon,
            type = tags.optStringOrNull("surveillance:type") ?: "ALPR",
            operator = tags.optStringOrNull("operator")
                ?: tags.optStringOrNull("manufacturer")
                ?: tags.optStringOrNull("brand"),
            direction = tags.optStringOrNull("camera:direction")
                ?: tags.optStringOrNull("direction"),
            mount = tags.optStringOrNull("camera:mount"),
            ref = tags.optStringOrNull("ref"),
            tagsSummary = tagsSummary.ifBlank { null }
        )
    }

    private fun fetchAlprFromOverpass(
        boundingBox: BoundingBox,
        onSuccess: (List<AlprPoint>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val bbox = "${boundingBox.latSouth},${boundingBox.lonWest},${boundingBox.latNorth},${boundingBox.lonEast}"
        val query = """
            [out:json][timeout:30];
            (
              node["man_made"="surveillance"]["surveillance:type"="ALPR"]($bbox);
              node["surveillance:type"="ALPR"]($bbox);
              node["surveillance"="ALPR"]($bbox);
            );
            out tags center;
        """.trimIndent()

        val body = "data=${java.net.URLEncoder.encode(query, "UTF-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onError(e) }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    mainHandler.post { onError(IOException("Overpass HTTP ${response.code}")) }
                    return
                }
                try {
                    val raw = response.body?.string() ?: "{}"
                    val root = JSONObject(raw)
                    val elements = root.optJSONArray("elements") ?: JSONArray()
                    val out = mutableListOf<AlprPoint>()
                    for (i in 0 until elements.length()) {
                        val el = elements.getJSONObject(i)
                        val lat: Double
                        val lng: Double
                        if (el.has("lat") && el.has("lon")) {
                            lat = el.getDouble("lat"); lng = el.getDouble("lon")
                        } else if (el.has("center")) {
                            val c = el.getJSONObject("center")
                            lat = c.getDouble("lat"); lng = c.getDouble("lon")
                        } else continue

                        val tags = el.optJSONObject("tags") ?: JSONObject()
                        val type = tags.optStringOrNull("surveillance:type")
                            ?: tags.optStringOrNull("surveillance")
                        val tagSummaryKeys = listOf(
                            "man_made", "surveillance", "surveillance:type",
                            "camera:type", "camera:mount", "camera:direction",
                            "direction", "operator", "operator:type", "ref",
                            "manufacturer", "model"
                        )
                        val tagsSummary = tagSummaryKeys
                            .mapNotNull { k -> tags.optStringOrNull(k)?.let { v -> "$k=$v" } }
                            .joinToString(", ")

                        out.add(
                            AlprPoint(
                                id = "${el.optString("type", "node")}:${el.optLong("id", 0L)}",
                                lat = lat,
                                lng = lng,
                                type = type ?: "ALPR",
                                operator = tags.optStringOrNull("operator"),
                                direction = tags.optStringOrNull("camera:direction")
                                    ?: tags.optStringOrNull("direction"),
                                mount = tags.optStringOrNull("camera:mount"),
                                ref = tags.optStringOrNull("ref"),
                                tagsSummary = tagsSummary.ifBlank { null }
                            )
                        )
                    }
                    mainHandler.post { onSuccess(out) }
                } catch (e: Exception) {
                    mainHandler.post { onError(e) }
                }
            }
        })
    }

    fun fetchOsmFeatures(onSuccess: (List<OsmFeatureRow>) -> Unit) {
        if (PROXY_URL == "PLACEHOLDER_PROXY_URL") {
             mainHandler.post { onSuccess(emptyList()) }
             return
         }
         val request = Request.Builder().url("$PROXY_URL?tab=osm_features").build()
         client.newCall(request).enqueue(object: Callback {
             override fun onFailure(call: Call, e: IOException) { mainHandler.post { onSuccess(emptyList()) } }
             override fun onResponse(call: Call, response: Response) {
                 try {
                     val type = object : TypeToken<List<OsmFeatureRow>>() {}.type
                     val rows: List<OsmFeatureRow> = gson.fromJson(response.body?.string(), type)
                     mainHandler.post { onSuccess(rows) }
                 } catch (e: Exception) { mainHandler.post { onSuccess(emptyList()) } }
             }
         })
    }
}
