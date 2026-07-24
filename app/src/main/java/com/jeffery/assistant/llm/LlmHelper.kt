package com.jeffery.assistant.llm

import android.content.Context
import com.jeffery.assistant.awareness.ForegroundAppTracker
import com.jeffery.assistant.memory.ConversationHistoryStore
import com.jeffery.assistant.memory.MoodStore
import com.jeffery.assistant.memory.NovaMemoryStore
import com.jeffery.assistant.memory.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to Ollama Cloud's chat completion endpoint (the same backend Elara uses).
 * Requires an internet connection and an API key from https://ollama.com/settings/keys —
 * paste it into Nova's settings screen (gear icon) before chatting.
 */
class LlmHelper(context: Context) {

    companion object {
        private const val CHAT_ENDPOINT = "https://ollama.com/api/chat"
        private const val MAX_HISTORY_TURNS = 12
    }

    private val settings = OllamaSettings(context)
    private val memoryStore = NovaMemoryStore(context)
    private val usageTracker = UsageTracker(context)
    private val appTracker = ForegroundAppTracker(context)
    private val conversationHistoryStore = ConversationHistoryStore(context)
    val moodStore = MoodStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // (user, assistant) turns — loaded from disk on startup so closing and reopening
    // the app doesn't drop the thread of what you were just talking about.
    private val history = conversationHistoryStore.load().toMutableList()
    private var activeCall: Call? = null

    fun isModelAvailable(): Boolean = settings.isConfigured()

    /** No-op — nothing to load locally. Kept so callers don't need to change. */
    suspend fun initialize() { /* Ollama Cloud needs no local setup */ }

    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        if (!settings.isConfigured()) {
            trySend("I need an Ollama Cloud API key first — tap the gear icon to add one.")
            close()
            return@callbackFlow
        }

        moodStore.applyMessageSentiment(prompt)

        val messages = JSONArray().apply {
            put(
                JSONObject().put("role", "system").put(
                    "content",
                    Persona.buildSystemPrompt(
                        facts = memoryStore.allFacts(),
                        observedPatterns = usageTracker.summarizePatterns(),
                        currentForegroundApp = appTracker.currentForegroundAppLabel(),
                        moodLabel = moodStore.currentMoodLabel()
                    )
                )
            )
            for ((userTurn, assistantTurn) in history.takeLast(MAX_HISTORY_TURNS)) {
                put(JSONObject().put("role", "user").put("content", userTurn))
                put(JSONObject().put("role", "assistant").put("content", assistantTurn))
            }
            put(JSONObject().put("role", "user").put("content", prompt))
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", messages)
            put("stream", true)
        }

        val request = Request.Builder()
            .url(CHAT_ENDPOINT)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call
        val fullResponse = StringBuilder()

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend("Couldn't reach Ollama Cloud: ${e.message}")
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        trySend("Ollama Cloud returned an error (${resp.code}). Check your API key and model name in settings.")
                        close()
                        return
                    }
                    val source = resp.body?.source() ?: run {
                        trySend("Empty response from Ollama Cloud.")
                        close()
                        return
                    }
                    // Ollama streams newline-delimited JSON objects, one per token/chunk.
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        try {
                            val json = JSONObject(line)
                            val chunk = json.optJSONObject("message")?.optString("content").orEmpty()
                            if (chunk.isNotEmpty()) {
                                fullResponse.append(chunk)
                                trySend(chunk)
                            }
                            if (json.optBoolean("done", false)) {
                                history.add(prompt to fullResponse.toString())
                                trimAndPersistHistory()
                                break
                            }
                        } catch (_: Exception) {
                            // skip malformed line
                        }
                    }
                    close()
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * Keeps the rolling context bounded. Turns that age out aren't just thrown away —
     * they're condensed into a single long-term fact first, so she can still recall
     * the gist of an old conversation without you ever having said "remember this."
     * This runs synchronously on the same background thread OkHttp already called us
     * on, so it doesn't need its own coroutine scope.
     */
    private fun trimAndPersistHistory() {
        if (history.size > MAX_HISTORY_TURNS) {
            val overflowCount = history.size - MAX_HISTORY_TURNS
            val aging = history.subList(0, overflowCount).toList()
            repeat(overflowCount) { history.removeAt(0) }
            summarizeIntoLongTermMemory(aging)
        }
        conversationHistoryStore.save(history)
    }

    /** Best-effort: condenses aged-out turns into one short fact via a small non-streaming call. */
    private fun summarizeIntoLongTermMemory(turns: List<Pair<String, String>>) {
        if (!settings.isConfigured() || turns.isEmpty()) return
        try {
            val transcript = turns.joinToString("\n") { (u, a) -> "User: $u\nNova: $a" }
            val messages = JSONArray().apply {
                put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "Summarize the durable, factual takeaway from this excerpt of a past " +
                            "conversation in ONE short sentence, focused only on things worth " +
                            "remembering about the user long-term (their situation, preferences, " +
                            "plans). If there's nothing worth keeping, reply with exactly: NONE."
                    )
                )
                put(JSONObject().put("role", "user").put("content", transcript))
            }
            val body = JSONObject().apply {
                put("model", settings.model)
                put("messages", messages)
                put("stream", false)
            }
            val request = Request.Builder()
                .url(CHAT_ENDPOINT)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val json = JSONObject(resp.body?.string().orEmpty())
                val summary = json.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                if (summary.isNotBlank() && !summary.equals("NONE", ignoreCase = true)) {
                    memoryStore.addFact("From an earlier conversation: $summary")
                }
            }
        } catch (e: Exception) {
            // Non-critical enhancement — silently skip on any failure (network, parsing, etc.)
        }
    }

    /** Clears conversation memory. Persona is re-sent as a system message every request regardless. */
    fun resetConversation() {
        history.clear()
        conversationHistoryStore.clear()
    }

    fun close() {
        activeCall?.cancel()
    }
}
