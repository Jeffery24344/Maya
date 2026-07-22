package com.jeffery.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class JournalEntry(val timestampMillis: Long, val text: String)

/**
 * A journal Nova keeps about you — her own running notes, not something you write in.
 * Entries are composed automatically (see checkin/JournalWorker.kt) from what she's
 * noticed: usage patterns, foreground app activity, new things she's been told to
 * remember. Purely on-device.
 */
class JournalStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_journal", Context.MODE_PRIVATE)

    fun addEntry(text: String) {
        if (text.isBlank()) return
        val current = allEntries().toMutableList()
        current.add(0, JournalEntry(System.currentTimeMillis(), text.trim()))
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        save(current)
    }

    /** Newest entries first. */
    fun allEntries(): List<JournalEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            JournalEntry(obj.getLong("time"), obj.getString("text"))
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(entries: List<JournalEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("time", it.timestampMillis).put("text", it.text)) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 120 // roughly 4 months of daily entries
    }
}
