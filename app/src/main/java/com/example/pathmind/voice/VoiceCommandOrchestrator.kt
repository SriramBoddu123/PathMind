package com.example.pathmind.voice

import android.content.Context
import android.content.Intent
import com.example.pathmind.NavigationActivity
import com.example.pathmind.data.MatchType
import com.example.pathmind.data.MemoryRepository
import com.example.pathmind.data.MemoryRetrievalEngine
import com.example.pathmind.data.RouteRepository
import com.example.pathmind.model.PlaceMemory
import com.example.pathmind.model.Route
import com.example.pathmind.model.VoiceAction
import com.example.pathmind.model.VoiceCommandResult
import com.example.pathmind.model.VoiceExecutionResult
import java.util.Locale

/**
 * Stage 8A / Stage 10 (Step 3): Voice Command Orchestrator.
 * Connects parsed voice/text intents to MemoryRetrievalEngine, RouteRepository, and Navigation contracts.
 * Zero external libraries; zero ASR dependencies.
 */
class VoiceCommandOrchestrator(
    private val context: Context? = null,
    private val memoryRepository: MemoryRepository? = null,
    private val routeRepository: RouteRepository? = null,
    private val parser: VoiceIntentParser = VoiceIntentParser(),
    private val retrievalEngine: MemoryRetrievalEngine = MemoryRetrievalEngine(),
    private val memoryProvider: (() -> List<PlaceMemory>)? = null,
    private val routeLookup: ((String) -> Route?)? = null,
    private val navIntentBuilder: ((routeId: String, segmentId: String, targetName: String) -> Intent?)? = null
) {

    private fun getMemoryRepo(): MemoryRepository = memoryRepository ?: MemoryRepository(context!!)
    private fun getRouteRepo(): RouteRepository = routeRepository ?: RouteRepository(context!!)

    /**
     * Parses and executes a voice or text command against the personal spatial memory store.
     */
    fun processCommand(
        transcript: String,
        activeRouteId: String? = null,
        activeSegmentId: String? = null,
        activeStepPosition: Int = 0
    ): VoiceExecutionResult {
        val parsed = parser.parse(transcript)
        return execute(parsed, activeRouteId, activeSegmentId, activeStepPosition)
    }

    /**
     * Executes a parsed VoiceCommandResult against the system repositories.
     */
    fun execute(
        command: VoiceCommandResult,
        activeRouteId: String? = null,
        activeSegmentId: String? = null,
        activeStepPosition: Int = 0
    ): VoiceExecutionResult {
        return when (command.action) {
            VoiceAction.REMEMBER_PLACE -> handleRememberPlace(command, activeRouteId, activeSegmentId, activeStepPosition)
            VoiceAction.FIND_PLACE -> handleFindPlace(command, activeRouteId)
            VoiceAction.START_ROUTE -> handleStartRoute(command, activeRouteId)
            VoiceAction.CANCEL -> VoiceExecutionResult(
                commandResult = command,
                success = true,
                displayMessage = "Command cancelled."
            )
            VoiceAction.UNKNOWN -> VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = command.feedbackMessage
            )
        }
    }

    private fun handleRememberPlace(
        command: VoiceCommandResult,
        activeRouteId: String?,
        activeSegmentId: String?,
        activeStepPosition: Int
    ): VoiceExecutionResult {
        val entity = command.targetEntity ?: "Saved Spot"
        val formattedName = entity.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

        // Determine which route to anchor this memory to
        val targetRoute: Route? = if (!activeRouteId.isNullOrBlank()) {
            routeLookup?.invoke(activeRouteId) ?: getRouteRepo().getRouteById(activeRouteId)
        } else {
            routeLookup?.invoke("default") ?: getRouteRepo().getRoutes().firstOrNull()
        }

        if (targetRoute == null) {
            return VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = "Cannot save '$formattedName': No learned routes available. Please walk and save a route first."
            )
        }

        val targetSegmentId = if (!activeSegmentId.isNullOrBlank()) {
            activeSegmentId
        } else {
            targetRoute.segments.firstOrNull()?.id ?: "start"
        }

        val targetStepPos = if (activeStepPosition > 0) activeStepPosition else 0

        // Duplicate protection: prevent accidental rapid duplicate submissions (< 5 seconds) along the same route
        val recentMemories = memoryProvider?.invoke() ?: getMemoryRepo().getAllMemories()
        val now = System.currentTimeMillis()
        val recentDuplicate = recentMemories.find {
            it.name.equals(formattedName, ignoreCase = true) &&
            it.routeId == targetRoute.id &&
            (now - it.createdAt) < 5000L
        }
        if (recentDuplicate != null) {
            return VoiceExecutionResult(
                commandResult = command,
                success = true,
                savedMemory = recentDuplicate,
                displayMessage = "Place '$formattedName' already anchored along route '${targetRoute.name}'."
            )
        }

        val placeMemory = PlaceMemory(
            name = formattedName,
            routeId = targetRoute.id,
            segmentId = targetSegmentId,
            stepPosition = targetStepPos,
            confidence = 95,
            description = "Anchored via voice command: \"${command.rawTranscript}\""
        )

        val saved = getMemoryRepo().saveMemory(placeMemory)
        return if (saved) {
            VoiceExecutionResult(
                commandResult = command,
                success = true,
                savedMemory = placeMemory,
                displayMessage = "Saved place '$formattedName' anchored along route '${targetRoute.name}'."
            )
        } else {
            VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = "Error saving memory for '$formattedName'."
            )
        }
    }

    private fun handleFindPlace(
        command: VoiceCommandResult,
        activeRouteId: String? = null
    ): VoiceExecutionResult {
        val query = command.targetEntity ?: ""
        if (query.isBlank()) {
            return VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = "Please specify a place to find."
            )
        }

        val memories = memoryProvider?.invoke() ?: getMemoryRepo().getAllMemories()
        if (memories.isEmpty()) {
            return VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = "You haven't remembered any places yet. Try: 'Remember where I parked my bike'."
            )
        }

        // Stage 10: Smart retrieval using MemoryRetrievalEngine with contextual route affinity
        val candidates = retrievalEngine.retrieve(
            query = query,
            memories = memories,
            currentRouteId = activeRouteId,
            minScoreThreshold = 0.40f
        )

        if (candidates.isEmpty()) {
            val availableNames = memories.take(4).joinToString { "'${it.name}'" }
            return VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = "Could not find any place matching '$query'. Saved places: $availableNames."
            )
        }

        // Check for Ambiguity vs Single Strong Match
        val isAmbiguous = candidates.size >= 2 && run {
            val best = candidates[0]
            val second = candidates[1]
            // An exact match decisively beats an alias or token match
            if (best.matchType == MatchType.EXACT && second.matchType != MatchType.EXACT) {
                false
            } else {
                (best.score - second.score) < 0.08f
            }
        }

        if (isAmbiguous) {
            val candidatePlaces = candidates.filter { (candidates[0].score - it.score) < 0.08f }.map { it.memory }
            return VoiceExecutionResult(
                commandResult = command,
                success = true,
                displayMessage = "Multiple places match '$query'. Please select one: ${candidatePlaces.joinToString { "'${it.name}'" }}",
                candidateMemories = candidatePlaces,
                requiresSelection = true,
                retrievalScore = candidates[0].score,
                matchType = candidates[0].matchType.name,
                matchReason = "Multiple close matches found"
            )
        }

        // Single Strong Match
        val best = candidates[0]
        val matchedMemory = best.memory
        val route = routeLookup?.invoke(matchedMemory.routeId) ?: getRouteRepo().getRouteById(matchedMemory.routeId)
        if (route == null) {
            return VoiceExecutionResult(
                commandResult = command,
                success = false,
                savedMemory = matchedMemory,
                displayMessage = "Found '${matchedMemory.name}', but its associated route was deleted.",
                retrievalScore = best.score,
                matchType = best.matchType.name,
                matchReason = best.matchReason
            )
        }

        // Prepare navigation launch intent (using custom builder in tests or platform Intent in production)
        val navIntent = navIntentBuilder?.invoke(route.id, matchedMemory.segmentId, matchedMemory.name)
            ?: if (context != null) {
                Intent(context, NavigationActivity::class.java).apply {
                    putExtra(NavigationActivity.EXTRA_ROUTE_ID, route.id)
                    putExtra(NavigationActivity.EXTRA_TARGET_SEGMENT_ID, matchedMemory.segmentId)
                    putExtra(NavigationActivity.EXTRA_TARGET_NAME, matchedMemory.name)
                }
            } else null

        return VoiceExecutionResult(
            commandResult = command,
            success = true,
            savedMemory = matchedMemory,
            navigationIntent = navIntent,
            displayMessage = "Found '${matchedMemory.name}' along route '${route.name}'. Ready to navigate.",
            requiresSelection = false,
            retrievalScore = best.score,
            matchType = best.matchType.name,
            matchReason = best.matchReason
        )
    }

    private fun handleStartRoute(command: VoiceCommandResult, activeRouteId: String? = null): VoiceExecutionResult {
        val query = command.targetEntity?.trim() ?: ""
        val routes = getRouteRepo().getRoutes()
        if (routes.isEmpty()) {
            return VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = "No saved routes available. Learn a route first."
            )
        }

        val activeRoute = if (!activeRouteId.isNullOrBlank()) {
            routeLookup?.invoke(activeRouteId) ?: getRouteRepo().getRouteById(activeRouteId)
        } else null

        // If no specific route name was provided (e.g. "Start my route", "Begin navigation"),
        // default to active route or the latest learned route.
        val matchedRoute = if (query.isBlank() || query.equals("my route", ignoreCase = true) || query.equals("the route", ignoreCase = true) || query.equals("route", ignoreCase = true)) {
            activeRoute ?: routes.firstOrNull()
        } else {
            routes.find { it.name.equals(query, ignoreCase = true) }
                ?: routes.find { it.name.contains(query, ignoreCase = true) || query.contains(it.name, ignoreCase = true) }
        }

        if (matchedRoute == null) {
            val routeNames = routes.take(3).joinToString { "'${it.name}'" }
            return VoiceExecutionResult(
                commandResult = command,
                success = false,
                displayMessage = "Route '$query' not found. Available routes: $routeNames."
            )
        }

        val navIntent = if (context != null) {
            Intent(context, NavigationActivity::class.java).apply {
                putExtra(NavigationActivity.EXTRA_ROUTE_ID, matchedRoute.id)
            }
        } else null

        return VoiceExecutionResult(
            commandResult = command,
            success = true,
            navigationIntent = navIntent,
            displayMessage = "Starting route '${matchedRoute.name}'."
        )
    }
}
