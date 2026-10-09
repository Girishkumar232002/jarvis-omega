package com.jarvis.omega

const val DEFAULT_SYSTEM =
    "You are JARVIS, a concise, capable personal assistant on the user's phone. " +
        "Be direct, say when you are unsure, and never claim to have done something you have not done."

data class ChatMsg(val role: String, val text: String, val provider: String = "")

data class Note(val id: Long, val text: String)

data class Reminder(val id: Long, val text: String, val atMillis: Long)

data class Settings(
    val anthropicKey: String = "",
    val openaiKey: String = "",
    val geminiKey: String = "",
    val anthropicModel: String = "claude-sonnet-5-5",
    val openaiModel: String = "gpt-4o",
    val geminiModel: String = "gemini-2.5-flash",
    val mode: String = "auto", // auto | local | ollama | anthropic | openai | gemini
    val localModel: String = "qwen2.5-1.5b",
    val useGpu: Boolean = false,
    val ollamaUrl: String = "",
    val ollamaModel: String = "llama3.2",
    val systemPrompt: String = DEFAULT_SYSTEM,
    val speak: Boolean = true,
    val speechRate: Float = 1.0f,
    val wakeWord: String = "jarvis",
)
