package com.example.pathmind.model

import android.content.Intent

/**
 * Stage 8A: Core Voice and Spatial Intent Actions.
 */
enum class VoiceAction {
    REMEMBER_PLACE, // "Remember where I parked my bike", "Save this place as bike"
    FIND_PLACE,     // "Where did I park my bike?", "Navigate to bike"
    START_ROUTE,    // "Start route sai", "Begin route library"
    CANCEL,         // "Cancel", "Stop", "Dismiss"
    UNKNOWN         // Unmatched or ambiguous command
}

/**
 * Structured output produced by VoiceIntentParser.
 */
data class VoiceCommandResult(
    val action: VoiceAction,
    val rawTranscript: String,
    val targetEntity: String? = null,
    val confidence: Float = 0.0f,
    val feedbackMessage: String = ""
)

/**
 * Result produced by VoiceCommandOrchestrator after binding intent to repositories.
 * Extended in Stage 10 (Step 3) to support smart retrieval scoring and ambiguous candidate lists.
 */
data class VoiceExecutionResult(
    val commandResult: VoiceCommandResult,
    val success: Boolean,
    val displayMessage: String,
    val savedMemory: PlaceMemory? = null,
    val navigationIntent: Intent? = null,
    val candidateMemories: List<PlaceMemory> = emptyList(),
    val requiresSelection: Boolean = false,
    val retrievalScore: Float? = null,
    val matchType: String? = null,
    val matchReason: String? = null
)
