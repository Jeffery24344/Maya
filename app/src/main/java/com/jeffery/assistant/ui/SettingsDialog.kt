package com.jeffery.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.jeffery.assistant.awareness.ForegroundAppTracker
import com.jeffery.assistant.llm.OllamaSettings

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
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
