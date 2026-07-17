package org.levimc.launcher.core.keys

import android.util.Log
import org.json.JSONObject
import java.io.File

object HiddenOfferTracker {
    private const val TRACK_FILE = "hidden_offer_track.json"

    private fun trackFile(filesDir: File): File {
        val dir = File(filesDir, "layouts")
        dir.mkdirs()
        return File(dir, TRACK_FILE)
    }

    fun record(filesDir: File, fileName: String, vararg sourceUrls: String) {
        val file = trackFile(filesDir)

        val map = if (file.exists()) {
            JSONObject(file.readText())
        } else {
            JSONObject()
        }

        val urlsArray = org.json.JSONArray()
        sourceUrls.forEach { urlsArray.put(it) }
        map.put(fileName, urlsArray)

        file.writeText(map.toString())
    }

    fun getAll(filesDir: File): Map<String, List<String>> {
        val file = trackFile(filesDir)

        if (!file.exists()) {
            return emptyMap()
        }

        val obj = JSONObject(file.readText())
        return obj.keys().asSequence().associateWith { key ->
            val urlsArray = obj.getJSONArray(key)
            (0 until urlsArray.length()).map { urlsArray.getString(it) }
        }
    }

    fun deleteAll(filesDir: File): Boolean {
        val trackFile = trackFile(filesDir)
        if (!trackFile.exists()) {
            return true
        }

        return try {
            val map = getAll(filesDir)

            val layoutDir = File(filesDir, "layouts")
            map.keys.forEach { fileName ->
                File(layoutDir, fileName).delete()
            }

            trackFile.delete()
        } catch (e: Exception) {
            Log.e("HiddenOfferTracker", "Error during cleanup", e)
            false
        }
    }
}