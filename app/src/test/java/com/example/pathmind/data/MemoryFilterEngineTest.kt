package com.example.pathmind.data

import com.example.pathmind.model.PlaceMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Stage 10, Step 5: Offline Memory Search & Route Filtering.
 *
 * Tests all required scenarios:
 * 1. Exact memory-name search
 * 2. Case-insensitive search
 * 3. Description search
 * 4. Punctuation and whitespace normalization
 * 5. Search with no results
 * 6. All Routes filter
 * 7. Specific route filter
 * 8. Combined search + route filter
 * 9. No-route memories appearing under All Routes
 * 10. Existing memory ordering/behavior (newest-first)
 * 11. Empty state classification
 * 12. Associated route ID deduplication
 */
class MemoryFilterEngineTest {

    private lateinit var memory1: PlaceMemory
    private lateinit var memory2: PlaceMemory
    private lateinit var memory3: PlaceMemory
    private lateinit var memoryUnrouted: PlaceMemory
    private lateinit var sampleMemories: List<PlaceMemory>

    @Before
    fun setUp() {
        memory1 = PlaceMemory(
            id = "mem_1",
            name = "My Bike",
            description = "Parked near bicycle stand and tree",
            createdAt = 1000L,
            routeId = "route_campus",
            segmentId = "seg_1",
            stepPosition = 12
        )
        memory2 = PlaceMemory(
            id = "mem_2",
            name = "Parking Area",
            description = "North Gate entrance lot",
            createdAt = 2000L,
            routeId = "route_campus",
            segmentId = "seg_2",
            stepPosition = 45
        )
        memory3 = PlaceMemory(
            id = "mem_3",
            name = "Library Entrance",
            description = "Study desk second floor",
            createdAt = 3000L,
            routeId = "route_library",
            segmentId = "seg_3",
            stepPosition = 80
        )
        memoryUnrouted = PlaceMemory(
            id = "mem_unrouted",
            name = "Cafeteria Snack Corner",
            description = "Coffee machine by north window",
            createdAt = 2500L,
            routeId = "",
            segmentId = "",
            stepPosition = 0
        )
        sampleMemories = listOf(memory1, memory2, memory3, memoryUnrouted)
    }

    // 1. Exact memory-name search
    @Test
    fun testExactMemoryNameSearch() {
        val result = MemoryFilterEngine.filterMemories(sampleMemories, "Parking Area", null)
        assertEquals(1, result.size)
        assertEquals("Parking Area", result[0].name)
    }

    // 2. Case-insensitive search
    @Test
    fun testCaseInsensitiveSearch() {
        val lowerResult = MemoryFilterEngine.filterMemories(sampleMemories, "my bike", null)
        assertEquals(1, lowerResult.size)
        assertEquals("My Bike", lowerResult[0].name)

        val upperResult = MemoryFilterEngine.filterMemories(sampleMemories, "PARKING AREA", null)
        assertEquals(1, upperResult.size)
        assertEquals("Parking Area", upperResult[0].name)

        val mixedResult = MemoryFilterEngine.filterMemories(sampleMemories, "lIbRaRy", null)
        assertEquals(1, mixedResult.size)
        assertEquals("Library Entrance", mixedResult[0].name)
    }

    // 3. Description search
    @Test
    fun testDescriptionSearch() {
        // "bicycle stand" is in memory1 description
        val result = MemoryFilterEngine.filterMemories(sampleMemories, "bicycle stand", null)
        assertEquals(1, result.size)
        assertEquals("My Bike", result[0].name)

        // "coffee machine" is in memoryUnrouted description
        val result2 = MemoryFilterEngine.filterMemories(sampleMemories, "coffee machine", null)
        assertEquals(1, result2.size)
        assertEquals("Cafeteria Snack Corner", result2[0].name)
    }

    // 4. Punctuation and whitespace normalization
    @Test
    fun testPunctuationAndWhitespaceNormalization() {
        // Query has extra whitespace and punctuation
        val queryPunct = "   my   bike!   "
        val result = MemoryFilterEngine.filterMemories(sampleMemories, queryPunct, null)
        assertEquals(1, result.size)
        assertEquals("My Bike", result[0].name)

        // Apostrophe tolerance
        assertEquals("bikes", MemoryFilterEngine.normalize("bike's"))
        assertEquals("parking area 2", MemoryFilterEngine.normalize("  Parking - Area   #2!  "))
    }

    // 5. Search with no results
    @Test
    fun testSearchWithNoResults() {
        val result = MemoryFilterEngine.filterMemories(sampleMemories, "nonexistent submarine spot", null)
        assertTrue(result.isEmpty())
    }

