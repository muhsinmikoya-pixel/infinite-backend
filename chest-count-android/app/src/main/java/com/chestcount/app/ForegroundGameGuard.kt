package com.chestcount.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Allows detection only while the actual Fortnite game package is the latest
 * resumed app. Usage access is user-granted in Android Settings.
 */
internal class ForegroundGameGuard(context: Context) {
    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var lastCheckedAt = 0L
    private var lastResumedPackage: String? = null
    private var cachedAccess = false
    private var accessCheckedAt = 0L

    @Synchronized fun hasUsageAccess(now: Long = System.currentTimeMillis()): Boolean {
        if (now - accessCheckedAt < ACCESS_CACHE_MS) return cachedAccess
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName
        )
        cachedAccess = mode == AppOpsManager.MODE_ALLOWED
        accessCheckedAt = now
        return cachedAccess
    }

    @Synchronized fun isFortniteForeground(now: Long = System.currentTimeMillis()): Boolean {
        if (!hasUsageAccess(now)) return false
        if (now - lastCheckedAt < FOREGROUND_CACHE_MS) {
            return lastResumedPackage in FORTNITE_PACKAGES
        }
        return try {
            // Load a broad history once, then only inspect a short overlapping window.
            val from = if (lastCheckedAt == 0L) now - INITIAL_LOOKBACK_MS
                else (lastCheckedAt - EVENT_OVERLAP_MS).coerceAtLeast(0L)
            val events = usageStats.queryEvents(from, now)
            val event = UsageEvents.Event()
            var latestTime = lastCheckedAt
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val resumed = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (resumed && event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    lastResumedPackage = event.packageName
                }
            }
            lastCheckedAt = now
            lastResumedPackage in FORTNITE_PACKAGES
        } catch (_: SecurityException) {
            lastCheckedAt = now
            false
        } catch (_: RuntimeException) {
            lastCheckedAt = now
            false
        }
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 24L * 60L * 60L * 1000L
        private const val EVENT_OVERLAP_MS = 2_000L
        private const val FOREGROUND_CACHE_MS = 1_000L
        private const val ACCESS_CACHE_MS = 3_000L
        private val FORTNITE_PACKAGES = setOf("com.epicgames.fortnite")
    }
}
