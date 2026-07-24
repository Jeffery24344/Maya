package com.jeffery.assistant.automation

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reads upcoming calendar events. Adding events still goes through the Calendar app
 * itself (via ACTION_INSERT, see AutomationEngine) so the user always sees and
 * confirms what gets added rather than Nova silently writing to the calendar.
 */
object CalendarReader {

    fun eventsForDay(context: Context, daysFromToday: Int = 0): List<String> {
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysFromToday)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, start.timeInMillis)
        ContentUris.appendId(uriBuilder, end.timeInMillis)

        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
        val cursor = context.contentResolver.query(
            uriBuilder.build(), projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: return emptyList()

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val results = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val title = it.getString(0) ?: "Untitled event"
                val beginMillis = it.getLong(1)
                results.add("$title at ${timeFormat.format(Date(beginMillis))}")
            }
        }
        return results
    }
}
