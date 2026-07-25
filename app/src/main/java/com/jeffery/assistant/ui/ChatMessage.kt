package com.jeffery.assistant.ui

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    /** Null for the user and for the primary persona (Nova); set to the secondary character's name when they're speaking. */
    val speakerName: String? = null
)
