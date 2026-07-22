package com.jeffery.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Logs lightweight events (which automation types get used, and when) so Nova can
 * notice patterns — "you've set an alarm for 6am three mornings running" — without
 * this ever leaving the phone or being anything more than a local event count.
 *
 * This is intentionally separate from NovaMemoryStore: these are things Nova *noticed*,
 * not things the user explicitly told her to remember, so they're kept and labeled
 * distinctly in the prompt.
 */
class UsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences("nova_usage", Context.MODE_PRIVATE)

    fun logEvent(type: String) {
        val now = System.currentTimeMillis()
        val events = readEvents().toMutableList()
        events.add(JSONObject().put("type", type).put("time", now))
        // Keep the last ~200 events — plenty to spot weekly patterns without growing forever.
        while (events.size > 200) events.removeAt(0)
        val array = JSONArray()
        events.forEach { array.put(it) }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    /** Returns a few short, human-readable observations about repeated recent behavior, if any. */
    fun summarizePatterns(withinDays: Int = 7, minOccurrences: Int = 3): List<String> {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(withinDays.toLong())
        val recent = readEvents().filter { it.getLong("time") >= cutoff }
        if (recent.size < minOccurrences) return emptyList()

        return recent.groupBy { it.getString("type") }
            .filter { (_, events) -> events.size >= minOccurrences }
            .map { (type, events) ->
                val label = when (type) {
                    "alarm" -> "set an alarm"
                    "timer" -> "set a timer"
                    "reminder" -> "set a reminder"
                    "open_app" -> "opened an app through you"
                    "volume" -> "adjusted the volume"
                    else -> "used the \"$type\" command"
                }
                "The user has $label ${events.size} times in the last $withinDays days."
            }
    }

    private fun readEvents(): List<JSONObject> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    companion object {
        private const val KEY_EVENTS = "events"
    }
}
