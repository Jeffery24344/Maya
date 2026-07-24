package com.jeffery.assistant.automation

import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * Reads recent incoming texts and sends outgoing ones directly via SmsManager.
 * Note: Google Play restricts publishing apps with SMS permissions unless they're
 * the default SMS handler — irrelevant here since this is a personal sideloaded
 * app, not something distributed through the Play Store.
 */
object SmsHelper {

    fun recentMessages(context: Context, limit: Int = 3): List<String> {
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return emptyList()

        val results = mutableListOf<String>()
        cursor.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                results.add("From $address: $body")
                count++
            }
        }
        return results
    }

    fun sendMessage(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
