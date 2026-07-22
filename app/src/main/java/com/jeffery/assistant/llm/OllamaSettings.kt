package com.jeffery.assistant.llm

import android.content.Context

/** Simple local storage for the Ollama Cloud API key and chosen model tag. */
class OllamaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    companion object {
        private const val KEY_API_KEY = "ollama_api_key"
        private const val KEY_MODEL = "ollama_model"
        // Check https://ollama.com/library for current "-cloud" tagged models you have
        // access to on your plan; swap this default for whichever you prefer.
        const val DEFAULT_MODEL = "llama3.1:8b-cloud"
    }
}
