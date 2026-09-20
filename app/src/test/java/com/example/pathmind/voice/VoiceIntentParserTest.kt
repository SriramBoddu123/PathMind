package com.example.pathmind.voice

import com.example.pathmind.model.VoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for deterministic VoiceIntentParser.
 * Validates natural language parsing, entity slot filling, and fallback handling.
 */
class VoiceIntentParserTest {

    private lateinit var parser: VoiceIntentParser

    @Before
    fun setUp() {
        parser = VoiceIntentParser()
    }

    @Test
    fun testCommand1_RememberWhereIParkedMyBike() {
        val result = parser.parse("Remember where I parked my bike.")
        assertEquals(VoiceAction.REMEMBER_PLACE, result.action)
        assertEquals("bike", result.targetEntity)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun testCommand1_RememberPlaceVariants() {
        val cases = mapOf(
            "Remember where I left my car" to "car",
            "Remember where I put my keys" to "keys",
            "Save this place as Library" to "library",
            "Save current spot as Canteen" to "canteen",
            "Remember my bike" to "bike",
            "Mark this spot as Lab" to "lab",
            "Save bike location here" to "bike"
        )

        for ((utterance, expectedEntity) in cases) {
            val res = parser.parse(utterance)
            assertEquals("Failed for: '$utterance'", VoiceAction.REMEMBER_PLACE, res.action)
            assertEquals("Entity mismatch for: '$utterance'", expectedEntity, res.targetEntity)
            assertTrue(res.confidence >= 0.9f)
        }
    }

    @Test
    fun testCommand2_WhereDidIParkMyBike() {
        val result = parser.parse("Where did I park my bike?")
        assertEquals(VoiceAction.FIND_PLACE, result.action)
        assertEquals("bike", result.targetEntity)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun testCommand2_FindPlaceVariants() {
        val cases = mapOf(
            "Where did I leave my bike" to "bike",
            "Where is my bike?" to "bike",
            "Where are my keys" to "keys",
            "Take me to my bike" to "bike",
            "Navigate to library" to "library",
            "Guide me to the canteen" to "canteen",
            "Find my bike" to "bike",
            "How do I get to my bike" to "bike"
        )

        for ((utterance, expectedEntity) in cases) {
            val res = parser.parse(utterance)
            assertEquals("Failed for: '$utterance'", VoiceAction.FIND_PLACE, res.action)
            assertEquals("Entity mismatch for: '$utterance'", expectedEntity, res.targetEntity)
            assertTrue(res.confidence >= 0.9f)
        }
    }

    @Test
    fun testStartRouteCommand() {
        val result = parser.parse("Start route sai")
        assertEquals(VoiceAction.START_ROUTE, result.action)
        assertEquals("sai", result.targetEntity)

        val result2 = parser.parse("Begin route library")
        assertEquals(VoiceAction.START_ROUTE, result2.action)
        assertEquals("library", result2.targetEntity)

        // Stage 8C general route commands
        val result3 = parser.parse("Start my route.")
        assertEquals(VoiceAction.START_ROUTE, result3.action)
        assertEquals(null, result3.targetEntity)

        val result4 = parser.parse("Start the route")
        assertEquals(VoiceAction.START_ROUTE, result4.action)
        assertEquals(null, result4.targetEntity)

        val result5 = parser.parse("Begin navigation")
        assertEquals(VoiceAction.START_ROUTE, result5.action)
        assertEquals(null, result5.targetEntity)

        val result6 = parser.parse("Begin navigation along sai")
        assertEquals(VoiceAction.START_ROUTE, result6.action)
        assertEquals("sai", result6.targetEntity)
    }

    @Test
    fun testCancelCommand() {
        val cancels = listOf("cancel", "cancel.", "stop", "stop.", "dismiss", "never mind", "exit", "close")
        for (c in cancels) {
            val res = parser.parse(c)
            assertEquals("Failed for: '$c'", VoiceAction.CANCEL, res.action)
            assertEquals(1.0f, res.confidence, 0.01f)
        }
    }

    @Test
    fun testUnknownCommands() {
        val unknowns = listOf(
            "What is the weather today?",
            "Play music",
            "Call mom",
            "",
            "   "
        )
        for (u in unknowns) {
            val res = parser.parse(u)
            assertEquals("Failed for: '$u'", VoiceAction.UNKNOWN, res.action)
            assertEquals(0.0f, res.confidence, 0.01f)
        }
    }
}
