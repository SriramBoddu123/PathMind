package com.example.pathmind.voice

import com.example.pathmind.model.VoiceAction
import com.example.pathmind.model.VoiceCommandResult
import java.util.Locale
import java.util.regex.Pattern

/**
 * Stage 8A (Option 3): Deterministic On-Device Voice Intent Parser.
 * Parses natural language spatial navigation and memory commands without cloud APIs or external libraries.
 */
class VoiceIntentParser {

    companion object {
        // Cancel patterns
        private val PATTERN_CANCEL = Pattern.compile(
            "^(?:cancel|stop|dismiss|never\\s*mind|exit|close)$",
            Pattern.CASE_INSENSITIVE
        )

        // Remember patterns (ordered from most specific to general)
        private val PATTERNS_REMEMBER = listOf(
            Pattern.compile("remember\\s+where\\s+(?:i|we)\\s+(?:parked|left|placed|put|am\\s+at)\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("save\\s+(?:this|current)\\s+(?:place|location|spot)\\s+as\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("remember\\s+this\\s+(?:place|location|spot)\\s+as\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("save\\s+(?:my\\s+|the\\s+)?(.+)\\s+(?:location|spot|here)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("mark\\s+(?:this\\s+spot\\s+as\\s+|this\\s+place\\s+as\\s+|my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("remember\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("parked\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE)
        )

        // Find / Navigate to Memory patterns
        private val PATTERNS_FIND = listOf(
            Pattern.compile("where\\s+(?:did\\s+i|have\\s+i|we)\\s+(?:park|leave|put|place)\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("where\\s+(?:is|are)\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("take\\s+me\\s+to\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("navigate\\s+to\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("guide\\s+me\\s+to\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("find\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("how\\s+do\\s+i\\s+get\\s+to\\s+(?:my\\s+|the\\s+)?(.+)", Pattern.CASE_INSENSITIVE)
        )

        // Route control patterns (supports "start my route", "start route sai", "begin navigation")
        private val PATTERNS_ROUTE = listOf(
            Pattern.compile("(?:start|begin|follow|open)\\s+(?:my\\s+|the\\s+)?route(?:\\s+(.+))?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("navigate\\s+(?:my\\s+|the\\s+)?route(?:\\s+(.+))?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:start|begin)\\s+navigation(?:\\s+(?:on\\s+|along\\s+)?(.+))?", Pattern.CASE_INSENSITIVE)
        )
    }

    /**
     * Parses a raw speech transcript or text input into a structured VoiceCommandResult.
     */
    fun parse(rawInput: String): VoiceCommandResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return VoiceCommandResult(
                action = VoiceAction.UNKNOWN,
                rawTranscript = rawInput,
                confidence = 0.0f,
                feedbackMessage = "Please say or enter a command."
            )
        }

        // Normalize text: remove surrounding quotes, question marks, exclamation marks, periods
        val normalized = trimmed
            .replace(Regex("[?!.,\"']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.US)

        // 1. Check CANCEL
        if (PATTERN_CANCEL.matcher(normalized).matches()) {
            return VoiceCommandResult(
                action = VoiceAction.CANCEL,
                rawTranscript = rawInput,
                confidence = 1.0f,
                feedbackMessage = "Command cancelled."
            )
        }

        // 2. Check START_ROUTE
        for (pattern in PATTERNS_ROUTE) {
            val matcher = pattern.matcher(normalized)
            if (matcher.matches()) {
                val groupVal = try { matcher.group(1) } catch (e: Exception) { null }
                val rawRoute = if (groupVal != null) cleanEntity(groupVal) else ""
                val targetRoute = if (rawRoute.isNotBlank() && !isIgnoredGenericWord(rawRoute)) rawRoute else null
                val message = if (targetRoute != null) "Starting route '$targetRoute'." else "Starting learned route."
                return VoiceCommandResult(
                    action = VoiceAction.START_ROUTE,
                    rawTranscript = rawInput,
                    targetEntity = targetRoute,
                    confidence = 0.95f,
                    feedbackMessage = message
                )
            }
        }

        // 3. Check REMEMBER_PLACE
        for (pattern in PATTERNS_REMEMBER) {
            val matcher = pattern.matcher(normalized)
            if (matcher.matches()) {
                val rawPlace = cleanEntity(matcher.group(1))
                if (rawPlace.isNotBlank() && !isIgnoredGenericWord(rawPlace)) {
                    return VoiceCommandResult(
                        action = VoiceAction.REMEMBER_PLACE,
                        rawTranscript = rawInput,
                        targetEntity = rawPlace,
                        confidence = 0.95f,
                        feedbackMessage = "Remembering place '$rawPlace'."
                    )
                }
            }
        }

        // 4. Check FIND_PLACE
        for (pattern in PATTERNS_FIND) {
            val matcher = pattern.matcher(normalized)
            if (matcher.matches()) {
                val rawPlace = cleanEntity(matcher.group(1))
                if (rawPlace.isNotBlank() && !isIgnoredGenericWord(rawPlace)) {
                    return VoiceCommandResult(
                        action = VoiceAction.FIND_PLACE,
                        rawTranscript = rawInput,
                        targetEntity = rawPlace,
                        confidence = 0.95f,
                        feedbackMessage = "Looking for '$rawPlace' in spatial memory."
                    )
                }
            }
        }

        // 5. Fallback: UNKNOWN
        return VoiceCommandResult(
            action = VoiceAction.UNKNOWN,
            rawTranscript = rawInput,
            confidence = 0.0f,
            feedbackMessage = "Unrecognized command. Try 'Remember where I parked my bike' or 'Where did I park my bike?'."
        )
    }

    /**
     * Cleans leading/trailing filler words from extracted place entities.
     */
    private fun cleanEntity(input: String?): String {
        if (input == null) return ""
        var cleaned = input.trim()

        // Strip leading fillers
        val leadingFillers = listOf("my ", "the ", "a ", "an ", "at ", "spot ", "place ", "location ")
        for (filler in leadingFillers) {
            if (cleaned.startsWith(filler, ignoreCase = true)) {
                cleaned = cleaned.substring(filler.length).trim()
            }
        }

        // Strip trailing fillers
        val trailingFillers = listOf(" here", " spot", " place", " location", " now", " please")
        for (filler in trailingFillers) {
            if (cleaned.endsWith(filler, ignoreCase = true)) {
                cleaned = cleaned.substring(0, cleaned.length - filler.length).trim()
            }
        }

        return cleaned
    }

    private fun isIgnoredGenericWord(word: String): Boolean {
        val lower = word.lowercase(Locale.US)
        return lower in listOf("here", "it", "this", "that", "there", "something", "anything")
    }
}
