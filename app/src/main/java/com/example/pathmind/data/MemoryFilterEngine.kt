package com.example.pathmind.data

import com.example.pathmind.model.PlaceMemory
import java.util.Locale

/**
 * Empty state types for My Memories screen.
 */
enum class MemoryEmptyStateType {
    NONE,                   // Filtered list is not empty
    NO_MEMORIES_SAVED,      // Total memories repository is empty ("NO MEMORIES YET")
    NO_MATCHING_SEARCH,     // Search produced no results ("NO MATCHING MEMORIES")
    NO_MEMORIES_ON_ROUTE    // Specific route selected produced no results ("NO MEMORIES ON THIS ROUTE")
}

/**
 * Stage 10 (Step 5): Offline, deterministic Memory Filter Engine.
 *
 * Provides:
 * 1. Normalized text search over memory name and description
 * 2. Case-insensitivity, punctuation tolerance, and whitespace collapse
 * 3. Route-based filtering (including unrouted memories under "All Routes")
 * 4. Combined search and route filtering
 * 5. Dynamic route option extraction from saved memories
 * 6. Deterministic empty-state classification
 *
 * Fully local with zero network, cloud, or external AI dependencies.
 */
object MemoryFilterEngine {

    val ALL_ROUTES_ID: String? = null

    /**
     * Normalizes text for search comparison:
     * - Convert to lowercase
     * - Remove apostrophes (e.g. "bike's" -> "bikes")
     * - Replace non-alphanumeric punctuation with spaces
     * - Collapse multiple whitespaces and trim
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lowercase(Locale.US)
            .replace("'", "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Checks if a memory matches the given search query.
     *
     * Searches both memory name and description.
     * Tolerant of punctuation, extra whitespace, and case.
     */
    fun matchesSearch(memory: PlaceMemory, query: String?): Boolean {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return true

        val normalizedName = normalize(memory.name)
        val normalizedDesc = normalize(memory.description)

        // Substring match on name or description
        if (normalizedName.contains(normalizedQuery) || normalizedDesc.contains(normalizedQuery)) {
            return true
        }

        // Token-level match: every query token must appear in name or description
        val tokens = normalizedQuery.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true

        val combined = "$normalizedName $normalizedDesc"
        return tokens.all { token -> combined.contains(token) }
    }

    /**
     * Checks if a memory matches the given route filter.
     *
     * If selectedRouteId is null (All Routes), matches all memories,
     * including memories with blank/missing routeId.
     * If selectedRouteId is a specific route, matches only memories with that routeId.
     */
    fun matchesRoute(memory: PlaceMemory, selectedRouteId: String?): Boolean {
        if (selectedRouteId == null || selectedRouteId.isBlank()) {
            return true
        }
        return memory.routeId == selectedRouteId
    }

    /**
     * Applies combined search and route filtering to a list of memories.
     *
     * Returns matching memories ordered newest-first (createdAt descending).
     */
    fun filterMemories(
        memories: List<PlaceMemory>,
        query: String?,
        selectedRouteId: String?
    ): List<PlaceMemory> {
        return memories.filter { memory ->
            matchesRoute(memory, selectedRouteId) && matchesSearch(memory, query)
        }.sortedByDescending { it.createdAt }
    }

    /**
     * Extracts distinct, non-blank route IDs associated with the saved memories.
     * Does not contain duplicate route IDs.
     */
    fun getAssociatedRouteIds(memories: List<PlaceMemory>): List<String> {
        return memories.map { it.routeId }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Determines which empty state should be displayed when filtered memories list is empty.
     */
    fun determineEmptyState(
        totalCount: Int,
        filteredCount: Int,
        query: String?,
        selectedRouteId: String?
    ): MemoryEmptyStateType {
        if (filteredCount > 0) {
            return MemoryEmptyStateType.NONE
        }
        if (totalCount == 0) {
            return MemoryEmptyStateType.NO_MEMORIES_SAVED
        }

        val hasSearchQuery = normalize(query).isNotEmpty()
        val hasSpecificRoute = selectedRouteId != null && selectedRouteId.isNotBlank()

        return when {
            hasSearchQuery -> MemoryEmptyStateType.NO_MATCHING_SEARCH
            hasSpecificRoute -> MemoryEmptyStateType.NO_MEMORIES_ON_ROUTE
            else -> MemoryEmptyStateType.NO_MEMORIES_SAVED
        }
    }
}
