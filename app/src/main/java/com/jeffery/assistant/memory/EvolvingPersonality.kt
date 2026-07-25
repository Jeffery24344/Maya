package com.jeffery.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LearnedTrait(val text: String, val reinforcementCount: Int, val lastReinforcedMillis: Long)

/**
 * Distinct from NovaIdentity's fixed self-facts (which never change) and from
 * NovaMemoryStore's facts about the user — this is what Nova herself picks up
 * over time from actually talking with you: a budding interest, an opinion that
 * solidifies the more it comes up, a bit of personality that emerges from
 * repeated exposure rather than being written in from day one.
 *
 * Traits get reinforced (not just added) when something similar comes up again,
 * and get pruned if they never repeat — so a one-off doesn't become a permanent
 * personality fixture, but a real pattern genuinely sticks.
 */
class EvolvingPersonality(context: Context) {
    private val prefs = context.getSharedPreferences("nova_evolving_personality", Context.MODE_PRIVATE)

    fun allTraits(): List<LearnedTrait> {
        val raw = prefs.getString(KEY_TRAITS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            LearnedTrait(obj.getString("text"), obj.getInt("count"), obj.getLong("lastReinforced"))
        }
    }

    /** Traits reinforced enough times to be worth surfacing in her system prompt. */
    fun establishedTraits(minReinforcement: Int = 2): List<String> {
        return allTraits()
            .filter { it.reinforcementCount >= minReinforcement }
            .sortedByDescending { it.reinforcementCount }
            .take(MAX_SURFACED_TRAITS)
            .map { it.text }
    }

    /** Traits that showed up once and are still "developing" — not yet established enough to shape her tone. */
    fun developingTraits(): List<String> = allTraits().filter { it.reinforcementCount == 1 }.map { it.text }

    /** If this trait (fuzzy-matched) already exists, strengthens it; otherwise adds it fresh. Returns true if newly reinforced past the threshold this call. */
    fun addOrReinforce(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        val current = allTraits().toMutableList()
        val existingIndex = current.indexOfFirst { similar(it.text, trimmed) }

        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            val newCount = existing.reinforcementCount + 1
            current[existingIndex] = existing.copy(reinforcementCount = newCount, lastReinforcedMillis = System.currentTimeMillis())
            save(current)
            return newCount == 2 // just crossed into "established"
        } else {
            current.add(LearnedTrait(trimmed, 1, System.currentTimeMillis()))
            save(current)
            return false
        }
    }

    /** Drops traits that only ever happened once and haven't repeated in a long while — keeps this organic rather than an ever-growing list. */
    fun pruneStale(staleAfterDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(staleAfterDays.toLong())
        val current = allTraits().filterNot { it.reinforcementCount == 1 && it.lastReinforcedMillis < cutoff }
        save(current)
    }

    private fun similar(a: String, b: String): Boolean {
        val normA = a.lowercase().trim()
        val normB = b.lowercase().trim()
        return normA == normB || normA.contains(normB) || normB.contains(normA)
    }

    private fun save(traits: List<LearnedTrait>) {
        val array = JSONArray()
        traits.forEach {
            array.put(JSONObject().put("text", it.text).put("count", it.reinforcementCount).put("lastReinforced", it.lastReinforcedMillis))
        }
        prefs.edit().putString(KEY_TRAITS, array.toString()).apply()
    }

    companion object {
        private const val KEY_TRAITS = "traits"
        private const val MAX_SURFACED_TRAITS = 10
    }
}
