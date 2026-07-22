package com.jeffery.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jeffery.assistant.awareness.ForegroundAppTracker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: AssistantViewModel, onOpenJournal: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var typedText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appTracker = remember { ForegroundAppTracker(context) }

    if (showSettings) {
        SettingsDialog(
            settings = viewModel.ollamaSettings,
            appTracker = appTracker,
            onDismiss = { showSettings = false },
            onSaved = {
                viewModel.refreshModelStatus()
                showSettings = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nova") },
                actions = {
                    if (!state.modelReady) {
                        Text(
                            "no API key set",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onOpenJournal) {
                        Icon(Icons.Filled.Book, contentDescription = "Journal")
                    }
                    IconButton(onClick = { viewModel.startNewConversation() }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = "New conversation")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            val novaState = when {
                state.isSpeaking -> NovaState.SPEAKING
                state.isThinking -> NovaState.THINKING
                state.isListening -> NovaState.LISTENING
                else -> NovaState.IDLE
            }
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                NovaOrb(state = novaState)
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(state.messages) { message ->
                    MessageBubble(message)
                }
            }

            if (state.liveTranscript.isNotBlank()) {
                Text(
                    text = state.liveTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = typedText,
                    onValueChange = { typedText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type or tap the mic\u2026") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    if (typedText.isNotBlank()) {
                        viewModel.handleUserInput(typedText)
                        typedText = ""
                    }
                }) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
                IconButton(onClick = {
                    if (state.isListening) viewModel.cancelListening() else viewModel.startListening()
                }) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Voice input",
                        tint = if (state.isListening) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Box(
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(0.8f)
        ) {
            Text(message.text.ifBlank { "\u2026" })
        }
    }
}
