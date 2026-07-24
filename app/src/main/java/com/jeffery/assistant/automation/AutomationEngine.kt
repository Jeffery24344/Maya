package com.jeffery.assistant.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.jeffery.assistant.memory.FolderSandbox
import com.jeffery.assistant.memory.GoalStore
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
    private val usageTracker: UsageTracker,
    private val goalStore: GoalStore,
    private val folderSandbox: FolderSandbox
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
    private val callPattern = Pattern.compile("\\bcall\\s+([a-zA-Z' -]+)", Pattern.CASE_INSENSITIVE)
    private val textPattern = Pattern.compile(
        "\\btext\\s+([a-zA-Z' -]+?)\\s+(?:saying|that says|:)\\s+(.+)",
        Pattern.CASE_INSENSITIVE
    )
    private val recentTextsPattern = Pattern.compile(
        "\\b(any new texts|check my texts|recent texts|recent messages)\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val calendarTomorrowPattern = Pattern.compile(
        "\\bwhat(?:'s| is| do i have) .*\\btomorrow\\b|\\btomorrow\\b.*\\bcalendar\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val calendarTodayPattern = Pattern.compile(
        "\\bwhat(?:'s| is) on my calendar\\b|\\bwhat do i have today\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val addEventPattern = Pattern.compile(
        "\\b(?:add|schedule) (?:an? )?event\\s+(.+)",
        Pattern.CASE_INSENSITIVE
    )
    private val photoCountPattern = Pattern.compile("\\bhow many photos\\b", Pattern.CASE_INSENSITIVE)

    // --- Goals ---
    private val setGoalPattern = Pattern.compile(
        "\\bset a goal (?:to |that )?(.+?)(?:\\s+with steps?[:\\s]+(.+))?$",
        Pattern.CASE_INSENSITIVE
    )
    private val listGoalsPattern = Pattern.compile("\\blist (?:my )?goals\\b", Pattern.CASE_INSENSITIVE)
    private val goalProgressPattern = Pattern.compile(
        "\\b(?:mark|update|check off) (.+?) (?:step )?(.+?) (?:as )?done\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val completeGoalPattern = Pattern.compile(
        "\\b(?:mark|finish|complete) goal (.+?) (?:as )?done\\b",
        Pattern.CASE_INSENSITIVE
    )

    // --- Utility tools ---
    private val convertPattern = Pattern.compile(
        "\\bconvert ([\\d.]+)\\s*([a-zA-Z]+) to ([a-zA-Z]+)\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val passwordPattern = Pattern.compile(
        "\\b(?:generate|make) (?:a )?password(?:\\s+(\\d+)\\s*characters?)?(\\s+no symbols)?\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val daysUntilPattern = Pattern.compile("\\bdays? until\\s+(.+)", Pattern.CASE_INSENSITIVE)
    private val dicePattern = Pattern.compile(
        "\\broll(?:\\s+(\\d+))?\\s*(?:dice|d(\\d+))\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val coinPattern = Pattern.compile("\\bflip a coin\\b", Pattern.CASE_INSENSITIVE)
    private val systemStatsPattern = Pattern.compile(
        "\\b(?:system stats|how much (?:battery|storage|ram|memory))\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val weatherPattern = Pattern.compile("\\bwhat(?:'s| is) the weather\\b|\\bweather like\\b", Pattern.CASE_INSENSITIVE)

    // --- Sandboxed folders ---
    private val listFoldersPattern = Pattern.compile("\\blist (?:my )?folders\\b", Pattern.CASE_INSENSITIVE)
    private val browseFolderPattern = Pattern.compile("\\bwhat'?s in (?:my )?(.+?) folder\\b", Pattern.CASE_INSENSITIVE)
    private val readFilePattern = Pattern.compile("\\bread (.+?) from (?:my )?(.+?)(?: folder)?$", Pattern.CASE_INSENSITIVE)
    private val writeFilePattern = Pattern.compile(
        "\\bin (?:my )?(.+?) folder,? (?:create|write) a file called (.+?) (?:saying|with|containing)\\s+(.+)",
        Pattern.CASE_INSENSITIVE
    )

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
        textPattern.matcher(command).let { m ->
            if (m.find()) return handleText(m)
        }
        callPattern.matcher(command).let { m ->
            if (m.find()) return handleCall(m)
        }
        if (recentTextsPattern.matcher(command).find()) return handleRecentTexts()
        if (calendarTomorrowPattern.matcher(command).find()) return handleCalendarQuery(daysFromToday = 1, dayLabel = "tomorrow")
        if (calendarTodayPattern.matcher(command).find()) return handleCalendarQuery(daysFromToday = 0, dayLabel = "today")
        addEventPattern.matcher(command).let { m ->
            if (m.find()) return handleAddEvent(m.group(1)?.trim().orEmpty())
        }
        if (photoCountPattern.matcher(command).find()) return handlePhotoCount()

        completeGoalPattern.matcher(command).let { m ->
            if (m.find()) return handleCompleteGoal(m.group(1)?.trim().orEmpty())
        }
        goalProgressPattern.matcher(command).let { m ->
            if (m.find()) return handleGoalProgress(m.group(1)?.trim().orEmpty(), m.group(2)?.trim().orEmpty())
        }
        if (listGoalsPattern.matcher(command).find()) return handleListGoals()
        setGoalPattern.matcher(command).let { m ->
            if (m.find()) return handleSetGoal(m.group(1)?.trim().orEmpty(), m.group(2)?.trim().orEmpty())
        }

        convertPattern.matcher(command).let { m ->
            if (m.find()) return handleConvert(m)
        }
        passwordPattern.matcher(command).let { m ->
            if (m.find()) return handlePassword(m)
        }
        daysUntilPattern.matcher(command).let { m ->
            if (m.find()) return AutomationResult.Handled(UtilityTools.daysUntil(m.group(1).orEmpty()))
        }
        dicePattern.matcher(command).let { m ->
            if (m.find()) return handleDice(m)
        }
        if (coinPattern.matcher(command).find()) return AutomationResult.Handled(UtilityTools.flipCoin())
        if (systemStatsPattern.matcher(command).find()) {
            return AutomationResult.Handled(UtilityTools.batteryAndSystemStats(context))
        }
        if (weatherPattern.matcher(command).find()) {
            return AutomationResult.Handled(WeatherTool.currentWeather(context))
        }

        if (listFoldersPattern.matcher(command).find()) return handleListFolders()
        writeFilePattern.matcher(command).let { m ->
            if (m.find()) return handleWriteFile(m.group(1).orEmpty(), m.group(2).orEmpty(), m.group(3).orEmpty())
        }
        readFilePattern.matcher(command).let { m ->
            if (m.find()) return handleReadFile(m.group(1).orEmpty(), m.group(2).orEmpty())
        }
        browseFolderPattern.matcher(command).let { m ->
            if (m.find()) return handleBrowseFolder(m.group(1).orEmpty())
        }

        openAppPattern.matcher(command).let { m ->
            if (m.find()) return handleOpenApp(m.group(1)?.trim().orEmpty())
        }
        return AutomationResult.NotAnAutomationCommand
    }

    private fun handleSetGoal(text: String, stepsRaw: String): AutomationResult {
        if (text.isBlank()) return AutomationResult.NotAnAutomationCommand
        val steps = if (stepsRaw.isBlank()) emptyList() else stepsRaw.split(",", " then ").map { it.trim() }.filter { it.isNotBlank() }
        goalStore.addGoal(text, "medium", steps)
        return AutomationResult.Handled(
            if (steps.isEmpty()) "Goal set: $text."
            else "Goal set: $text, with ${steps.size} step${if (steps.size != 1) "s" else ""}."
        )
    }

    private fun handleListGoals(): AutomationResult {
        val goals = goalStore.allGoals()
        if (goals.isEmpty()) return AutomationResult.Handled("No goals set yet.")
        val summary = goals.joinToString("\n") { goal ->
            if (goal.steps.isEmpty()) {
                "- ${goal.text}"
            } else {
                val done = goal.steps.count { it.done }
                "- ${goal.text} ($done/${goal.steps.size} steps done)"
            }
        }
        return AutomationResult.Handled(summary)
    }

    private fun handleGoalProgress(goalFragment: String, stepFragment: String): AutomationResult {
        return if (goalStore.markStepDone(goalFragment, stepFragment)) {
            AutomationResult.Handled("Marked it done.")
        } else {
            AutomationResult.Failed("Couldn't find a matching goal/step for that.")
        }
    }

    private fun handleCompleteGoal(goalFragment: String): AutomationResult {
        return if (goalStore.markStepDone(goalFragment, "")) {
            AutomationResult.Handled("Marked \"$goalFragment\" as done.")
        } else {
            AutomationResult.Failed("Couldn't find a goal matching that.")
        }
    }

    private fun handleConvert(m: java.util.regex.Matcher): AutomationResult {
        val value = m.group(1)?.toDoubleOrNull() ?: return AutomationResult.Failed("Couldn't parse that number.")
        val from = m.group(2).orEmpty()
        val to = m.group(3).orEmpty()
        val result = UtilityTools.convertUnits(value, from, to)
            ?: return AutomationResult.Failed("I don't know how to convert $from to $to yet.")
        return AutomationResult.Handled(result)
    }

    private fun handlePassword(m: java.util.regex.Matcher): AutomationResult {
        val length = m.group(1)?.toIntOrNull() ?: 16
        val includeSymbols = m.group(2) == null
        return AutomationResult.Handled(UtilityTools.generatePassword(length, includeSymbols))
    }

    private fun handleDice(m: java.util.regex.Matcher): AutomationResult {
        val count = m.group(1)?.toIntOrNull() ?: 1
        val sides = m.group(2)?.toIntOrNull() ?: 6
        return AutomationResult.Handled(UtilityTools.rollDice(sides, count))
    }

    private fun handleListFolders(): AutomationResult {
        val names = folderSandbox.nicknames()
        return if (names.isEmpty()) {
            AutomationResult.Handled("No folders granted yet — add one from the gear icon → Sandboxed folders.")
        } else {
            AutomationResult.Handled("Folders you've granted me: ${names.joinToString(", ")}.")
        }
    }

    private fun handleBrowseFolder(nickname: String) : AutomationResult {
        val files = folderSandbox.listFiles(nickname.trim())
            ?: return AutomationResult.Failed("I don't have a folder granted with that name.")
        return if (files.isEmpty()) {
            AutomationResult.Handled("That folder's empty.")
        } else {
            AutomationResult.Handled(files.joinToString(", "))
        }
    }

    private fun handleReadFile(filename: String, nickname: String): AutomationResult {
        val content = folderSandbox.readFile(nickname.trim(), filename.trim())
            ?: return AutomationResult.Failed("Couldn't find that file in that folder.")
        return AutomationResult.Handled(content)
    }

    private fun handleWriteFile(nickname: String, filename: String, content: String): AutomationResult {
        val success = folderSandbox.writeFile(nickname.trim(), filename.trim(), content)
        return if (success) {
            AutomationResult.Handled("Wrote $filename to your $nickname folder.")
        } else {
            AutomationResult.Failed("Couldn't write that file — check the folder's been granted.")
        }
    }

    private fun cleanName(raw: String): String =
        raw.trim().removeSuffix(" please").removeSuffix(" now").trim()

    private fun handleCall(m: java.util.regex.Matcher): AutomationResult {
        val name = cleanName(m.group(1).orEmpty())
        if (name.isBlank()) return AutomationResult.NotAnAutomationCommand
        val phone = ContactsHelper.findPhoneNumber(context, name)
            ?: return AutomationResult.Failed("Couldn't find a contact matching \"$name\".")

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            usageTracker.logEvent("call")
            AutomationResult.Handled("Calling $name.")
        } catch (e: SecurityException) {
            AutomationResult.Failed("I don't have permission to make calls yet — grant Phone access in Android settings.")
        } catch (e: Exception) {
            AutomationResult.Failed("Couldn't place the call: ${e.message}")
        }
    }

    private fun handleText(m: java.util.regex.Matcher): AutomationResult {
        val name = cleanName(m.group(1).orEmpty())
        val message = m.group(2)?.trim().orEmpty()
        if (name.isBlank() || message.isBlank()) return AutomationResult.NotAnAutomationCommand
        val phone = ContactsHelper.findPhoneNumber(context, name)
            ?: return AutomationResult.Failed("Couldn't find a contact matching \"$name\".")

        return if (SmsHelper.sendMessage(phone, message)) {
            usageTracker.logEvent("text")
            AutomationResult.Handled("Sent to $name.")
        } else {
            AutomationResult.Failed("Couldn't send that text — check that SMS permission is granted.")
        }
    }

    private fun handleRecentTexts(): AutomationResult {
        val messages = try {
            SmsHelper.recentMessages(context)
        } catch (e: SecurityException) {
            return AutomationResult.Failed("I don't have permission to read texts yet — grant SMS access in Android settings.")
        }
        return if (messages.isEmpty()) {
            AutomationResult.Handled("No recent texts.")
        } else {
            AutomationResult.Handled(messages.joinToString("\n"))
        }
    }

    private fun handleCalendarQuery(daysFromToday: Int, dayLabel: String): AutomationResult {
        val events = try {
            CalendarReader.eventsForDay(context, daysFromToday)
        } catch (e: SecurityException) {
            return AutomationResult.Failed("I don't have permission to read your calendar yet — grant Calendar access in Android settings.")
        }
        return if (events.isEmpty()) {
            AutomationResult.Handled("Nothing on your calendar for $dayLabel.")
        } else {
            AutomationResult.Handled("For $dayLabel: ${events.joinToString("; ")}")
        }
    }

    private fun handleAddEvent(title: String): AutomationResult {
        if (title.isBlank()) return AutomationResult.NotAnAutomationCommand
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AutomationResult.Handled("Opened Calendar to add \"$title\" — set the time and save.")
        } catch (e: Exception) {
            AutomationResult.Failed("Couldn't open Calendar: ${e.message}")
        }
    }

    private fun handlePhotoCount(): AutomationResult {
        val count = try {
            PhotoReader.photosTakenToday(context)
        } catch (e: SecurityException) {
            return AutomationResult.Failed("I don't have permission to check your photos yet — grant Photos access in Android settings.")
        }
        return AutomationResult.Handled("You've taken $count photo${if (count != 1) "s" else ""} today.")
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

        // Querying launchable activities directly (rather than getInstalledApplications)
        // is the same approach a launcher uses, and reliably finds preinstalled apps like
        // Chrome that getInstalledApplications can miss depending on how they're registered.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcherIntent, 0)

        val match = candidates.firstOrNull { it.loadLabel(pm).toString().equals(appName, ignoreCase = true) }
            ?: candidates.firstOrNull { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) }

        return if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                usageTracker.logEvent("open_app")
                AutomationResult.Handled("Opening ${match.loadLabel(pm)}.")
            } else {
                AutomationResult.Failed("Found $appName but it has no launchable activity.")
            }
        } else {
            AutomationResult.Failed("Couldn't find an app matching \"$appName\".")
        }
    }
}