    // 6. All Routes filter
    @Test
    fun testAllRoutesFilter() {
        // Passing null or blank for selectedRouteId should return all memories
        val resultNull = MemoryFilterEngine.filterMemories(sampleMemories, null, null)
        assertEquals(4, resultNull.size)

        val resultBlank = MemoryFilterEngine.filterMemories(sampleMemories, "", "")
        assertEquals(4, resultBlank.size)
    }

    // 7. Specific route filter
    @Test
    fun testSpecificRouteFilter() {
        val campusResult = MemoryFilterEngine.filterMemories(sampleMemories, null, "route_campus")
        assertEquals(2, campusResult.size)
        assertTrue(campusResult.all { it.routeId == "route_campus" })

        val libraryResult = MemoryFilterEngine.filterMemories(sampleMemories, null, "route_library")
        assertEquals(1, libraryResult.size)
        assertEquals("Library Entrance", libraryResult[0].name)
    }

    // 8. Combined search + route filter
    @Test
    fun testCombinedSearchAndRouteFilter() {
        // Search "north" matches memory2 (desc: North Gate entrance lot) and memoryUnrouted (desc: north window)
        // Filtering by "route_campus" restricts to only memory2
        val combinedResult = MemoryFilterEngine.filterMemories(sampleMemories, "north", "route_campus")
        assertEquals(1, combinedResult.size)
        assertEquals("Parking Area", combinedResult[0].name)

        // Search "gate" with route_campus matches memory2
        val gateCampus = MemoryFilterEngine.filterMemories(sampleMemories, "gate", "route_campus")
        assertEquals(1, gateCampus.size)
        assertEquals("Parking Area", gateCampus[0].name)

        // Search "gate" with route_library matches nothing
        val gateLibrary = MemoryFilterEngine.filterMemories(sampleMemories, "gate", "route_library")
        assertTrue(gateLibrary.isEmpty())
    }

    // 9. No-route memories appearing under All Routes
    @Test
    fun testNoRouteMemoriesAppearUnderAllRoutes() {
        val allResult = MemoryFilterEngine.filterMemories(sampleMemories, null, null)
        assertTrue(allResult.any { it.id == "mem_unrouted" })

        // But not under a specific route
        val campusResult = MemoryFilterEngine.filterMemories(sampleMemories, null, "route_campus")
        assertFalse(campusResult.any { it.id == "mem_unrouted" })
    }

    // 10. Existing memory ordering/behavior (newest-first)
    @Test
    fun testNewestFirstOrderingPreserved() {
        val result = MemoryFilterEngine.filterMemories(sampleMemories, null, null)
        // Expected order: memory3 (3000L), memoryUnrouted (2500L), memory2 (2000L), memory1 (1000L)
        assertEquals("mem_3", result[0].id)
        assertEquals("mem_unrouted", result[1].id)
        assertEquals("mem_2", result[2].id)
        assertEquals("mem_1", result[3].id)
    }

    // 11. Empty state classification
    @Test
    fun testEmptyStateClassification() {
        // Case A: Items present
        val stateWithItems = MemoryFilterEngine.determineEmptyState(
            totalCount = 4,
            filteredCount = 2,
            query = "bike",
            selectedRouteId = "route_campus"
        )
        assertEquals(MemoryEmptyStateType.NONE, stateWithItems)

        // Case B: No memories saved in repository
        val stateNoMemories = MemoryFilterEngine.determineEmptyState(
            totalCount = 0,
            filteredCount = 0,
            query = "",
            selectedRouteId = null
        )
        assertEquals(MemoryEmptyStateType.NO_MEMORIES_SAVED, stateNoMemories)

        // Case C: Search produced no results
        val stateSearchEmpty = MemoryFilterEngine.determineEmptyState(
            totalCount = 4,
            filteredCount = 0,
            query = "submarine",
            selectedRouteId = null
        )
        assertEquals(MemoryEmptyStateType.NO_MATCHING_SEARCH, stateSearchEmpty)

        // Case D: Route filter produced no results (with no search query)
        val stateRouteEmpty = MemoryFilterEngine.determineEmptyState(
            totalCount = 4,
            filteredCount = 0,
            query = "",
            selectedRouteId = "route_empty"
        )
        assertEquals(MemoryEmptyStateType.NO_MEMORIES_ON_ROUTE, stateRouteEmpty)
    }

    // 12. Associated route ID extraction and deduplication
    @Test
    fun testGetAssociatedRouteIds() {
        val routeIds = MemoryFilterEngine.getAssociatedRouteIds(sampleMemories)
        // Should contain route_campus and route_library, without duplicates and without blank
        assertEquals(2, routeIds.size)
        assertTrue(routeIds.contains("route_campus"))
        assertTrue(routeIds.contains("route_library"))
        assertFalse(routeIds.contains(""))
    }
}
