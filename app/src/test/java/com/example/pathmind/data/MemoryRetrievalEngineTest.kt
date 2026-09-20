package com.example.pathmind.data

import com.example.pathmind.model.PlaceMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryRetrievalEngineTest {

    private lateinit var engine: MemoryRetrievalEngine

    @Before
    fun setUp() {
        engine = MemoryRetrievalEngine()
    }

    private fun createMemory(
        name: String,
        id: String = name,
        routeId: String = "test_route",
        createdAt: Long = 1000L
    ): PlaceMemory {
        return PlaceMemory(
            id = id,
            name = name,
            createdAt = createdAt,
            routeId = routeId,
            segmentId = "test_seg",
            stepPosition = 10
        )
    }

    // ==========================================
    // STEP 1 REGRESSION TESTS (PRESERVED)
    // ==========================================

    @Test
    fun testExactMatch() {
        val memory = createMemory("bike")
        val candidate = engine.scoreCandidate(engine.normalize("bike"), memory)

        assertEquals(1.00f, candidate.score, 0.001f)
        assertEquals(MatchType.EXACT, candidate.matchType)
        assertEquals(memory, candidate.memory)
    }

    @Test
    fun testCaseInsensitiveMatch() {
        val memory = createMemory("Bike")
        val candidate = engine.scoreCandidate(engine.normalize("BIKE"), memory)

        assertEquals(1.00f, candidate.score, 0.001f)
        assertEquals(MatchType.EXACT, candidate.matchType)
    }

    @Test
    fun testWhitespaceNormalization() {
        val memory = createMemory("Bike Parking")
        val candidate = engine.scoreCandidate(engine.normalize("   bike    parking   "), memory)

        assertEquals(1.00f, candidate.score, 0.001f)
        assertEquals(MatchType.EXACT, candidate.matchType)
    }

    @Test
    fun testPunctuationStripping() {
        val memory = createMemory("Bike's Spot!")
        val candidate = engine.scoreCandidate(engine.normalize("bikes spot"), memory)

        assertEquals(1.00f, candidate.score, 0.001f)
        assertEquals(MatchType.EXACT, candidate.matchType)
    }

    @Test
    fun testAliasMatch_BicycleToBike() {
        val memory = createMemory("Bike")
        val candidate = engine.scoreCandidate(engine.normalize("bicycle"), memory)

        assertEquals(0.95f, candidate.score, 0.001f)
        assertEquals(MatchType.ALIAS, candidate.matchType)
        assertTrue(candidate.matchReason.contains("bicycle"))
    }

    @Test
    fun testAliasMatch_CafeteriaToCanteen() {
        val memory = createMemory("Canteen")
        val candidate = engine.scoreCandidate(engine.normalize("cafeteria"), memory)

        assertEquals(0.95f, candidate.score, 0.001f)
        assertEquals(MatchType.ALIAS, candidate.matchType)
        assertTrue(candidate.matchReason.contains("cafeteria"))
    }

    @Test
    fun testControlledAliases_CarVehicleGateParking() {
        // car <-> vehicle
        val carMemory = createMemory("Car")
        val carCandidate = engine.scoreCandidate(engine.normalize("vehicle"), carMemory)
        assertEquals(0.95f, carCandidate.score, 0.001f)
        assertEquals(MatchType.ALIAS, carCandidate.matchType)

        // gate <-> entrance
        val gateMemory = createMemory("Main Gate")
        val entranceMatch = engine.scoreCandidate(engine.normalize("main entrance"), gateMemory)
        assertTrue("Token alias should match main entrance to main gate", entranceMatch.score >= 0.85f)

        // parking <-> parking area
        val parkMemory = createMemory("Parking Area")
        val parkCandidate = engine.scoreCandidate(engine.normalize("parking"), parkMemory)
        assertEquals(0.95f, parkCandidate.score, 0.001f)
        assertEquals(MatchType.ALIAS, parkCandidate.matchType)
    }

    @Test
    fun testMultiWordMatch() {
        val memory = createMemory("My Bike Parking")
        val candidate = engine.scoreCandidate(engine.normalize("bike parking"), memory)

        assertTrue("Multi-word tokens should produce strong match >= 0.90", candidate.score >= 0.90f)
        assertEquals(MatchType.TOKEN_OVERLAP, candidate.matchType)
    }

    @Test
    fun testSimpleTypo_BkieToBike() {
        val memory = createMemory("Bike")
        val candidate = engine.scoreCandidate(engine.normalize("bkie"), memory)

        assertTrue("Typo 'bkie' should match 'bike' with score between 0.65 and 0.85", candidate.score in 0.65f..0.85f)
        assertEquals(MatchType.FUZZY_TYPO, candidate.matchType)
    }

    @Test
    fun testConservativeTypoRejection_ShortWords() {
        // Length <= 3 words should NOT allow typos (prevents false matches like "cat" to "car")
        val memory = createMemory("Car")
        val candidate = engine.scoreCandidate(engine.normalize("cat"), memory)

        assertEquals(0.00f, candidate.score, 0.001f)
        assertEquals(MatchType.NONE, candidate.matchType)
    }

    @Test
    fun testUnrelatedQueryReceivesLowOrZeroScore() {
        val memory = createMemory("Bike")
        val candidate = engine.scoreCandidate(engine.normalize("library"), memory)

        assertEquals(0.00f, candidate.score, 0.001f)
        assertEquals(MatchType.NONE, candidate.matchType)
    }

    @Test
    fun testRanking_ExactMatchBeatsWeakerCandidates() {
        val memExact = createMemory("Bike", id = "mem_exact")
        val memAlias = createMemory("Bicycle", id = "mem_alias")
        val memMulti = createMemory("Bike Parking Area", id = "mem_multi")
        val memTypo = createMemory("Bkie", id = "mem_typo")
        val memUnrelated = createMemory("Campus Library", id = "mem_unrelated")

        val memories = listOf(memTypo, memUnrelated, memMulti, memAlias, memExact)

        val ranked = engine.retrieve("bike", memories)

        // 1. Exact match should be first with score 1.00
        assertEquals("mem_exact", ranked[0].memory.id)
        assertEquals(1.00f, ranked[0].baseScore, 0.001f)
        assertEquals(MatchType.EXACT, ranked[0].matchType)

        // 2. Alias match should beat partial/multi-word
        assertEquals("mem_alias", ranked[1].memory.id)
        assertEquals(0.95f, ranked[1].baseScore, 0.001f)
        assertEquals(MatchType.ALIAS, ranked[1].matchType)

        // 3. Multi-word should beat typo
        assertEquals("mem_multi", ranked[2].memory.id)
        assertTrue(ranked[2].baseScore < ranked[1].baseScore)
        assertEquals(MatchType.TOKEN_OVERLAP, ranked[2].matchType)

        // 4. Typo match should be next
        assertEquals("mem_typo", ranked[3].memory.id)
        assertTrue(ranked[3].baseScore < ranked[2].baseScore)
        assertEquals(MatchType.FUZZY_TYPO, ranked[3].matchType)

        // 5. Unrelated memory should NOT even be in candidates list (below threshold 0.30)
        assertTrue("Unrelated memory must not appear in retrieve() candidates", ranked.none { it.memory.id == "mem_unrelated" })
    }

    @Test
    fun testFindBestMatch() {
        val memories = listOf(
            createMemory("North Gate"),
            createMemory("Bike Stand"),
            createMemory("Library Reading Room")
        )

        val match = engine.findBestMatch("cycle stand", memories)
        assertNotNull(match)
        assertEquals("Bike Stand", match!!.memory.name)
        assertTrue(match.score >= 0.85f)

        val noMatch = engine.findBestMatch("swimming pool", memories)
        assertNull("Query with no matching memories should return null", noMatch)
    }

    @Test
    fun testEmptyInputs() {
        val memories = listOf(createMemory("Bike"))
        assertTrue(engine.retrieve("", memories).isEmpty())
        assertTrue(engine.retrieve("   ", memories).isEmpty())
        assertTrue(engine.retrieve("bike", emptyList()).isEmpty())
    }

    // ==========================================
    // STEP 2 CONTEXT-AWARE RANKING TESTS
    // ==========================================

    @Test
    fun testRouteAffinity_SameRouteReceivesBonus() {
        val memCampus = createMemory("My Bike", id = "bike_campus", routeId = "route_campus")
        val memParking = createMemory("My Bike", id = "bike_parking", routeId = "route_parking")

        val memories = listOf(memParking, memCampus)

        // Query with route_campus active
        val ranked = engine.retrieve("my bike", memories, currentRouteId = "route_campus")

        assertEquals(2, ranked.size)
        assertEquals("bike_campus", ranked[0].memory.id)
        assertTrue("Campus bike should receive route affinity bonus", ranked[0].contextBonus >= 0.03f)
        assertEquals(0.0f, ranked[1].contextBonus, 0.001f)
        assertTrue(ranked[0].score > ranked[1].score)
        assertTrue(ranked[0].matchReason.contains("route affinity"))
    }

    @Test
    fun testRouteAffinity_UnrelatedRouteDoesNotReceiveBonus() {
        val memCampus = createMemory("My Bike", id = "bike_campus", routeId = "route_campus")
        val memParking = createMemory("My Bike", id = "bike_parking", routeId = "route_parking")

        val memories = listOf(memParking, memCampus)

        // Query with an unrelated route active
        val ranked = engine.retrieve("my bike", memories, currentRouteId = "route_library")

        assertEquals(2, ranked.size)
        assertEquals(0.0f, ranked[0].contextBonus, 0.001f)
        assertEquals(0.0f, ranked[1].contextBonus, 0.001f)
    }

    @Test
    fun testNoRouteContext_BehavesEssentiallyLikeStep1() {
        val mem1 = createMemory("Bike", id = "b1", routeId = "r1", createdAt = 1000L)
        val mem2 = createMemory("Bike", id = "b2", routeId = "r2", createdAt = 1000L)

        val ranked = engine.retrieve("bike", listOf(mem1, mem2), currentRouteId = null)

        assertEquals(2, ranked.size)
        assertEquals(0.0f, ranked[0].contextBonus, 0.001f)
        assertEquals(0.0f, ranked[1].contextBonus, 0.001f)
        assertEquals(1.00f, ranked[0].score, 0.001f)
        assertEquals(1.00f, ranked[1].score, 0.001f)
    }

    @Test
    fun testWeakRecency_BreaksCloseTie() {
        val memOlder = createMemory("Bike", id = "older_bike", createdAt = 1000L)
        val memNewer = createMemory("Bike", id = "newer_bike", createdAt = 5000L)

        val memories = listOf(memOlder, memNewer)

        val ranked = engine.retrieve("bike", memories, currentRouteId = null)

        assertEquals(2, ranked.size)
        // Both have exact baseScore 1.00f, but newer breaks the tie
        assertEquals("newer_bike", ranked[0].memory.id)
        assertTrue("Newer memory should receive small recency bonus", ranked[0].contextBonus > 0.0f)
        assertTrue("Newer memory score should be slightly higher", ranked[0].score > ranked[1].score)
        assertTrue(ranked[0].matchReason.contains("recency"))
    }

    @Test
    fun testWeakRecency_CannotBeatSignificantlyStrongerMatch() {
        val memExactOlder = createMemory("Bike", id = "exact_older", createdAt = 1000L)
        val memTypoNewer = createMemory("Bkie", id = "typo_newer", createdAt = 5000L)

        val memories = listOf(memTypoNewer, memExactOlder)

        val ranked = engine.retrieve("bike", memories, currentRouteId = null)

        // Exact match (1.00) must decisively beat typo (0.76 + recency <= 0.78)
        assertEquals("exact_older", ranked[0].memory.id)
        assertTrue(ranked[0].score > ranked[1].score)
        assertEquals(MatchType.EXACT, ranked[0].matchType)
    }

    @Test
    fun testCombinedContext_CannotBeatExactMatch() {
        // Exact match on different route, older
        val memExact = createMemory("Bike", id = "exact_other_route", routeId = "route_other", createdAt = 1000L)
        // Alias match on active route, newer (receives BOTH route bonus +0.03 AND recency bonus +0.015 = +0.045)
        val memAlias = createMemory("Bicycle", id = "alias_active_route", routeId = "route_active", createdAt = 5000L)

        val memories = listOf(memAlias, memExact)

        val ranked = engine.retrieve("bike", memories, currentRouteId = "route_active")

        // Exact match base is 1.00. Alias base is 0.95 + 0.045 = 0.995.
        // Exact match MUST still win (1.000 > 0.995)
        assertEquals("exact_other_route", ranked[0].memory.id)
        assertEquals("alias_active_route", ranked[1].memory.id)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun testUnrelatedMemory_NeverReceivesContextBonus() {
        val memUnrelated = createMemory("Library", id = "unrelated", routeId = "route_active", createdAt = 5000L)

        // Query: "bike" on active route
        val ranked = engine.retrieve("bike", listOf(memUnrelated), currentRouteId = "route_active")

        // Unrelated place must NOT be promoted into results by route affinity
        assertTrue("Unrelated memory must remain excluded", ranked.isEmpty())
    }
}
