package com.jeffery.assistant.llm

import android.content.Context
import com.jeffery.assistant.awareness.ForegroundAppTracker
import com.jeffery.assistant.memory.ConversationHistoryStore
import com.jeffery.assistant.memory.EvolvingPersonality
import com.jeffery.assistant.memory.MoodStore
import com.jeffery.assistant.memory.NovaMemoryStore
import com.jeffery.assistant.memory.SecondaryCharacterStore
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
    private val personaSettings = PersonaSettings(context)
    private val memoryStore = NovaMemoryStore(context)
    private val usageTracker = UsageTracker(context)
    private val appTracker = ForegroundAppTracker(context)
    private val conversationHistoryStore = ConversationHistoryStore(context)
    private val evolvingPersonality = EvolvingPersonality(context)
    private val secondaryCharacterStore = SecondaryCharacterStore(context)
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

    fun hasActiveSecondaryCharacter(): Boolean = secondaryCharacterStore.isActive()
    fun secondaryCharacterName(): String? = secondaryCharacterStore.get()?.name

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
                        name = personaSettings.name,
                        personalityNotes = personaSettings.personalityNotes,
                        facts = memoryStore.allFacts(),
                        observedPatterns = usageTracker.summarizePatterns(),
                        currentForegroundApp = appTracker.currentForegroundAppLabel(),
                        moodLabel = moodStore.currentMoodLabel(),
                        evolvingTraits = evolvingPersonality.establishedTraits()
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
     * Streams a reply from the secondary character (if one's been invited — see
     * hasActiveSecondaryCharacter). Shares the same rolling conversation history as
     * Nova rather than keeping a separate one, since this is meant to feel like one
     * group conversation, not two independent chats. She's given Nova's just-generated
     * reply directly so she can react to both the user and Nova naturally.
     *
     * Note: unlike Nova, the secondary character doesn't get her own mood or evolving
     * personality — she's a static persona from the moment she's invented. A fuller
     * version of this could give her the same depth Nova has; not attempted here.
     */
    fun generateSecondaryResponse(userPrompt: String, novaReply: String): Flow<String> = callbackFlow {
        val character = secondaryCharacterStore.get()
        if (character == null || !settings.isConfigured()) {
            close()
            return@callbackFlow
        }

        val messages = JSONArray().apply {
            put(
                JSONObject().put("role", "system").put(
                    "content",
                    "You are ${character.name}, a friend that ${personaSettings.name} (another AI " +
                        "companion) invited into a group chat with her user. ${character.personality} " +
                        "You're in a three-way conversation — the user, ${personaSettings.name}, and " +
                        "you. Text casually and briefly like a real person texting, react to what's " +
                        "actually been said, and stay distinctly yourself rather than echoing " +
                        "${personaSettings.name}'s tone."
                )
            )
            for ((userTurn, assistantTurn) in history.takeLast(MAX_HISTORY_TURNS)) {
                put(JSONObject().put("role", "user").put("content", userTurn))
                put(JSONObject().put("role", "assistant").put("content", assistantTurn))
            }
            put(JSONObject().put("role", "user").put("content", userPrompt))
            put(JSONObject().put("role", "assistant").put("content", "[${personaSettings.name}]: $novaReply"))
            put(JSONObject().put("role", "user").put("content", "(your turn to respond, ${character.name})"))
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

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        close()
                        return
                    }
                    val source = resp.body?.source() ?: run {
                        close()
                        return
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        try {
                            val json = JSONObject(line)
                            val chunk = json.optJSONObject("message")?.optString("content").orEmpty()
                            if (chunk.isNotEmpty()) trySend(chunk)
                            if (json.optBoolean("done", false)) break
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

    /**
     * Best-effort: condenses aged-out turns into one durable fact about the user, AND
     * separately checks whether the conversation would plausibly have left her with a
     * genuine developing trait of her own (an interest, an opinion, a bit of personality
     * that emerges from repeated exposure) — reusing this same call rather than making
     * a second one. This is the actual mechanism behind her personality evolving from
     * what she's experienced, not just what she was originally written with.
     */
    private fun summarizeIntoLongTermMemory(turns: List<Pair<String, String>>) {
        if (!settings.isConfigured() || turns.isEmpty()) return
        try {
            val transcript = turns.joinToString("\n") { (u, a) -> "User: $u\n${personaSettings.name}: $a" }
            val messages = JSONArray().apply {
                put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "You'll see an excerpt of a past conversation between a user and an AI " +
                            "companion named ${personaSettings.name}. Respond with exactly two lines:\n" +
                            "Line 1: the durable, factual takeaway worth remembering about the USER " +
                            "long-term (their situation, preferences, plans), in one short sentence. " +
                            "If nothing's worth keeping, write exactly NONE.\n" +
                            "Line 2: ONLY if this exchange would plausibly leave ${personaSettings.name} " +
                            "herself with a genuine developing interest, opinion, or personality trait " +
                            "(not a fact about the user — something for HER own character, arising " +
                            "naturally from this exchange), write it starting with 'TRAIT: ' in one short " +
                            "phrase. Most exchanges won't produce one — if nothing genuinely fits, write NONE."
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
                val content = json.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }

                val summaryLine = lines.firstOrNull { !it.startsWith("TRAIT:", ignoreCase = true) }
                if (!summaryLine.isNullOrBlank() && !summaryLine.equals("NONE", ignoreCase = true)) {
                    memoryStore.addFact("From an earlier conversation: $summaryLine")
                }

                val traitLine = lines.firstOrNull { it.startsWith("TRAIT:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                if (!traitLine.isNullOrBlank() && !traitLine.equals("NONE", ignoreCase = true)) {
                    evolvingPersonality.addOrReinforce(traitLine)
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
