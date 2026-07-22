package com.jeffery.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wraps Android's built-in TextToSpeech engine — fully on-device, no network needed.
 * Pitch/rate are tuned slightly for warmth, and speaking state is reported back via
 * callbacks so the UI (e.g. the avatar) can react while she's actually talking.
 */
class SpeechOutputManager(context: Context) {

    private var ready = false
    private var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts()?.language = Locale.getDefault()
            // Slight warmth tweak — a touch higher pitch and a measured, unhurried rate
            // fits a calm/professional character better than the flat robotic default.
            tts()?.setPitch(1.03f)
            tts()?.setSpeechRate(0.98f)
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

    /**
     * Nudges pitch/rate around the base warmth tuning to reflect current mood —
     * higher energy speaks slightly faster/brighter, low valence speaks slightly
     * flatter/slower. Subtle on purpose; call before speak().
     */
    fun applyMood(energy: Float, valence: Float) {
        if (!ready) return
        val negativity = (-valence).coerceAtLeast(0f) // how far into negative territory, 0 if positive/neutral
        val positivity = valence.coerceAtLeast(0f)
        val pitch = 1.03f + (energy - 0.5f) * 0.06f + positivity * 0.03f
        val rate = 0.98f + (energy - 0.5f) * 0.1f - negativity * 0.04f
        tts().let {
            it?.setPitch(pitch.coerceIn(0.85f, 1.2f))
            it?.setSpeechRate(rate.coerceIn(0.8f, 1.2f))
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
