package com.jeffery.assistant.ui

import android.speech.tts.Voice
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.jeffery.assistant.llm.PersonaSettings
import com.jeffery.assistant.memory.EvolvingPersonality
import com.jeffery.assistant.memory.FolderSandbox
import com.jeffery.assistant.presence.BatteryOptimizationHelper
import com.jeffery.assistant.presence.OverlayPermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: OllamaSettings,
    personaSettings: PersonaSettings,
    appTracker: ForegroundAppTracker,
    availableVoices: List<Voice>,
    onPreviewVoice: () -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    val hasUsageAccess = remember { mutableStateOf(appTracker.hasPermission()) }
    val context = LocalContext.current
    val hasOverlayAccess = remember { mutableStateOf(OverlayPermissionHelper.hasPermission(context)) }
    val isBatteryExempt = remember { mutableStateOf(BatteryOptimizationHelper.isExempt(context)) }
    val folderSandbox = remember { FolderSandbox(context) }
    var folderNickname by remember { mutableStateOf("") }
    var folderList by remember { mutableStateOf(folderSandbox.nicknames()) }
    val evolvingPersonality = remember { EvolvingPersonality(context) }

    var novaName by remember { mutableStateOf(personaSettings.name) }
    var personalityNotes by remember { mutableStateOf(personaSettings.personalityNotes) }
    var selectedVoiceName by remember { mutableStateOf(personaSettings.voiceName) }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }
    var pitch by remember { mutableStateOf(personaSettings.basePitch) }
    var rate by remember { mutableStateOf(personaSettings.baseRate) }

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
                Text("Personality", style = MaterialTheme.typography.titleSmall)
                Text("Change her name or add anything you want to be genuinely true about her.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = novaName,
                    onValueChange = { novaName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = personalityNotes,
                    onValueChange = { personalityNotes = it },
                    label = { Text("Personality notes (optional)") },
                    placeholder = { Text("e.g. \"loves hiking\", \"a bit sarcastic\", \"grew up in Chicago\"") },
                    minLines = 3
                )
                run {
                    val established = evolvingPersonality.establishedTraits()
                    val developing = evolvingPersonality.developingTraits()
                    if (established.isNotEmpty() || developing.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Traits she's picked up on her own from talking with you (this grows " +
                                "automatically — nothing to set here):",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (established.isNotEmpty()) {
                            Text("Established: ${established.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (developing.isNotEmpty()) {
                            Text("Still developing: ${developing.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("Voice", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = voiceDropdownExpanded,
                    onExpandedChange = { voiceDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedVoiceName ?: "System default",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("TTS voice") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceDropdownExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = voiceDropdownExpanded,
                        onDismissRequest = { voiceDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("System default") },
                            onClick = { selectedVoiceName = null; voiceDropdownExpanded = false }
                        )
                        availableVoices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice.name) },
                                onClick = { selectedVoiceName = voice.name; voiceDropdownExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Pitch: %.2f".format(pitch), style = MaterialTheme.typography.bodySmall)
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f)
                Text("Speed: %.2f".format(rate), style = MaterialTheme.typography.bodySmall)
                Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f)
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(onClick = {
                        personaSettings.voiceName = selectedVoiceName
                        personaSettings.basePitch = pitch
                        personaSettings.baseRate = rate
                        onPreviewVoice()
                    }) { Text("Apply & preview") }
                }

                Spacer(Modifier.height(20.dp))
                Text("Background reliability", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (isBatteryExempt.value)
                        "Exempted — check-ins and her daily journal entry should run reliably."
                    else
                        "If daily check-ins or journal entries aren't showing up, it's almost " +
                            "always Android's battery optimization silently killing background " +
                            "work, not a bug. Grant this exemption to fix it."
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(onClick = { BatteryOptimizationHelper.requestExemption(context) }) {
                        Text(if (isBatteryExempt.value) "Manage" else "Fix background reliability")
                    }
                }
                Text(
                    "Some phone brands (Xiaomi, Samsung, Huawei, OnePlus, etc.) layer their own " +
                        "battery manager on top of this — if it's still unreliable after granting " +
                        "this, look for an \"autostart\" or \"no restrictions\" toggle in your " +
                        "phone's own battery settings for this app.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(20.dp))
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
                        "Usage access granted — she can see what app you have open."
                    else
                        "Off — she can't see which app is in the foreground."
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
                        "Granted — a small avatar floats over other apps; tap it to talk to her."
                    else
                        "Off — she still shows a persistent notification either way; this adds the floating avatar too."
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
                    "Give her read/write access to specific folders by name — she can " +
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
                personaSettings.name = novaName
                personaSettings.personalityNotes = personalityNotes
                personaSettings.voiceName = selectedVoiceName
                personaSettings.basePitch = pitch
                personaSettings.baseRate = rate
                onSaved()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
