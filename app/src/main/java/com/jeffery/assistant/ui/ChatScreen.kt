package com.jeffery.assistant.ui

import android.provider.OpenableColumns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeffery.assistant.awareness.ForegroundAppTracker

private val QUICK_REPLIES = listOf(
    "What's on my calendar today?",
    "Remind me to ",
    "Call ",
    "How are you feeling today?"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(viewModel: AssistantViewModel, onOpenJournal: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var typedText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appTracker = remember { ForegroundAppTracker(context) }

    if (showSettings) {
        SettingsDialog(
            settings = viewModel.ollamaSettings,
            personaSettings = viewModel.personaSettings,
            appTracker = appTracker,
            availableVoices = viewModel.availableVoices(),
            onPreviewVoice = { viewModel.refreshVoiceSettings(); viewModel.previewVoice("Hi, this is what I sound like.") },
            onDismiss = { showSettings = false },
            onSaved = {
                viewModel.refreshModelStatus()
                viewModel.refreshVoiceSettings()
                showSettings = false
            }
        )
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clipData = event.toAndroidDragEvent().clipData ?: return false
                val appended = StringBuilder()
                for (i in 0 until clipData.itemCount) {
                    val item = clipData.getItemAt(i)
                    val text = item.text
                    val uri = item.uri
                    when {
                        !text.isNullOrBlank() -> appended.append(text).append(" ")
                        uri != null -> {
                            val name = displayNameForUri(context, uri) ?: uri.lastPathSegment ?: "file"
                            appended.append("[Attached: $name] ")
                        }
                    }
                }
                if (appended.isNotBlank()) {
                    typedText = (typedText + " " + appended.toString()).trim()
                }
                return appended.isNotBlank()
            }
        }
    }

    Scaffold(
        topBar = {
            // Kept deliberately minimal — no title text here, since her name/avatar/status
            // now live in the header below, closer to how a companion chat app presents
            // itself than a utility app's toolbar.
            TopAppBar(
                title = {},
                actions = {
                    if (!state.modelReady) {
                        Text(
                            "no API key set",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { showMenu = false; showSettings = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Journal") },
                                leadingIcon = { Icon(Icons.Filled.Book, contentDescription = null) },
                                onClick = { showMenu = false; onOpenJournal() }
                            )
                            DropdownMenuItem(
                                text = { Text("New conversation") },
                                leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                                onClick = { showMenu = false; viewModel.startNewConversation() }
                            )
                        }
                    }
                }
            )
        },
        // The composer lives in its OWN Scaffold slot rather than as the last item in the
        // body Column. This guarantees it always gets laid out and is never squeezed off
        // the bottom by the header or chat list — Scaffold reserves exact space for
        // whatever this slot measures to, and imePadding() here (not on the body) means
        // only the composer moves when the keyboard opens.
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp)
                ) {
                    QUICK_REPLIES.forEach { suggestion ->
                        AssistChip(
                            onClick = { typedText = suggestion },
                            label = { Text(suggestion.trim(), maxLines = 1) }
                        )
                    }
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
                        placeholder = { Text("Type, drop a file, or tap the mic\u2026") },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
        ) {
            val novaState = when {
                state.isSpeaking -> NovaState.SPEAKING
                state.isThinking -> NovaState.THINKING
                state.isListening -> NovaState.LISTENING
                else -> NovaState.IDLE
            }

            // Profile-style header — big avatar, name, and a mood status line, closer to
            // opening a contact/character in a chat app than a small utility indicator.
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NovaOrb(state = novaState, size = 108.dp)
                Spacer(Modifier.height(10.dp))
                Text(viewModel.personaSettings.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (state.moodLabel.isNotBlank()) {
                    Text(
                        "feeling ${state.moodLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // The weight(1f) lives on SelectionContainer (the direct child of this Column),
            // not on the LazyColumn nested inside it — Column only reads weight parent-data
            // off its immediate children.
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(state.messages) { message ->
                        MessageBubble(message)
                    }
                }
            }

            if (state.liveTranscript.isNotBlank()) {
                Text(
                    text = state.liveTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

private fun displayNameForUri(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    // Asymmetric corners give a more "chat app" feel and make sender obvious at a glance,
    // on top of the alignment/color difference.
    val bubbleShape = if (message.isUser)
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    else
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)

    // The secondary character (if one's been invited into a group chat) gets a visibly
    // different avatar color so it's obvious at a glance who's talking.
    val avatarColor = if (message.speakerName != null)
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
    else
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start) {
            if (message.speakerName != null) {
                Text(
                    message.speakerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 30.dp, bottom = 2.dp)
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (!message.isUser) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(avatarColor, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .background(bubbleColor, bubbleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .widthIn(max = 280.dp)
                ) {
                    Text(message.text.ifBlank { "\u2026" })
                }
            }
        }
    }
}
