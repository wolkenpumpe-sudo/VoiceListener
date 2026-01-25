package com.example.voicelistener.network

import com.google.gson.annotations.SerializedName

// Chat Completion Request
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double = 0.0
)

data class Message(
    val role: String,
    val content: String
)

// Chat Completion Response
data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: Message
)

// Whisper Response
data class TranscriptionResponse(
    val text: String
)
