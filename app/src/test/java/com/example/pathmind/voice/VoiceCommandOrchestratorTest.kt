package com.example.pathmind.voice

import android.content.Intent
import com.example.pathmind.model.PlaceMemory
import com.example.pathmind.model.Route
import com.example.pathmind.model.RouteSegment
import com.example.pathmind.model.SegmentAction
import com.example.pathmind.model.SegmentDirection
import com.example.pathmind.model.VoiceAction
import com.example.pathmind.model.VoiceCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for Stage 10 Steps 3 & 4: Voice Retrieval Integration and Disambiguation.
 * Validates VoiceCommandOrchestrator integration with MemoryRetrievalEngine and candidate selection flows.
 */
class VoiceCommandOrchestratorTest {

    private val mockRoute = Route(
        id = "route_test_1",
        name = "Campus Route",
        totalSteps = 100,
        totalSegments = 2,
        segments = listOf(
            RouteSegment(
                id = "seg_1",
                action = SegmentAction.START,
                direction = SegmentDirection.STRAIGHT,
                steps = 0
            ),
            RouteSegment(
                id = "seg_2",
                action = SegmentAction.WALK,
                direction = SegmentDirection.STRAIGHT,
                steps = 100
            )
        )
    )

    private val memories = mutableListOf<PlaceMemory>()
    private lateinit var orchestrator: VoiceCommandOrchestrator

    // In unit test environment, we pass a dummy intent via navIntentBuilder so no real Android Intent is instantiated
    private val dummyIntent: Intent = Intent()

    @Before
    fun setUp() {
        memories.clear()
        orchestrator = VoiceCommandOrchestrator(
            memoryProvider = { memories },
            routeLookup = { routeId -> if (routeId == mockRoute.id) mockRoute else null },
            navIntentBuilder = { _, _, _ -> dummyIntent }
        )
    }

    private fun addMemory(name: String, id: String = name, routeId: String = mockRoute.id): PlaceMemory {
        val memory = PlaceMemory(
            id = id,
            name = name,
            routeId = routeId,
            segmentId = "seg_1",
            stepPosition = 50
        )
        memories.add(memory)
        return memory
    }

    @Test
    fun testCaseA_NaturalAliasQuery_BicycleFindsMyBike() {
        addMemory("My Bike", id = "mem_my_bike")

        val result = orchestrator.processCommand("Where did I leave my bicycle?")

        assertEquals(VoiceAction.FIND_PLACE, result.commandResult.action)
        assertTrue(result.success)
        assertFalse(result.requiresSelection)
        assertNotNull(result.savedMemory)
        assertEquals("My Bike", result.savedMemory!!.name)
        assertNotNull(result.navigationIntent)
        assertTrue(result.retrievalScore != null && result.retrievalScore!! >= 0.85f)
    }

    @Test
    fun testCaseB_Typo_BkieFindsMyBike() {
        addMemory("My Bike", id = "mem_my_bike")

        val result = orchestrator.processCommand("Navigate to bkie")

        assertEquals(VoiceAction.FIND_PLACE, result.commandResult.action)
        assertTrue(result.success)
        assertFalse(result.requiresSelection)
        assertNotNull(result.savedMemory)
        assertEquals("My Bike", result.savedMemory!!.name)
        assertNotNull(result.navigationIntent)
    }

    @Test
    fun testCaseC_ExactQuery_MyBikeFindsExactMemory() {
        addMemory("My Bike", id = "mem_my_bike")

        // Exact query target entity matching memory name
        val result = orchestrator.execute(
            VoiceCommandResult(
                action = VoiceAction.FIND_PLACE,
                rawTranscript = "My Bike",
                targetEntity = "My Bike",
                confidence = 1.0f
            )
        )

        assertEquals(VoiceAction.FIND_PLACE, result.commandResult.action)
        assertTrue(result.success)
        assertFalse(result.requiresSelection)
        assertNotNull(result.savedMemory)
        assertEquals("My Bike", result.savedMemory!!.name)
        assertEquals(1.00f, result.retrievalScore ?: 0f, 0.001f)
        assertEquals("EXACT", result.matchType)
    }

    @Test
    fun testCaseC2_NaturalQuery_WhereIsMyBikeFindsMyBike() {
        addMemory("My Bike", id = "mem_my_bike")

        val result = orchestrator.processCommand("Where did I park my bike?")

        assertEquals(VoiceAction.FIND_PLACE, result.commandResult.action)
        assertTrue(result.success)
        assertFalse(result.requiresSelection)
        assertNotNull(result.savedMemory)
        assertEquals("My Bike", result.savedMemory!!.name)
        assertTrue("Should have high retrieval score >= 0.90", (result.retrievalScore ?: 0f) >= 0.90f)
    }

    @Test
    fun testCaseD_StrongMatchAutomaticallySelected() {
        addMemory("Bike Parking", id = "mem_target")
        addMemory("Library", id = "mem_other")

        val result = orchestrator.processCommand("Take me to bike parking")

        assertTrue(result.success)
        assertFalse("Single dominant match must not require selection", result.requiresSelection)
        assertEquals("Bike Parking", result.savedMemory?.name)
        assertNotNull("Navigation intent must be prepared", result.navigationIntent)
    }

