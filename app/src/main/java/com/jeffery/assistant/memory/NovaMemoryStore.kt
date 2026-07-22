package com.jeffery.assistant.memory

import android.content.Context
import org.json.JSONArray

/**
 * Long-term memory, separate from the rolling conversation history in LlmHelper.
 * Facts stored here persist across app restarts and get woven into every request's
 * system prompt, so Nova can recall things you told her days ago — not just earlier
 * in the same chat.
 */
class NovaMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_memory", Context.MODE_PRIVATE)

    fun allFacts(): List<String> {
        val raw = prefs.getString(KEY_FACTS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getString(it) }
    }

    fun addFact(fact: String) {
        val trimmed = fact.trim()
        if (trimmed.isBlank()) return
        val current = allFacts().toMutableList()
        current.add(trimmed)
        // Keep this bounded so the system prompt doesn't grow unbounded over months of use.
        while (current.size > MAX_FACTS) current.removeAt(0)
        save(current)
    }

    /** Removes any stored fact that contains the given text (case-insensitive). */
    fun removeMatching(text: String): Boolean {
        val needle = text.trim().lowercase()
        if (needle.isBlank()) return false
        val current = allFacts()
        val filtered = current.filterNot { it.lowercase().contains(needle) }
        if (filtered.size == current.size) return false
        save(filtered)
        return true
    }

    fun clearAll() {
        prefs.edit().remove(KEY_FACTS).apply()
    }

    /** Returns the previous "last seen" timestamp (0 if this is the first ever launch), then updates it to now. */
    fun markSeenNow(): Long {
        val previous = prefs.getLong(KEY_LAST_SEEN, 0L)
        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
        return previous
    }

    private fun save(facts: List<String>) {
        val array = JSONArray()
        facts.forEach { array.put(it) }
        prefs.edit().putString(KEY_FACTS, array.toString()).apply()
    }

    companion object {
        private const val KEY_FACTS = "facts"
        private const val KEY_LAST_SEEN = "last_seen_millis"
        private const val MAX_FACTS = 200
    }
}
