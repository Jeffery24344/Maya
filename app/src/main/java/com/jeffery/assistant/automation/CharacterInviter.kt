package com.jeffery.assistant.automation

import android.content.Context
import com.jeffery.assistant.llm.OllamaSettings
import com.jeffery.assistant.llm.PersonaSettings
import com.jeffery.assistant.memory.MoodStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Has Nova invent a fitting friend to bring into the chat — she creates who they are, not the user. */
object CharacterInviter {
    data class InventedCharacter(val name: String, val personality: String)

    fun inventCharacter(context: Context): InventedCharacter? {
        val settings = OllamaSettings(context)
        if (!settings.isConfigured()) return null
        val novaName = PersonaSettings(context).name
        val mood = MoodStore(context).currentMoodLabel()

        return try {
            val messages = JSONArray().apply {
                put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "An AI companion named $novaName (currently feeling $mood) wants to invite a " +
                            "friend of hers into a group chat with her user. Invent ONE fitting friend " +
                            "character. Respond with EXACTLY two lines and nothing else:\n" +
                            "Line 1: their name only.\n" +
                            "Line 2: a short, concrete personality description (1-2 sentences) — someone " +
                            "with their own distinct opinions, tone, and quirks, not a generic filler " +
                            "character. They should feel like a real friend of $novaName's, not a copy " +
                            "of her."
                    )
                )
                put(JSONObject().put("role", "user").put("content", "Invent the friend now."))
            }
            val body = JSONObject().apply {
                put("model", settings.model)
                put("messages", messages)
                put("stream", false)
            }
            val request = Request.Builder()
                .url("https://ollama.com/api/chat")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            OkHttpClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val content = json.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (lines.size < 2) return null
                InventedCharacter(lines[0].removePrefix("Name:").trim(), lines.drop(1).joinToString(" "))
            }
        } catch (e: Exception) {
            null
        }
    }
}
