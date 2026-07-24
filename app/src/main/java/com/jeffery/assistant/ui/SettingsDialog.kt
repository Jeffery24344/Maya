package com.jeffery.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jeffery.assistant.awareness.ForegroundAppTracker
import com.jeffery.assistant.llm.OllamaSettings
import com.jeffery.assistant.memory.FolderSandbox
import com.jeffery.assistant.presence.OverlayPermissionHelper

@Composable
fun SettingsDialog(
    settings: OllamaSettings,
    appTracker: ForegroundAppTracker,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    val hasUsageAccess = remember { mutableStateOf(appTracker.hasPermission()) }
    val context = LocalContext.current
    val hasOverlayAccess = remember { mutableStateOf(OverlayPermissionHelper.hasPermission(context)) }
    val folderSandbox = remember { FolderSandbox(context) }
    var folderNickname by remember { mutableStateOf("") }
    var folderList by remember { mutableStateOf(folderSandbox.nicknames()) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && folderNickname.isNotBlank()) {
            folderSandbox.addFolder(folderNickname, uri)
            folderList = folderSandbox.nicknames()
            folderNickname = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Ollama Cloud", style = MaterialTheme.typography.titleSmall)
                Text("Get a key at ollama.com/settings/keys")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true
                )
                Spacer(Modifier.height(20.dp))
                Text("App awareness", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (hasUsageAccess.value)
                        "Usage access granted — Nova can see what app you have open."
                    else
                        "Off — Nova can't see which app is in the foreground."
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(onClick = { appTracker.openSettings() }) {
                        Text(if (hasUsageAccess.value) "Manage access" else "Grant access")
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Floating bubble", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (hasOverlayAccess.value)
                        "Granted — a small avatar floats over other apps; tap it to talk to Nova."
                    else
                        "Off — Nova still shows a persistent notification either way; this adds the floating avatar too."
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(onClick = { OverlayPermissionHelper.openSettings(context) }) {
                        Text(if (hasOverlayAccess.value) "Manage access" else "Grant access")
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Sandboxed folders", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Give Nova read/write access to specific folders by name — she can " +
                        "never touch anything outside folders you've explicitly added here."
                )
                Spacer(Modifier.height(6.dp))
                if (folderList.isNotEmpty()) {
                    Text("Granted: ${folderList.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = folderNickname,
                    onValueChange = { folderNickname = it },
                    label = { Text("Nickname (e.g. \"unity project\")") },
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        enabled = folderNickname.isNotBlank()
                    ) {
                        Text("Choose folder")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.apiKey = apiKey.trim()
                settings.model = model.trim().ifBlank { OllamaSettings.DEFAULT_MODEL }
                onSaved()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
