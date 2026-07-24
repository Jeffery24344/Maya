package com.jeffery.assistant.automation

import android.content.Context
import android.provider.MediaStore
import java.util.Calendar

/** Reads basic photo counts from MediaStore — no image content is read, just metadata. */
object PhotoReader {

    fun photosTakenToday(context: Context): Int {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis / 1000 // MediaStore DATE_TAKEN is millis, DATE_ADDED is seconds — using DATE_ADDED here

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf(startOfDay.toString()),
            null
        ) ?: return 0

        cursor.use { return it.count }
    }
}
