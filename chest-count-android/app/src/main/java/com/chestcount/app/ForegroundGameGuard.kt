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

    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isFortniteForeground(now: Long = System.currentTimeMillis()): Boolean {
        if (!hasUsageAccess()) return false
        return try {
            val events = usageStats.queryEvents(now - LOOKBACK_MS, now)
            val event = UsageEvents.Event()
            var latestTime = 0L
            var latestPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val resumed = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (resumed && event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                }
            }
            latestPackage in FORTNITE_PACKAGES && latestTime > 0L
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        private const val LOOKBACK_MS = 30_000L
        private val FORTNITE_PACKAGES = setOf("com.epicgames.fortnite")
    }
}
