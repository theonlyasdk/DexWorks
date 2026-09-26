package com.asdk.tools.dexworks

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the last known installed-app list so the browse screen can render real
 * content immediately on launch instead of blocking on PackageManager.
 *
 * Enumerating installed packages forces the framework to parse 400+ APK manifests,
 * and resolving each app's label loads that app's resources. That work is
 * unavoidable but it does not need to happen before the list can be drawn, so the
 * previous result is shown first and refreshed in the background.
 */
object AppListCache {

    private const val TAG = "AppListCache"
    private const val FILE_NAME = "app_list_cache.json"
    private const val TIMING_FILE = "app_load_timings.txt"

    fun load(context: Context): List<AppItem>? {
        val file = File(context.filesDir, FILE_NAME)
        // length() is already 0 for a path that does not exist, so the extra
        // exists() stat is pure overhead on the cold start path.
        if (file.length() == 0L) return null
        return try {
            val array = JSONArray(file.readText())
            val out = ArrayList<AppItem>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                out.add(
                    AppItem(
                        name = o.optString("name"),
                        packageName = o.optString("packageName"),
                        versionName = o.optString("versionName"),
                        versionCode = o.optLong("versionCode"),
                        sizeBytes = o.optLong("sizeBytes"),
                        isSystemApp = o.optBoolean("isSystemApp"),
                        sourceDir = o.optString("sourceDir"),
                        icon = null
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "cache unreadable, ignoring: ${e.message}")
            null
        }
    }

    fun save(context: Context, apps: List<AppItem>) {
        try {
            val array = JSONArray()
            for (app in apps) {
                array.put(
                    JSONObject().apply {
                        put("name", app.name)
                        put("packageName", app.packageName)
                        put("versionName", app.versionName)
                        put("versionCode", app.versionCode)
                        put("sizeBytes", app.sizeBytes)
                        put("isSystemApp", app.isSystemApp)
                        put("sourceDir", app.sourceDir)
                    }
                )
            }
            val file = File(context.filesDir, FILE_NAME)
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            // Serialise once: this used to re-serialise the whole 481 item JSON
            // up to three times.
            val json = array.toString()
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                file.writeText(json)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed: ${e.message}")
        }
    }

    /** Debug-only sink, because this ROM filters app logs out of logcat. */
    fun recordTiming(context: Context, line: String) {
        try {
            File(context.filesDir, TIMING_FILE).appendText(line + "\n")
        } catch (e: Exception) {
            // ignore
        }
    }
}
