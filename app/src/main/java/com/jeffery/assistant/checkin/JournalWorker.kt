package com.jeffery.assistant.checkin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jeffery.assistant.awareness.ForegroundAppTracker
import com.jeffery.assistant.memory.JournalStore
import com.jeffery.assistant.memory.NovaMemoryStore
import com.jeffery.assistant.memory.UsageTracker
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs about once a day and writes one journal entry — Nova's own running notes
 * about you, built from what's actually observable on-device: automation usage
 * patterns, today's most-used apps (if you've granted Usage Access), and how many
 * things she's been explicitly told to remember. Unlike CheckInWorker, this always
 * writes (it's a private journal, not a notification) so there's no need to skip days.
 */
class JournalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val journal = JournalStore(applicationContext)
        val usageTracker = UsageTracker(applicationContext)
        val memoryStore = NovaMemoryStore(applicationContext)
        val appTracker = ForegroundAppTracker(applicationContext)

        val parts = mutableListOf<String>()

        val topApps = appTracker.topAppsToday()
        if (topApps.isNotEmpty()) {
            val appNames = topApps.joinToString(", ") { it.first }
            parts.add("Spent the most time today in: $appNames.")
        }

        val patterns = usageTracker.summarizePatterns(withinDays = 1, minOccurrences = 1)
        if (patterns.isNotEmpty()) {
            parts.add(patterns.joinToString(" "))
        }

        val factCount = memoryStore.allFacts().size
        if (factCount > 0) {
            parts.add("Currently keeping track of $factCount thing${if (factCount != 1) "s" else ""} I've been told to remember.")
        }

        if (parts.isEmpty()) {
            parts.add("Quiet day — nothing notable to log.")
        }

        val dateLabel = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(System.currentTimeMillis())
        journal.addEntry("[$dateLabel] ${parts.joinToString(" ")}")

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "nova_daily_journal"

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
