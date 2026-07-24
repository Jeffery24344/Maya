package com.jeffery.assistant.automation

import android.content.Context
import android.provider.ContactsContract

/** Looks up a phone number by contact name, for the call/text automations. */
object ContactsHelper {

    fun findPhoneNumber(context: Context, name: String): String? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return null

        cursor.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) return it.getString(numberIndex)
            }
        }
        return null
    }
}
