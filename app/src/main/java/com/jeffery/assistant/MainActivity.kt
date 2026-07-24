package com.jeffery.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jeffery.assistant.checkin.CheckInWorker
import com.jeffery.assistant.checkin.JournalWorker
import com.jeffery.assistant.presence.NovaPresenceService
import com.jeffery.assistant.ui.AssistantViewModel
import com.jeffery.assistant.ui.ChatScreen
import com.jeffery.assistant.ui.JournalScreen

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: user can just retry tapping the mic button */ }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: without this, check-ins just won't show — not fatal */ }

    private val requestPhoneAccessPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: any that get denied just make that specific command fail gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Contacts, calls, texts, calendar, photos — lets Nova look people up, call/text
        // them, check your schedule, and see photo counts. Any of these the user denies
        // just makes that one specific command fail gracefully rather than crashing.
        val phonePermissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        phonePermissions.add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES
            else
                Manifest.permission.READ_EXTERNAL_STORAGE
        )
        val ungranted = phonePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            requestPhoneAccessPermissions.launch(ungranted.toTypedArray())
        }

        // Lets Nova check in on her own occasionally, not just when the app is opened.
        CheckInWorker.schedule(applicationContext)
        JournalWorker.schedule(applicationContext)

        // Persistent "she's running" notification + optional floating bubble.
        NovaPresenceService.start(applicationContext)

        var pendingAutoListen by mutableStateOf(intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showJournal by remember { mutableStateOf(false) }
                    if (showJournal) {
                        JournalScreen(
                            entries = viewModel.journalEntries(),
                            onBack = { showJournal = false }
                        )
                    } else {
                        ChatScreen(viewModel, onOpenJournal = { showJournal = true })
                        LaunchedEffect(pendingAutoListen) {
                            if (pendingAutoListen) {
                                viewModel.startListening()
                                pendingAutoListen = false
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_LISTEN, false)) {
            viewModel.startListening()
        }
    }

    companion object {
        const val EXTRA_AUTO_LISTEN = "auto_listen"
    }
}
