package com.jeffery.assistant.presence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.jeffery.assistant.MainActivity
import com.jeffery.assistant.memory.MoodStore

/**
 * Keeps Nova "present" in the background: a persistent low-priority notification
 * (so you always know she's running, and can tap it to instantly start listening),
 * plus an optional floating bubble avatar over other apps if you've granted the
 * overlay permission. Neither of these makes her always-listening — tapping either
 * one launches the app and starts a single listening turn, same as tapping the mic
 * button would.
 */
class NovaPresenceService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: NovaBubbleView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (OverlayPermissionHelper.hasPermission(this)) {
            addBubble()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground notification content is refreshed each start in case mood changed.
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
    }

    private fun buildNotification(): Notification {
        val channelId = "nova_presence"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Nova presence", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shows that Nova is running in the background"
                }
            )
        }

        val mood = MoodStore(applicationContext).currentMoodLabel()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Nova")
            .setContentText("Feeling $mood — tap to talk")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(tapPendingIntent)
            .build()
    }

    private fun addBubble() {
        if (bubbleView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sizePx = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        val view = NovaBubbleView(this)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val tapIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
                        }
                        startActivity(tapIntent)
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(view, params)
        bubbleView = view
    }

    private fun removeBubble() {
        bubbleView?.let {
            it.stopAnimating()
            windowManager?.removeView(it)
        }
        bubbleView = null
    }

    companion object {
        private const val NOTIFICATION_ID = 9002

        fun start(context: Context) {
            val intent = Intent(context, NovaPresenceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NovaPresenceService::class.java))
        }
    }
}
