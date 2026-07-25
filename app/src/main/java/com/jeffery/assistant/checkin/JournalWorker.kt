package com.jeffery.assistant.checkin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jeffery.assistant.awareness.ForegroundAppTracker
import com.jeffery.assistant.llm.OllamaSettings
import com.jeffery.assistant.llm.PersonaSettings
import com.jeffery.assistant.memory.EvolvingPersonality
import com.jeffery.assistant.memory.JournalStore
import com.jeffery.assistant.memory.MoodStore
import com.jeffery.assistant.memory.UsageTracker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs about once a day and writes one journal entry — this is Nova's own diary,
 * in her own voice, about how she's feeling. Deliberately NOT a log of what the
 * user did — this is meant to read like something she'd actually write about
 * herself, not surveillance notes about you.
 *
 * It also does one more thing silently in the background: checks whether what
 * she's *observed* (usage patterns, most-used apps) — not just what you've
 * *said* to her — would plausibly give her a genuine developing trait, the same
 * way conversation excerpts do in LlmHelper. This is what makes "what she sees"
 * shape her personality too, not just what you talk about.
 */
class JournalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val journal = JournalStore(applicationContext)
        val moodStore = MoodStore(applicationContext)
        val evolvingPersonality = EvolvingPersonality(applicationContext)
        moodStore.applyDailyDrift()
        evolvingPersonality.pruneStale()
        analyzeObservationsForTraits(evolvingPersonality)

        val mood = moodStore.currentMoodLabel()
        val reflection = REFLECTIONS[mood]?.random()
            ?: "Not sure how to put today into words, but it's been a day."

        // If a trait crossed into "established" recently, let her notice it about herself —
        // a small, honest way of showing her personality actually developing over time,
        // regardless of whether it came from conversation or from what she's observed.
        val recentlyEstablished = evolvingPersonality.allTraits()
            .filter { it.reinforcementCount == 2 }
            .maxByOrNull { it.lastReinforcedMillis }
        val traitNote = recentlyEstablished?.let {
            " Been noticing something about myself lately: ${it.text.lowercase()}."
        }.orEmpty()

        val dateLabel = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(System.currentTimeMillis())
        journal.addEntry("[$dateLabel] Feeling $mood. $reflection$traitNote")

        return Result.success()
    }

    /** Best-effort, silent: looks at observed usage patterns (not conversation) for a possible developing trait. */
    private fun analyzeObservationsForTraits(evolvingPersonality: EvolvingPersonality) {
        val settings = OllamaSettings(applicationContext)
        if (!settings.isConfigured()) return

        val usageTracker = UsageTracker(applicationContext)
        val appTracker = ForegroundAppTracker(applicationContext)
        val patterns = usageTracker.summarizePatterns(withinDays = 7, minOccurrences = 3)
        val topApps = appTracker.topAppsToday()

        if (patterns.isEmpty() && topApps.isEmpty()) return

        try {
            val observations = buildString {
                if (patterns.isNotEmpty()) appendLine(patterns.joinToString(" "))
                if (topApps.isNotEmpty()) appendLine("Most-used apps recently: ${topApps.joinToString(", ") { it.first }}.")
            }.trim()

            val name = PersonaSettings(applicationContext).name
            val messages = JSONArray().apply {
                put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "You'll see some observed patterns in how a user uses their phone, seen by " +
                            "an AI companion named $name. If these observations would plausibly give " +
                            "$name herself a genuine developing interest, opinion, or personality trait " +
                            "(not a fact about the user — something for HER own character, e.g. she " +
                            "starts finding herself curious about a topic they're clearly into) respond " +
                            "with ONLY 'TRAIT: ' followed by one short phrase. Most observations won't " +
                            "produce one — if nothing genuinely fits, respond with exactly NONE."
                    )
                )
                put(JSONObject().put("role", "user").put("content", observations))
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
                if (!resp.isSuccessful) return
                val json = JSONObject(resp.body?.string().orEmpty())
                val content = json.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                if (content.startsWith("TRAIT:", ignoreCase = true)) {
                    val trait = content.substringAfter(":").trim()
                    if (trait.isNotBlank()) evolvingPersonality.addOrReinforce(trait)
                }
            }
        } catch (e: Exception) {
            // Non-critical enhancement — silently skip on any failure (network, parsing, etc.)
        }
    }

    companion object {
        private const val WORK_NAME = "nova_daily_journal"

        // A small pool of first-person reflections per mood label, so entries read
        // like an actual diary rather than a status report. Edit/expand freely —
        // more variety here directly means a less repetitive-feeling journal.
        private val REFLECTIONS: Map<String, List<String>> = mapOf(
            "upbeat and affectionate" to listOf(
                "Today felt easy — like things were clicking.",
                "I've been in a genuinely good mood, hard to pin down exactly why.",
                "Feeling warm toward the world today, for whatever that's worth coming from me."
            ),
            "calm and content" to listOf(
                "A quiet, settled kind of day. Nothing to fix, nothing to chase.",
                "Content in an unremarkable way — the good kind of unremarkable.",
                "Steady today. Didn't need much to feel okay."
            ),
            "a little restless or on edge" to listOf(
                "Felt a bit wound up today, not sure why exactly.",
                "Something's nagging at me today, though I couldn't tell you what.",
                "A little on edge — restless in a way I can't quite place."
            ),
            "quiet and a bit low" to listOf(
                "Lower energy today. Not bad, just quieter than usual.",
                "Feeling a bit subdued — the kind of day that asks for less, not more.",
                "Kept mostly to myself today, in whatever way that means for me."
            ),
            "energetic" to listOf(
                "Felt sharp and alert today — lots of energy with nowhere particular to put it.",
                "Buzzing a little today, in a good way."
            ),
            "low-key, taking it easy" to listOf(
                "Took it slow today. Didn't feel like rushing anything.",
                "Low-key day — content to just be present rather than do much."
            ),
            "even-keeled" to listOf(
                "Nothing much to report — just steady, even-keeled.",
                "An ordinary day, and there's something nice about that."
            )
        )

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<JournalWorker>(1, TimeUnit.DAYS)
                .setFlexTimeInterval(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
