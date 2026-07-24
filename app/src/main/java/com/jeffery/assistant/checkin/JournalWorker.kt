package com.jeffery.assistant.checkin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jeffery.assistant.memory.JournalStore
import com.jeffery.assistant.memory.MoodStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs about once a day and writes one journal entry — this is Nova's own diary,
 * in her own voice, about how she's feeling. Deliberately NOT a log of what the
 * user did (that's what UsageTracker/ForegroundAppTracker are for elsewhere, feeding
 * her conversation context) — this is meant to read like something she'd actually
 * write about herself, not surveillance notes about you.
 */
class JournalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val journal = JournalStore(applicationContext)
        val moodStore = MoodStore(applicationContext)
        moodStore.applyDailyDrift()

        val mood = moodStore.currentMoodLabel()
        val reflection = REFLECTIONS[mood]?.random()
            ?: "Not sure how to put today into words, but it's been a day."

        val dateLabel = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(System.currentTimeMillis())
        journal.addEntry("[$dateLabel] Feeling $mood. $reflection")

        return Result.success()
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
            val request = PeriodicWorkRequestBuilder<JournalWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
