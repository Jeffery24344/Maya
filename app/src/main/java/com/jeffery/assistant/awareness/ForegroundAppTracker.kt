package com.jeffery.assistant.awareness

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * Lets Nova see which app is currently in the foreground, and which apps you've used
 * most today. This needs the "Usage access" special permission, which Android only
 * lets the user grant manually (no runtime dialog) — call [hasPermission] to check,
 * and [openSettings] to send the user to the right screen if it's off.
 */
class ForegroundAppTracker(private val context: Context) {

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings() {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    /** Best-effort label of whatever app was most recently in the foreground, if permission is granted. */
    fun currentForegroundAppLabel(): String? {
        if (!hasPermission()) return null
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - TEN_MINUTES_MILLIS
        val events = usageStatsManager.queryEvents(start, end)

        var lastPackage: String? = null
        var lastTimestamp = 0L
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.timeStamp > lastTimestamp
            ) {
                lastTimestamp = event.timeStamp
                lastPackage = event.packageName
            }
        }
        return lastPackage?.let { labelForPackage(it) }
    }

    /** Top apps by foreground time so far today, most-used first. */
    fun topAppsToday(limit: Int = 3): List<Pair<String, Long>> {
        if (!hasPermission()) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        return stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)
            .mapNotNull { stat ->
                labelForPackage(stat.packageName)?.let { label -> label to stat.totalTimeInForeground }
            }
    }

    private fun labelForPackage(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    companion object {
        private const val TEN_MINUTES_MILLIS = 10 * 60 * 1000L
    }
}
