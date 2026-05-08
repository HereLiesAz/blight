package com.example.ninthwardcanvas

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class FilterState(
    var monthsBack: Int = 0,
    var deadlineWindow: String = "all",
    var propertyTypes: MutableSet<String> = mutableSetOf("residential", "commercial", "mixed", "vacant"),
    var hasStructure: String = "both",
    var daysBlightedMin: Int = 0,
    var repeatOnly: Boolean = false,
    var activeRehab: String = "all",
    var cluster: String = "all",
    var demoStatus: String = "all",
    var permitsMin: Int = 0
)

class FilterManager {
    val state = FilterState()
    var masterData = mutableListOf<MapItem>()

    // Cluster calculation cache
    private var clusterCountCache: Map<String, Int>? = null
    private var clusterGroupsCache: List<List<Int>>? = null

    companion object {
        const val CLUSTER_RADIUS_M = 100.0
        const val MIN_CLUSTER_SIZE = 3

        fun parseSocrataDate(dateStr: String?): Date {
            if (dateStr == null) return Date()
            return try {
                if (dateStr.contains("T")) {
                    val p = dateStr.split("T")[0].split("-")
                    val cal = Calendar.getInstance()
                    cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 0, 0, 0)
                    cal.time
                } else if (dateStr.length >= 8 && !dateStr.contains("-")) {
                    val cal = Calendar.getInstance()
                    cal.set(dateStr.substring(0, 4).toInt(), dateStr.substring(4, 6).toInt() - 1, dateStr.substring(6, 8).toInt(), 0, 0, 0)
                    cal.time
                } else {
                    SimpleDateFormat("MM/dd/yyyy", Locale.US).parse(dateStr) ?: Date()
                }
            } catch (e: Exception) {
                Date()
            }
        }

        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val R = 6371000.0
            val toRad = { x: Double -> x * Math.PI / 180.0 }
            val dLat = toRad(lat2 - lat1)
            val dLng = toRad(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dLng / 2) * sin(dLng / 2)
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }

    fun invalidateCache() {
        clusterCountCache = null
        clusterGroupsCache = null
    }

    private fun propertyCategory(p: PropInfo): String {
        val desc = ((p.landUseDesc ?: "") + " " + (p.zoningDesc ?: "")).lowercase()
        if (p.hasStructure == false) return "vacant"
        if (desc.contains("commercial")) return "commercial"
        if (desc.contains("mixed")) return "mixed"
        return "residential"
    }

    private fun isActiveRehab(p: PropInfo): Boolean {
        if (p.permitStatus == null || p.permitFiling == null) return false
        if (p.permitStatus.lowercase() != "permit issued") return false
        val d = parseSocrataDate(p.permitFiling)
        val ageDays = (System.currentTimeMillis() - d.time) / 86400000.0
        return ageDays < 365 && "(?i)build|reno|repair".toRegex().containsMatchIn(p.permitType ?: "")
    }

    private fun inDeadlineWindow(p: PropInfo, win: String): Boolean {
        if (win == "all") return true
        if (p.deadline.isEmpty()) return false

        val deadlineDate = try {
            SimpleDateFormat("MM/dd/yyyy", Locale.US).parse(p.deadline)
        } catch (e: Exception) { null } ?: return false

        val days = (deadlineDate.time - System.currentTimeMillis()) / 86400000.0
        return when (win) {
            "expired" -> days < 0
            "<7" -> days in 0.0..7.0
            "7-14" -> days in 7.0..14.0
            ">14" -> days > 14
            else -> true
        }
    }

    private fun ensureClusterCounts() {
        if (clusterCountCache != null) return
        val cache = mutableMapOf<String, Int>()
        for (i in masterData.indices) {
            var count = 0
            for (j in masterData.indices) {
                if (i == j) continue
                if (haversineMeters(masterData[i].lat, masterData[i].lng, masterData[j].lat, masterData[j].lng) <= CLUSTER_RADIUS_M) {
                    count++
                }
            }
            cache[masterData[i].caseno] = count
        }
        clusterCountCache = cache
    }

    fun ensureClusterGroups(): List<List<Int>> {
        if (clusterGroupsCache != null) return clusterGroupsCache!!
        val visited = mutableSetOf<Int>()
        val groups = mutableListOf<List<Int>>()
        for (i in masterData.indices) {
            if (visited.contains(i)) continue
            val queue = mutableListOf(i)
            val group = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val k = queue.removeAt(0)
                if (visited.contains(k)) continue
                visited.add(k)
                group.add(k)
                for (j in masterData.indices) {
                    if (visited.contains(j)) continue
                    if (haversineMeters(masterData[k].lat, masterData[k].lng, masterData[j].lat, masterData[j].lng) <= CLUSTER_RADIUS_M) {
                        queue.add(j)
                    }
                }
            }
            if (group.size >= MIN_CLUSTER_SIZE) {
                groups.add(group)
            }
        }
        clusterGroupsCache = groups
        return groups
    }

    private fun inClusterBucket(item: MapItem, bucket: String): Boolean {
        if (bucket == "all") return true
        ensureClusterCounts()
        val n = clusterCountCache?.get(item.caseno) ?: 0
        if (bucket == "solo") return n == 0
        if (bucket == "cluster") return n >= 3
        return true
    }

    fun getClusterCountFor(caseno: String): Int {
        ensureClusterCounts()
        return clusterCountCache?.get(caseno) ?: 0
    }

    fun applyFilters(graffitiFilter: String): List<MapItem> {
        val cutoff = Calendar.getInstance().apply { add(Calendar.MONTH, -state.monthsBack) }.time

        return masterData.filter { item ->
            val p = item.prop
            if (state.monthsBack > 0 && item.date.before(cutoff)) return@filter false

            // Graffiti Filter logic
            val showByGraffiti = when (graffitiFilter) {
                "all" -> true
                "graffiti" -> (p.graffitiScore ?: 0.0) >= 0.5
                "clean" -> p.graffitiScore != null && p.graffitiScore < 0.5
                else -> true
            }
            if (!showByGraffiti) return@filter false

            if (!state.propertyTypes.contains(propertyCategory(p))) return@filter false

            if (state.hasStructure != "both") {
                val want = state.hasStructure == "yes"
                if ((p.hasStructure == true) != want) return@filter false
            }

            if ((p.daysUnderBlight ?: 0) < state.daysBlightedMin) return@filter false
            if (state.repeatOnly && (p.caseCount ?: 1) < 2) return@filter false

            if (state.activeRehab != "all") {
                val rehab = isActiveRehab(p)
                if (state.activeRehab == "exclude" && rehab) return@filter false
                if (state.activeRehab == "only" && !rehab) return@filter false
            }

            if (!inDeadlineWindow(p, state.deadlineWindow)) return@filter false
            if (!inClusterBucket(item, state.cluster)) return@filter false

            if (state.demoStatus != "all") {
                val ds = p.demolitionStatus ?: "none"
                if (ds != state.demoStatus) return@filter false
            }

            if ((p.permitCount365d ?: 0) < state.permitsMin) return@filter false

            true
        }
    }

    fun applyPreset(name: String) {
        state.monthsBack = state.monthsBack // Preserve time slider
        state.deadlineWindow = "all"
        state.propertyTypes = mutableSetOf("residential", "commercial", "mixed", "vacant")
        state.hasStructure = "both"
        state.daysBlightedMin = 0
        state.repeatOnly = false
        state.activeRehab = "all"
        state.cluster = "all"
        state.demoStatus = "all"
        state.permitsMin = 0

        when (name) {
            "fresh" -> {
                state.hasStructure = "yes"
                state.deadlineWindow = ">14"
                state.activeRehab = "exclude"
            }
            "cluster" -> state.cluster = "cluster"
            "solo" -> state.cluster = "solo"
            "repeat" -> state.repeatOnly = true
            "long-term" -> {
                state.daysBlightedMin = 365
                state.activeRehab = "exclude"
            }
            "expiring" -> state.deadlineWindow = "<7"
        }
    }
}
