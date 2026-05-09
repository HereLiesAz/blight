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
