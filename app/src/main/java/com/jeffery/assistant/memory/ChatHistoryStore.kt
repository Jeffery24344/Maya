package com.jeffery.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestampMillis: Long,
    val speakerName: String? = null
)

/**
 * Persists the visible chat transcript so reopening the app shows your conversation
 * history instead of starting blank — separate from NovaMemoryStore (explicit facts)
 * and the rolling model context in ConversationHistoryStore (what she actually sees
 * on each request). This one is purely for what you see scrolling back in the UI.
 */
class ChatHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_chat_history", Context.MODE_PRIVATE)

    fun allMessages(): List<StoredChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            StoredChatMessage(
                obj.getString("text"),
                obj.getBoolean("isUser"),
                obj.getLong("time"),
                if (obj.has("speaker")) obj.optString("speaker").ifBlank { null } else null
            )
        }
    }

    fun appendMessage(text: String, isUser: Boolean, speakerName: String? = null) {
        val current = allMessages().toMutableList()
        current.add(StoredChatMessage(text, isUser, System.currentTimeMillis(), speakerName))
        while (current.size > MAX_MESSAGES) current.removeAt(0)
        save(current)
    }

    fun clear() {
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    private fun save(messages: List<StoredChatMessage>) {
        val array = JSONArray()
        messages.forEach {
            val obj = JSONObject().put("text", it.text).put("isUser", it.isUser).put("time", it.timestampMillis)
            if (it.speakerName != null) obj.put("speaker", it.speakerName)
            array.put(obj)
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    companion object {
        private const val KEY_MESSAGES = "messages"
        private const val MAX_MESSAGES = 500
    }
}
