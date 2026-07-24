package com.jeffery.assistant.presence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The floating bubble needs "draw over other apps" — another special permission
 * Android only lets the user grant manually (like Usage Access), no runtime dialog.
 */
object OverlayPermissionHelper {

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun openSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
