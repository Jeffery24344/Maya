package com.jeffery.assistant.memory

import android.content.Context
import kotlin.random.Random

private val POSITIVE_WORDS = listOf(
    "thanks", "thank you", "love", "great", "awesome", "happy", "good", "nice",
    "amazing", "excited", "glad", "wonderful", "perfect", "yay", "haha", "lol"
)
private val NEGATIVE_WORDS = listOf(
    "tired", "sad", "angry", "hate", "bad", "stressed", "annoyed", "frustrated",
    "awful", "terrible", "exhausted", "upset", "worried", "anxious"
)

/**
 * A simple two-axis mood (valence: negative↔positive, energy: low↔high) that persists
 * across sessions and drifts naturally over time rather than resetting to neutral
 * every launch. Nudged by rough sentiment in what the user says, with a slow daily
 * pull back toward baseline so she doesn't get stuck in an extreme for good.
 *
 * This is a deliberately simple heuristic, not real emotion — but it gives her tone
 * something to actually track across days instead of being flat every single time.
 */
class MoodStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_mood", Context.MODE_PRIVATE)

    var valence: Float
        get() = prefs.getFloat(KEY_VALENCE, 0.2f) // starts mildly warm, not neutral-flat
        private set(value) = prefs.edit().putFloat(KEY_VALENCE, value.coerceIn(-1f, 1f)).apply()

    var energy: Float
        get() = prefs.getFloat(KEY_ENERGY, 0.5f)
        private set(value) = prefs.edit().putFloat(KEY_ENERGY, value.coerceIn(0f, 1f)).apply()

    /** Nudges mood based on rough sentiment in the user's message. Cheap and approximate on purpose. */
    fun applyMessageSentiment(userText: String) {
        val lower = userText.lowercase()
        val positiveHits = POSITIVE_WORDS.count { lower.contains(it) }
        val negativeHits = NEGATIVE_WORDS.count { lower.contains(it) }

        val valenceDelta = (positiveHits - negativeHits) * 0.08f
        var energyDelta = 0f
        if (userText.contains("!")) energyDelta += 0.05f
        if (positiveHits + negativeHits == 0 && userText.length < 8) energyDelta -= 0.02f // short/flat messages settle her down slightly

        valence = valence + valenceDelta
        energy = energy + energyDelta
    }

    /** Slow pull back toward a mildly warm baseline, plus a touch of natural randomness. Call about once a day. */
    fun applyDailyDrift() {
        val pullStrength = 0.15f
        valence = valence + (0.2f - valence) * pullStrength + (Random.nextFloat() - 0.5f) * 0.1f
        energy = energy + (0.5f - energy) * pullStrength + (Random.nextFloat() - 0.5f) * 0.1f
    }

    /** A short human label for the current mood, meant to color tone rather than dictate a script. */
    fun currentMoodLabel(): String {
        return when {
            valence > 0.35f && energy > 0.6f -> "upbeat and affectionate"
            valence > 0.35f && energy <= 0.6f -> "calm and content"
            valence < -0.35f && energy > 0.6f -> "a little restless or on edge"
            valence < -0.35f && energy <= 0.6f -> "quiet and a bit low"
            energy > 0.7f -> "energetic"
            energy < 0.3f -> "low-key, taking it easy"
            else -> "even-keeled"
        }
    }

    companion object {
        private const val KEY_VALENCE = "valence"
        private const val KEY_ENERGY = "energy"
    }
}
