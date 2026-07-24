package com.jeffery.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last N (user, assistant) turns that LlmHelper sends as context on
 * every request. Without this, closing the app mid-conversation and reopening it
 * meant she'd lose the thread of what you were just talking about — this is what
 * makes "remember what we were just discussing" survive a restart, distinct from
 * NovaMemoryStore's explicit long-term facts.
 */
class ConversationHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_conversation_history", Context.MODE_PRIVATE)

    fun load(): List<Pair<String, String>> {
        val raw = prefs.getString(KEY_TURNS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            obj.getString("user") to obj.getString("assistant")
        }
    }

    fun save(turns: List<Pair<String, String>>) {
        val array = JSONArray()
        turns.forEach { (user, assistant) ->
            array.put(JSONObject().put("user", user).put("assistant", assistant))
        }
        prefs.edit().putString(KEY_TURNS, array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TURNS).apply()
    }

    companion object {
        private const val KEY_TURNS = "turns"
    }
}
