package com.jeffery.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jeffery.assistant.llm.PersonaSettings
import java.util.Locale

/**
 * Wraps Android's built-in TextToSpeech engine — fully on-device, no network needed.
 * Base pitch/rate and the chosen voice now come from PersonaSettings (editable in
 * the settings screen) rather than being hardcoded, and mood nudges are applied on
 * top of that user-set baseline. Speaking state is reported back via callbacks so
 * the UI (e.g. the avatar) can react while she's actually talking.
 */
class SpeechOutputManager(context: Context, private val personaSettings: PersonaSettings) {

    private var ready = false
    private var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts()?.language = Locale.getDefault()
            applyBaseVoiceSettings()
            tts()?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { onSpeakingChanged?.invoke(true) }
                override fun onDone(utteranceId: String?) { onSpeakingChanged?.invoke(false) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onSpeakingChanged?.invoke(false) }
            })
        }
    }

    private var ttsRef: TextToSpeech? = tts
    private fun tts(): TextToSpeech? = ttsRef

    fun setOnSpeakingChanged(listener: (Boolean) -> Unit) {
        onSpeakingChanged = listener
    }

    /** Lists installed voices for the current language, for the settings picker. */
    fun availableVoices(): List<Voice> {
        return try {
            tts().let { engine ->
                engine?.voices?.filter { it.locale?.language == Locale.getDefault().language }
                    ?.sortedBy { it.name } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Re-applies the user's chosen voice/pitch/rate from PersonaSettings — call after settings change. */
    fun applyBaseVoiceSettings() {
        if (!ready) return
        val voiceName = personaSettings.voiceName
        if (voiceName != null) {
            val match = tts()?.voices?.firstOrNull { it.name == voiceName }
            if (match != null) tts()?.voice = match
        }
        tts()?.setPitch(personaSettings.basePitch)
        tts()?.setSpeechRate(personaSettings.baseRate)
    }

    /**
     * Nudges pitch/rate around the user's base tuning to reflect current mood —
     * higher energy speaks slightly faster/brighter, low valence speaks slightly
     * flatter/slower. Subtle on purpose; call before speak().
     */
    fun applyMood(energy: Float, valence: Float) {
        if (!ready) return
        val negativity = (-valence).coerceAtLeast(0f) // how far into negative territory, 0 if positive/neutral
        val positivity = valence.coerceAtLeast(0f)
        val basePitch = personaSettings.basePitch
        val baseRate = personaSettings.baseRate
        val pitch = basePitch + (energy - 0.5f) * 0.06f + positivity * 0.03f
        val rate = baseRate + (energy - 0.5f) * 0.1f - negativity * 0.04f
        tts().let {
            it?.setPitch(pitch.coerceIn(0.5f, 2f))
            it?.setSpeechRate(rate.coerceIn(0.5f, 2f))
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_utterance")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
