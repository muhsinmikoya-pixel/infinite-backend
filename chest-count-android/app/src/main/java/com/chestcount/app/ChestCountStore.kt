package com.chestcount.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local-only session and overlay preferences. No network or analytics. */
internal class ChestCountStore(context: Context) {
    private val prefs = context.getSharedPreferences("chest_count_local", Context.MODE_PRIVATE)

    data class Session(
        val startedAt: Long,
        val endedAt: Long,
        val detected: Int,
        val manual: Int
    ) {
        val total: Int get() = (detected + manual).coerceAtLeast(0)
        val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
    }

    @Synchronized fun beginSession(now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_STARTED, now)
            .putInt(KEY_DETECTED, 0)
            .putInt(KEY_MANUAL, 0)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    @Synchronized fun isSessionActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)
    @Synchronized fun detectedCount(): Int = prefs.getInt(KEY_DETECTED, 0)
    @Synchronized fun manualCount(): Int = prefs.getInt(KEY_MANUAL, 0)
    @Synchronized fun currentCount(): Int = (detectedCount() + manualCount()).coerceAtLeast(0)

    @Synchronized fun addDetected() {
        if (isSessionActive()) prefs.edit().putInt(KEY_DETECTED, detectedCount() + 1).apply()
    }

    @Synchronized fun adjustManual(delta: Int) {
        if (isSessionActive()) {
            prefs.edit().putInt(KEY_MANUAL, (manualCount() + delta).coerceAtLeast(-detectedCount())).apply()
        }
    }

    @Synchronized fun finishSession(now: Long = System.currentTimeMillis()) {
        if (!isSessionActive()) return
        val started = prefs.getLong(KEY_STARTED, now)
        val item = JSONObject()
            .put("startedAt", started)
            .put("endedAt", now)
            .put("detected", detectedCount())
            .put("manual", manualCount())
        val history = try { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        history.put(item)
        val trimmed = JSONArray()
        val first = (history.length() - MAX_HISTORY).coerceAtLeast(0)
        for (i in first until history.length()) trimmed.put(history.getJSONObject(i))
        prefs.edit()
            .putString(KEY_HISTORY, trimmed.toString())
            .putBoolean(KEY_ACTIVE, false)
            .apply()
    }

    @Synchronized fun recentSessions(): List<Session> {
        val history = try { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in history.length() - 1 downTo 0) {
                val item = history.optJSONObject(i) ?: continue
                add(Session(
                    item.optLong("startedAt"),
                    item.optLong("endedAt"),
                    item.optInt("detected"),
                    item.optInt("manual")
                ))
            }
        }
    }

    fun overlayX(): Int = prefs.getInt(KEY_X, 12)
    fun overlayY(): Int = prefs.getInt(KEY_Y, 82)
    fun saveOverlayPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_X, x.coerceAtLeast(0)).putInt(KEY_Y, y.coerceAtLeast(0)).apply()
    }

    companion object {
        private const val KEY_STARTED = "session_started"
        private const val KEY_DETECTED = "session_detected"
        private const val KEY_MANUAL = "session_manual"
        private const val KEY_ACTIVE = "session_active"
        private const val KEY_HISTORY = "session_history"
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val MAX_HISTORY = 30
    }
}
