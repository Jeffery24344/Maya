package com.jeffery.assistant.presence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimization (Doze) is the single most common reason background
 * work like daily check-ins or journal entries silently stops firing — far more often
 * than a bug in the WorkManager scheduling itself. This requests an exemption so the
 * OS stops deferring/killing this app's background work to save battery.
 *
 * Some OEMs (Xiaomi/MIUI, Samsung, Huawei, OnePlus, etc.) layer their own additional
 * battery managers on top of stock Android and may still restrict the app even after
 * this exemption is granted — those typically need an extra per-brand toggle (often
 * called "autostart" or "no restrictions") in their own battery/app settings, which
 * this can't reach directly.
 */
object BatteryOptimizationHelper {

    fun isExempt(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestExemption(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
