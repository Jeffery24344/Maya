package com.jeffery.assistant.memory

import android.content.Context

data class SecondaryCharacter(val name: String, val personality: String, val introducedAtMillis: Long)

/**
 * At most one secondary character can be active at a time — Nova invents who they
 * are and how they act (see automation/CharacterInviter.kt), turning the 1:1 chat
 * into a group conversation until the user ends it.
 */
class SecondaryCharacterStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_secondary_character", Context.MODE_PRIVATE)

    fun get(): SecondaryCharacter? {
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val personality = prefs.getString(KEY_PERSONALITY, "") ?: ""
        val introducedAt = prefs.getLong(KEY_INTRODUCED_AT, 0L)
        return SecondaryCharacter(name, personality, introducedAt)
    }

    fun isActive(): Boolean = get() != null

    fun set(name: String, personality: String) {
        prefs.edit()
            .putString(KEY_NAME, name.trim())
            .putString(KEY_PERSONALITY, personality.trim())
            .putLong(KEY_INTRODUCED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_PERSONALITY = "personality"
        private const val KEY_INTRODUCED_AT = "introduced_at"
    }
}
