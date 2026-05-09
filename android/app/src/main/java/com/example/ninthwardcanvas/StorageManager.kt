package com.example.ninthwardcanvas

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StorageManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("NinthWardCanvasPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val debounceJobs = mutableMapOf<String, Job>()

    companion object {
        val DEFAULT_PIN_CATEGORIES = listOf(
            PinCategory("🎯", "Target"),
            PinCategory("👁️", "Scout"),
            PinCategory("🎨", "Hit"),
            PinCategory("📷", "Photographed"),
            PinCategory("✅", "Done"),
            PinCategory("🚫", "Skip")
        )
    }

    fun loadPinCategories(): List<PinCategory> {
        val raw = prefs.getString("pin_categories", null) ?: return DEFAULT_PIN_CATEGORIES
        return try {
            val type = object : TypeToken<List<PinCategory>>() {}.type
            val parsed: List<PinCategory> = gson.fromJson(raw, type)
            if (parsed.isNotEmpty()) parsed else DEFAULT_PIN_CATEGORIES
        } catch (e: Exception) {
            DEFAULT_PIN_CATEGORIES
        }
    }

    fun savePinCategories(cats: List<PinCategory>) {
        prefs.edit().putString("pin_categories", gson.toJson(cats)).apply()
    }

    fun loadUserPins(): List<UserPin> {
        val raw = prefs.getString("user_pins", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<UserPin>>() {}.type
            gson.fromJson(raw, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveUserPins(pins: List<UserPin>) {
        prefs.edit().putString("user_pins", gson.toJson(pins)).apply()
    }

    fun getNote(address: String): String {
        val b64 = prefs.getString("txt_$address", null) ?: return ""
        return try {
            String(Base64.decode(b64, Base64.DEFAULT))
        } catch (e: Exception) {
            ""
        }
    }

    fun saveNote(address: String, note: String) {
        val key = "txt_$address"
        debounceJobs[key]?.cancel()
        debounceJobs[key] = scope.launch {
            delay(500)
            val b64 = Base64.encodeToString(note.toByteArray(), Base64.NO_WRAP)
            prefs.edit().putString(key, b64).commit()
        }
    }

    fun getImgPath(address: String): String {
        return prefs.getString("img_$address", "") ?: ""
    }

    fun saveImgPath(address: String, b64OrPath: String) {
        prefs.edit().putString("img_$address", b64OrPath).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun getAllNotesAsMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("txt_") && value is String) {
                try {
                    val decoded = String(Base64.decode(value, Base64.DEFAULT))
                    map[key.removePrefix("txt_")] = decoded
                } catch (e: Exception) {}
            }
        }
        return map
    }
}
