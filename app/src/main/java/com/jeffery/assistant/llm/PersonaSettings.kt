package com.jeffery.assistant.llm

import android.content.Context

/** Everything about her that used to require editing code now lives here instead. */
class PersonaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nova_persona_settings", Context.MODE_PRIVATE)

    var name: String
        get() = prefs.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
        set(value) = prefs.edit().putString(KEY_NAME, value.ifBlank { DEFAULT_NAME }).apply()

    /** Free-text additions/overrides to her personality, appended to the base prompt as-is. */
    var personalityNotes: String
        get() = prefs.getString(KEY_PERSONALITY_NOTES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONALITY_NOTES, value).apply()

    /** Android TTS voice name (from TextToSpeech.getVoices()); null = system default. */
    var voiceName: String?
        get() = prefs.getString(KEY_VOICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_VOICE_NAME, value).apply()

    var basePitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.03f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value.coerceIn(0.5f, 2f)).apply()

    var baseRate: Float
        get() = prefs.getFloat(KEY_RATE, 0.98f)
        set(value) = prefs.edit().putFloat(KEY_RATE, value.coerceIn(0.5f, 2f)).apply()

    companion object {
        const val DEFAULT_NAME = "Nova"
        private const val KEY_NAME = "name"
        private const val KEY_PERSONALITY_NOTES = "personality_notes"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_PITCH = "pitch"
        private const val KEY_RATE = "rate"
    }
}
