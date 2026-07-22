package com.jeffery.assistant.checkin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jeffery.assistant.automation.NotificationHelper
import com.jeffery.assistant.memory.MoodStore
import com.jeffery.assistant.memory.NovaMemoryStore
import com.jeffery.assistant.memory.UsageTracker
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Runs roughly once a day (Android's WorkManager batches exact timing for battery
 * reasons — this is "sometime today," not a precise alarm) and has Nova send a
 * check-in notification of her own accord, so she doesn't feel like she only exists
 * when you open the app. She skips some days on purpose so it doesn't get repetitive.
 *
 * This composes a message from templates + what's actually stored on-device (facts,
 * usage patterns) rather than calling the cloud LLM in the background, to keep this
 * reliable and avoid surprise network/battery use while the app isn't open. Swap in
 * a real LLM call here later if you want richer, less templated check-ins.
 */
class CheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Skip most days so this feels like an occasional thought, not a daily chore.
        if (Random.nextFloat() > SKIP_CHANCE_TO_FIRE) return Result.success()

        val memoryStore = NovaMemoryStore(applicationContext)
        val usageTracker = UsageTracker(applicationContext)
        val moodStore = MoodStore(applicationContext)

        val facts = memoryStore.allFacts()
        val patterns = usageTracker.summarizePatterns()
        val mood = moodStore.currentMoodLabel()

        val message = when {
            patterns.isNotEmpty() -> patterns.random()
                .replace("The user has", "Noticed you've")
                .replace(".", " — thought I'd mention it.")
            facts.isNotEmpty() -> "Just checking in, feeling $mood today. Still keeping track of what you've told me — ${facts.size} thing${if (facts.size != 1) "s" else ""} on file."
            else -> "Just checking in — feeling $mood, nothing urgent, just wanted to say hi."
        }

        NotificationHelper.show(
            applicationContext,
            id = CHECK_IN_NOTIFICATION_ID,
            title = "Nova",
            text = message
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "nova_daily_checkin"
        private const val CHECK_IN_NOTIFICATION_ID = 9001

        // Chance the worker actually fires a notification on any given scheduled run,
        // so she doesn't check in literally every single day. Tune to taste.
        private const val SKIP_CHANCE_TO_FIRE = 0.4f

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CheckInWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
