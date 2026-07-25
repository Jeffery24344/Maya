package com.jeffery.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeffery.assistant.automation.AutomationEngine
import com.jeffery.assistant.automation.AutomationResult
import com.jeffery.assistant.llm.LlmHelper
import com.jeffery.assistant.llm.OllamaSettings
import com.jeffery.assistant.llm.PersonaSettings
import com.jeffery.assistant.memory.ChatHistoryStore
import com.jeffery.assistant.memory.FolderSandbox
import com.jeffery.assistant.memory.GoalStore
import com.jeffery.assistant.memory.JournalStore
import com.jeffery.assistant.memory.NovaMemoryStore
import com.jeffery.assistant.memory.UsageTracker
import com.jeffery.assistant.voice.SpeechOutputManager
import com.jeffery.assistant.voice.VoiceEvent
import com.jeffery.assistant.voice.VoiceInputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val liveTranscript: String = "",
    val modelReady: Boolean = false,
    val moodLabel: String = ""
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val voiceManager = VoiceInputManager(application)
    val personaSettings = PersonaSettings(application)
    private val speechOutput = SpeechOutputManager(application, personaSettings)
    private val memoryStore = NovaMemoryStore(application)
    private val usageTracker = UsageTracker(application)
    private val journalStore = JournalStore(application)
    private val chatHistoryStore = ChatHistoryStore(application)
    private val goalStore = GoalStore(application)
    private val folderSandbox = FolderSandbox(application)
    private val automationEngine = AutomationEngine(application, memoryStore, usageTracker, goalStore, folderSandbox)
    private val llmHelper = LlmHelper(application)
    val ollamaSettings = OllamaSettings(application)

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(
            modelReady = llmHelper.isModelAvailable(),
            moodLabel = llmHelper.moodStore.currentMoodLabel()
        )
        speechOutput.setOnSpeakingChanged { speaking ->
            _uiState.value = _uiState.value.copy(isSpeaking = speaking)
        }
        loadPersistedTranscript()
        showWelcomeBackGreeting()
    }

    /** Restores the visible chat transcript from disk so reopening the app doesn't start blank. */
    private fun loadPersistedTranscript() {
        val restored = chatHistoryStore.allMessages().map { ChatMessage(it.text, it.isUser) }
        if (restored.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(messages = restored)
        }
    }

    /**
     * Greets the user based on how long it's been since the app was last opened, and
     * whether Nova has any stored memories — a small touch that makes her feel like
     * she's been "there" the whole time rather than starting fresh every launch.
     */
    private fun showWelcomeBackGreeting() {
        val previousSeenMillis = memoryStore.markSeenNow()
        val factCount = memoryStore.allFacts().size

        val greeting = if (previousSeenMillis == 0L) {
            "Hi, I'm Nova. Tell me things to remember — like \"remember I have a meeting every Monday\" — and I'll keep them in mind from now on, even after you close the app."
        } else {
            val elapsedMillis = System.currentTimeMillis() - previousSeenMillis
            val gapDescription = when {
                elapsedMillis < TimeUnit.HOURS.toMillis(1) -> null // don't comment on very short gaps
                elapsedMillis < TimeUnit.DAYS.toMillis(1) -> "It's been a few hours."
                elapsedMillis < TimeUnit.DAYS.toMillis(2) -> "It's been about a day."
                else -> "It's been ${TimeUnit.MILLISECONDS.toDays(elapsedMillis)} days."
            }
            val memoryNote = if (factCount > 0) " I still remember what you've told me." else ""
            if (gapDescription != null) "Welcome back. $gapDescription$memoryNote" else null
        }

        if (greeting != null) {
            appendMessage(ChatMessage(greeting, isUser = false))
        }
    }

    fun startListening() {
        _uiState.value = _uiState.value.copy(isListening = true, liveTranscript = "")
        viewModelScope.launch {
            voiceManager.startListening().collect { event ->
                when (event) {
                    is VoiceEvent.ListeningStarted -> {
                        _uiState.value = _uiState.value.copy(isListening = true)
                    }
                    is VoiceEvent.PartialResult -> {
                        _uiState.value = _uiState.value.copy(liveTranscript = event.text)
                    }
                    is VoiceEvent.FinalResult -> {
                        _uiState.value = _uiState.value.copy(liveTranscript = "")
                        handleUserInput(event.text)
                    }
                    is VoiceEvent.Error -> {
                        _uiState.value = _uiState.value.copy(isListening = false, liveTranscript = "")
                    }
                    is VoiceEvent.Done -> {
                        _uiState.value = _uiState.value.copy(isListening = false)
                    }
                }
            }
        }
    }

    fun cancelListening() {
        voiceManager.cancel()
        _uiState.value = _uiState.value.copy(isListening = false, liveTranscript = "")
    }

    /** Clears chat history and the model's conversational memory, keeping her persona. */
    fun startNewConversation() {
        llmHelper.resetConversation()
        chatHistoryStore.clear()
        _uiState.value = _uiState.value.copy(messages = emptyList())
    }

    /** Call after the settings dialog saves a new API key/model. */
    fun refreshModelStatus() {
        _uiState.value = _uiState.value.copy(modelReady = llmHelper.isModelAvailable())
    }

    fun journalEntries() = journalStore.allEntries()

    fun availableVoices() = speechOutput.availableVoices()

    /** Call after the settings dialog saves voice/pitch/rate changes. */
    fun refreshVoiceSettings() {
        speechOutput.applyBaseVoiceSettings()
    }

    fun previewVoice(sampleText: String) {
        speechOutput.speak(sampleText)
    }

    /** Also used for typed (non-voice) input from the text field. */
    fun handleUserInput(text: String) {
        if (text.isBlank()) return
        appendMessage(ChatMessage(text, isUser = true))
        chatHistoryStore.appendMessage(text, isUser = true)

        viewModelScope.launch {
            // Some automations (weather, website checks) now make real network calls,
            // so this runs off the main thread to avoid freezing the UI.
            val result = withContext(Dispatchers.IO) { automationEngine.tryHandle(text) }
            when (result) {
                is AutomationResult.Handled -> respond(result.confirmation)
                is AutomationResult.Failed -> respond(result.reason)
                AutomationResult.NotAnAutomationCommand -> askLlm(text)
            }
        }
    }

    private fun askLlm(prompt: String) {
        _uiState.value = _uiState.value.copy(isThinking = true)
        val placeholderIndex = _uiState.value.messages.size
        appendMessage(ChatMessage("", isUser = false))

        viewModelScope.launch {
            val builder = StringBuilder()
            llmHelper.generateResponse(prompt).collect { chunk ->
                builder.append(chunk)
                updateMessageAt(placeholderIndex, builder.toString())
            }
            _uiState.value = _uiState.value.copy(isThinking = false, moodLabel = llmHelper.moodStore.currentMoodLabel())
            // Persist once the full reply is in, rather than writing to disk on every
            // streamed chunk.
            chatHistoryStore.appendMessage(builder.toString(), isUser = false)
            speechOutput.applyMood(llmHelper.moodStore.energy, llmHelper.moodStore.valence)
            speechOutput.speak(builder.toString())
        }
    }

    private fun respond(text: String) {
        appendMessage(ChatMessage(text, isUser = false))
        chatHistoryStore.appendMessage(text, isUser = false)
        speechOutput.speak(text)
    }

    private fun appendMessage(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + message)
    }

    private fun updateMessageAt(index: Int, newText: String) {
        val current = _uiState.value.messages.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(text = newText)
            _uiState.value = _uiState.value.copy(messages = current)
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechOutput.shutdown()
        llmHelper.close()
    }
}