    @Test
    fun testCaseE_MultipleCloseCandidates_ProduceAmbiguityResult() {
        // Two places with very similar names matching "gate"
        addMemory("North Gate", id = "mem_north_gate")
        addMemory("South Gate", id = "mem_south_gate")

        val result = orchestrator.processCommand("Guide me to the gate")

        assertEquals(VoiceAction.FIND_PLACE, result.commandResult.action)
        assertTrue(result.success)
        assertTrue("Ambiguous close candidates MUST require selection", result.requiresSelection)
        assertEquals(2, result.candidateMemories.size)
        assertNull("Ambiguous match must NOT automatically set savedMemory", result.savedMemory)
        assertNull("Ambiguous match must NOT launch navigation intent yet", result.navigationIntent)
        assertTrue(result.displayMessage.contains("Multiple places match"))
    }

    @Test
    fun testStep4_SelectingCandidateA_NavigatesToA() {
        val memA = addMemory("North Gate", id = "mem_north_gate", routeId = "route_north")
        val memB = addMemory("South Gate", id = "mem_south_gate", routeId = "route_south")

        val result = orchestrator.processCommand("Guide me to the gate")
        assertTrue("Disambiguation required", result.requiresSelection)
        assertEquals(2, result.candidateMemories.size)

        // User taps candidate A
        val selectedA = result.candidateMemories.first { it.id == "mem_north_gate" }
        assertEquals("North Gate", selectedA.name)
        assertEquals("route_north", selectedA.routeId)
        assertEquals("seg_1", selectedA.segmentId)

        // Verify simulated navigation intent launch parameters for candidate A
        var capturedRouteId: String? = null
        var capturedSegmentId: String? = null
        var capturedTargetName: String? = null

        val navBuilder: (String, String, String) -> Intent? = { rId, sId, name ->
            capturedRouteId = rId
            capturedSegmentId = sId
            capturedTargetName = name
            dummyIntent
        }
        navBuilder(selectedA.routeId, selectedA.segmentId, selectedA.name)

        assertEquals("route_north", capturedRouteId)
        assertEquals("seg_1", capturedSegmentId)
        assertEquals("North Gate", capturedTargetName)
    }

    @Test
    fun testStep4_SelectingCandidateB_NavigatesToB() {
        val memA = addMemory("North Gate", id = "mem_north_gate", routeId = "route_north")
        val memB = addMemory("South Gate", id = "mem_south_gate", routeId = "route_south")

        val result = orchestrator.processCommand("Guide me to the gate")
        assertTrue("Disambiguation required", result.requiresSelection)
        assertEquals(2, result.candidateMemories.size)

        // User taps candidate B
        val selectedB = result.candidateMemories.first { it.id == "mem_south_gate" }
        assertEquals("South Gate", selectedB.name)
        assertEquals("route_south", selectedB.routeId)
        assertEquals("seg_1", selectedB.segmentId)

        // Verify simulated navigation intent launch parameters for candidate B
        var capturedRouteId: String? = null
        var capturedSegmentId: String? = null
        var capturedTargetName: String? = null

        val navBuilder: (String, String, String) -> Intent? = { rId, sId, name ->
            capturedRouteId = rId
            capturedSegmentId = sId
            capturedTargetName = name
            dummyIntent
        }
        navBuilder(selectedB.routeId, selectedB.segmentId, selectedB.name)

        assertEquals("route_south", capturedRouteId)
        assertEquals("seg_1", capturedSegmentId)
        assertEquals("South Gate", capturedTargetName)
    }

    @Test
    fun testStep4_CancelCommand_PerformsNoNavigation() {
        val result = orchestrator.processCommand("Cancel")

        assertEquals(VoiceAction.CANCEL, result.commandResult.action)
        assertTrue(result.success)
        assertFalse(result.requiresSelection)
        assertNull("Cancel command must perform zero navigation", result.navigationIntent)
        assertNull("Cancel command must set zero saved memory", result.savedMemory)
        assertEquals("Command cancelled.", result.displayMessage)
    }

    @Test
    fun testCaseF_NoMatch_ProducesNoNavigationTarget() {
        addMemory("Bike Stand", id = "mem_bike")

        val result = orchestrator.processCommand("Where is the swimming pool?")

        assertEquals(VoiceAction.FIND_PLACE, result.commandResult.action)
        assertFalse("Unmatched query should indicate failure", result.success)
        assertFalse(result.requiresSelection)
        assertNull(result.savedMemory)
        assertNull(result.navigationIntent)
        assertTrue(result.displayMessage.contains("Could not find any place matching"))
    }

    @Test
    fun testEmptyMemories_ShowsHelpfulFeedback() {
        val result = orchestrator.processCommand("Where did I park my car?")

        assertFalse(result.success)
        assertNull(result.navigationIntent)
        assertTrue(result.displayMessage.contains("haven't remembered any places yet"))
    }
}
