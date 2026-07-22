package com.jeffery.assistant.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_REMINDER_TEXT) ?: return
        val id = intent.getIntExtra(EXTRA_REMINDER_ID, 0)
        NotificationHelper.show(context, id, "Reminder", text)
    }

    companion object {
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}
