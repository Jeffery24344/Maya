package com.jeffery.assistant.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import com.jeffery.assistant.memory.NovaMemoryStore
import com.jeffery.assistant.memory.UsageTracker
import java.util.Calendar
import java.util.regex.Pattern
import kotlin.random.Random

sealed class AutomationResult {
    data class Handled(val confirmation: String) : AutomationResult()
    data object NotAnAutomationCommand : AutomationResult()
    data class Failed(val reason: String) : AutomationResult()
}

/**
 * Lightweight, fully offline command parser. Runs BEFORE handing text to the LLM:
 * if a phrase clearly matches an action (alarm, open app, timer), it's executed directly
 * and skips the model entirely — faster and more reliable than asking an LLM to emit
 * structured tool calls for simple deterministic tasks.
 *
 * Extend the `patterns` list to add more automations (e.g. toggling wifi, sending a
 * text via Intent.ACTION_SENDTO, launching Tasker/Shortcuts, etc.).
 */
class AutomationEngine(
    private val context: Context,
    private val memoryStore: NovaMemoryStore,
    private val usageTracker: UsageTracker
) {

    private val alarmPattern = Pattern.compile(
        "\\bset (an? )?alarm\\b.*?(\\d{1,2})(:(\\d{2}))?\\s*(am|pm)?",
        Pattern.CASE_INSENSITIVE
    )
    private val timerPattern = Pattern.compile(
        "\\bset (an? )?timer\\b.*?(\\d+)\\s*(second|minute|hour)s?",
        Pattern.CASE_INSENSITIVE
    )
    private val openAppPattern = Pattern.compile(
        "\\bopen\\s+([a-zA-Z0-9 ]+)\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val reminderPattern = Pattern.compile(
        "\\bremind me to (.+?) in (\\d+)\\s*(second|minute|hour)s?\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val volumePattern = Pattern.compile(
        "\\b(turn|set) (the )?volume (up|down|to (\\d{1,3})\\s*%?)\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val wifiPattern = Pattern.compile("\\b(turn (on|off) )?wi[- ]?fi\\b", Pattern.CASE_INSENSITIVE)
    private val bluetoothPattern = Pattern.compile("\\bbluetooth\\b", Pattern.CASE_INSENSITIVE)
    private val dndPattern = Pattern.compile("\\bdo not disturb\\b|\\bdnd\\b", Pattern.CASE_INSENSITIVE)
    private val forgetEverythingPattern = Pattern.compile("\\bforget everything\\b", Pattern.CASE_INSENSITIVE)
    private val forgetPattern = Pattern.compile("\\bforget (that )?(.+)", Pattern.CASE_INSENSITIVE)
    private val rememberPattern = Pattern.compile("\\bremember (that )?(.+)", Pattern.CASE_INSENSITIVE)

    fun tryHandle(command: String): AutomationResult {
        alarmPattern.matcher(command).let { m ->
            if (m.find()) return handleAlarm(m)
        }
        timerPattern.matcher(command).let { m ->
            if (m.find()) return handleTimer(m)
        }
        reminderPattern.matcher(command).let { m ->
            if (m.find()) return handleReminder(m)
        }
        volumePattern.matcher(command).let { m ->
            if (m.find()) return handleVolume(m)
        }
        if (wifiPattern.matcher(command).find()) return openPanel(Settings.Panel.ACTION_WIFI, "Wi-Fi")
        if (bluetoothPattern.matcher(command).find()) {
            return openPanel(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth", isPanel = false)
        }
        if (dndPattern.matcher(command).find()) {
            return openPanel(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb", isPanel = false)
        }
        // Remember/forget are loose catch-alls (they match on the word appearing anywhere),
        // so they're checked after the more specific automations above to avoid false
        // positives like "remind me to forget my umbrella" being read as a forget-command.
        if (forgetEverythingPattern.matcher(command).find()) {
            memoryStore.clearAll()
            return AutomationResult.Handled("Done — I've cleared everything I remembered about you.")
        }
        forgetPattern.matcher(command).let { m ->
            if (m.find()) {
                val target = m.group(2)?.trim().orEmpty()
                return if (memoryStore.removeMatching(target)) {
                    AutomationResult.Handled("Forgotten.")
                } else {
                    AutomationResult.Failed("I didn't have anything matching that stored.")
                }
            }
        }
        rememberPattern.matcher(command).let { m ->
            if (m.find()) {
                val fact = m.group(2)?.trim().orEmpty()
                memoryStore.addFact(fact)
                return AutomationResult.Handled("Got it — I'll remember that.")
            }
        }
        openAppPattern.matcher(command).let { m ->
            if (m.find()) return handleOpenApp(m.group(1)?.trim().orEmpty())
        }
        return AutomationResult.NotAnAutomationCommand
    }

    private fun handleReminder(m: java.util.regex.Matcher): AutomationResult {
        val text = m.group(1)?.trim().orEmpty()
        val amount = m.group(2)?.toIntOrNull() ?: return AutomationResult.Failed("Couldn't parse when to remind you.")
        val unit = m.group(3)?.lowercase() ?: "minute"
        val millis = when (unit) {
            "second" -> amount * 1000L
            "hour" -> amount * 3600_000L
            else -> amount * 60_000L
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val reminderId = Random.nextInt()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_REMINDER_TEXT, text)
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminderId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + millis

        return try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            usageTracker.logEvent("reminder")
            AutomationResult.Handled("Got it — I'll remind you to $text in $amount $unit${if (amount != 1) "s" else ""}.")
        } catch (e: Exception) {
            AutomationResult.Failed("Couldn't schedule that reminder: ${e.message}")
        }
    }

    private fun handleVolume(m: java.util.regex.Matcher): AutomationResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = m.group(3)?.lowercase()
        val exactPercent = m.group(4)?.toIntOrNull()

        return try {
            when {
                exactPercent != null -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (max * (exactPercent.coerceIn(0, 100) / 100f)).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    usageTracker.logEvent("volume")
                    AutomationResult.Handled("Volume set to $exactPercent%.")
                }
                direction?.startsWith("up") == true -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                    usageTracker.logEvent("volume")
                    AutomationResult.Handled("Turned the volume up.")
                }
                direction?.startsWith("down") == true -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                    usageTracker.logEvent("volume")
                    AutomationResult.Handled("Turned the volume down.")
                }
                else -> AutomationResult.NotAnAutomationCommand
            }
        } catch (e: Exception) {
            AutomationResult.Failed("Couldn't change the volume: ${e.message}")
        }
    }

    /**
     * Android 10+ blocks apps from silently toggling Wi-Fi/Bluetooth/DND for privacy
     * reasons — the best we can do is open the relevant settings panel for a one-tap
     * toggle rather than flipping it ourselves.
     */
    private fun openPanel(action: String, label: String, isPanel: Boolean = true): AutomationResult {
        return try {
            val intent = Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            AutomationResult.Handled("Opened $label settings — Android needs a manual tap here.")
        } catch (e: Exception) {
            AutomationResult.Failed("Couldn't open $label settings: ${e.message}")
        }
    }

    private fun handleAlarm(m: java.util.regex.Matcher): AutomationResult {
        val hour = m.group(2)?.toIntOrNull() ?: return AutomationResult.Failed("Couldn't parse the time.")
        val minute = m.group(4)?.toIntOrNull() ?: 0
        val ampm = m.group(5)?.lowercase()

        var hour24 = hour
        if (ampm == "pm" && hour < 12) hour24 += 12
        if (ampm == "am" && hour == 12) hour24 = 0

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            usageTracker.logEvent("alarm")
            AutomationResult.Handled("Alarm set for ${"%02d:%02d".format(hour24, minute)}.")
        } catch (e: Exception) {
            AutomationResult.Failed("Couldn't set the alarm: ${e.message}")
        }
    }

    private fun handleTimer(m: java.util.regex.Matcher): AutomationResult {
        val amount = m.group(2)?.toIntOrNull() ?: return AutomationResult.Failed("Couldn't parse the duration.")
        val unit = m.group(3)?.lowercase() ?: "minute"
        val seconds = when (unit) {
            "second" -> amount
            "hour" -> amount * 3600
            else -> amount * 60
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            usageTracker.logEvent("timer")
            AutomationResult.Handled("Timer set for $amount $unit${if (amount != 1) "s" else ""}.")
        } catch (e: Exception) {
            AutomationResult.Failed("Couldn't set the timer: ${e.message}")
        }
    }

    private fun handleOpenApp(appName: String): AutomationResult {
        if (appName.isBlank()) return AutomationResult.NotAnAutomationCommand
        val pm = context.packageManager
        val match = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .firstOrNull { pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true) }
            ?: pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .firstOrNull { pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true) }

        return if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                usageTracker.logEvent("open_app")
                AutomationResult.Handled("Opening ${pm.getApplicationLabel(match)}.")
            } else {
                AutomationResult.Failed("Found $appName but it has no launchable activity.")
            }
        } else {
            AutomationResult.Failed("Couldn't find an app matching \"$appName\".")
        }
    }
}
