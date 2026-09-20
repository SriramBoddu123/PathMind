package com.example.pathmind.sensor

import android.content.Context
import com.example.pathmind.model.MovementEvent
import com.example.pathmind.model.MovementState
import com.example.pathmind.model.Route
import com.example.pathmind.model.RouteSegment
import com.example.pathmind.model.SegmentAction
import com.example.pathmind.model.SegmentDirection
import com.example.pathmind.model.TurnDirection
import java.util.UUID
import kotlin.math.max

enum class LearningStatus {
    IDLE,
    LEARNING,
    PAUSED,
    COMPLETED
}

/**
 * Stage 3 Route Learning Engine.
 * Consumes real MovementEvent streams from MovementSensorEngine.
 * Partitions movement into structured segments: START, WALK, TURN, PAUSE, DESTINATION.
 */
class RouteLearningEngine(
    context: Context? = null,
    val sensorEngine: MovementSensorEngine = MovementSensorEngine(context)
) {

    companion object {
        // Calibration starting points for route segment learning
        const val DEFAULT_TURN_STREAK_THRESHOLD = 15      // ~300 ms of sustained turn evidence at 50Hz
        const val DEFAULT_MIN_TURN_CONFIDENCE = 70        // Minimum confidence required to recognize turn
        const val DEFAULT_MIN_TURN_DURATION_MS = 800L     // Minimum duration (ms) for a valid physical turn
        const val DEFAULT_MAX_TURN_DURATION_MS = 4000L    // Maximum duration (ms) for a turn before timeout
        const val DEFAULT_PAUSE_THRESHOLD_MS = 4000L      // Stillness duration (ms) to trigger a PAUSE
    }

    // Calibratable parameters
    var turnStreakThreshold: Int = DEFAULT_TURN_STREAK_THRESHOLD
    var minTurnConfidence: Int = DEFAULT_MIN_TURN_CONFIDENCE
    var minTurnDurationMs: Long = DEFAULT_MIN_TURN_DURATION_MS
    var maxTurnDurationMs: Long = DEFAULT_MAX_TURN_DURATION_MS
    var pauseThresholdMs: Long = DEFAULT_PAUSE_THRESHOLD_MS

    var learningStatus: LearningStatus = LearningStatus.IDLE
        private set

    var onSegmentListChanged: ((List<RouteSegment>) -> Unit)? = null
    var onTelemetryUpdated: ((movementEvent: MovementEvent, currentSegmentDesc: String, totalSteps: Int) -> Unit)? = null

    private val completedSegments = mutableListOf<RouteSegment>()

    // Current in-progress segment tracking
    private var activeAction: SegmentAction? = null
    private var activeDirection: SegmentDirection = SegmentDirection.STRAIGHT
    private var activeStartSteps: Int = 0
    private var activeStartTime: Long = 0L
    private val activeConfidences = mutableListOf<Int>()

    // Pending WALK segment tracking when entering a candidate TURN
    private var pendingWalkStartSteps: Int = 0
    private var pendingWalkStartTime: Long = 0L
    private val pendingWalkConfidences = mutableListOf<Int>()

    // Turn filtering & debounce
    private var turnCandidateDirection: TurnDirection = TurnDirection.NONE
    private var turnCandidateStreak: Int = 0
    private var turnCandidateStartTime: Long = 0L

    // Pause tracking
    private var stationaryStartTime: Long = 0L

    // Overall metrics
    private var routeTotalSteps: Int = 0
    private var lastRecordedSteps: Int = 0

    init {
        sensorEngine.onMovementUpdated = { event, _ ->
            processMovementEvent(event)
        }
    }

    fun startLearning(): Boolean {
        completedSegments.clear()
        activeConfidences.clear()
        pendingWalkConfidences.clear()
        routeTotalSteps = 0
        lastRecordedSteps = 0
        turnCandidateDirection = TurnDirection.NONE
        turnCandidateStreak = 0
        turnCandidateStartTime = 0L
        stationaryStartTime = 0L

        // Initial START segment
        val startSegment = RouteSegment(
            id = UUID.randomUUID().toString(),
            action = SegmentAction.START,
            direction = SegmentDirection.NONE,
            steps = 0,
            duration = 0L,
            confidence = 100
        )
        completedSegments.add(startSegment)
        onSegmentListChanged?.invoke(getDisplaySegments())

        // Begin active walking segment
        val now = System.currentTimeMillis()
        activeAction = SegmentAction.WALK
        activeDirection = SegmentDirection.STRAIGHT
        activeStartSteps = 0
        activeStartTime = now
        activeConfidences.add(90)

        pendingWalkStartSteps = 0
        pendingWalkStartTime = now
        pendingWalkConfidences.clear()
        pendingWalkConfidences.add(90)

        learningStatus = LearningStatus.LEARNING
        sensorEngine.resetSteps()
        sensorEngine.resetIntegratedYaw()
        sensorEngine.startListening()
        return true
    }

    fun pauseLearning() {
        if (learningStatus != LearningStatus.LEARNING) return

        finalizeCurrentActiveSegment()
        sensorEngine.stopListening()
        learningStatus = LearningStatus.PAUSED
        onSegmentListChanged?.invoke(getDisplaySegments())
    }

    fun resumeLearning() {
        if (learningStatus != LearningStatus.PAUSED) return

        sensorEngine.resetIntegratedYaw()
        sensorEngine.startListening()
        val now = System.currentTimeMillis()
        activeAction = SegmentAction.WALK
        activeDirection = SegmentDirection.STRAIGHT
        activeStartSteps = lastRecordedSteps
        activeStartTime = now
        activeConfidences.clear()
        activeConfidences.add(85)

        pendingWalkStartSteps = lastRecordedSteps
        pendingWalkStartTime = now
        pendingWalkConfidences.clear()
        pendingWalkConfidences.add(85)

        learningStatus = LearningStatus.LEARNING
        onSegmentListChanged?.invoke(getDisplaySegments())
    }

    fun stopAndCreateRoute(routeName: String): Route {
        finalizeCurrentActiveSegment()
        sensorEngine.stopListening()

        // Append DESTINATION segment
        val destSegment = RouteSegment(
            id = UUID.randomUUID().toString(),
            action = SegmentAction.DESTINATION,
            direction = SegmentDirection.NONE,
            steps = 0,
            duration = 0L,
            confidence = 100
        )
        completedSegments.add(destSegment)

        learningStatus = LearningStatus.COMPLETED
        onSegmentListChanged?.invoke(getDisplaySegments())

        val calculatedTotalSteps = completedSegments.sumOf { it.steps }

        return Route(
            id = UUID.randomUUID().toString(),
            name = routeName.ifBlank { "Recorded Route" },
            createdAt = System.currentTimeMillis(),
            totalSteps = calculatedTotalSteps,
            totalSegments = completedSegments.size,
            segments = ArrayList(completedSegments)
        )
    }

    fun cancelLearning() {
        sensorEngine.stopListening()
        learningStatus = LearningStatus.IDLE
        completedSegments.clear()
        activeAction = null
        turnCandidateDirection = TurnDirection.NONE
        turnCandidateStreak = 0
        turnCandidateStartTime = 0L
    }

    fun processMovementEvent(event: MovementEvent) {
        if (learningStatus != LearningStatus.LEARNING) return

        lastRecordedSteps = event.totalSteps
        routeTotalSteps = event.totalSteps

        if (event.confidence > 0) {
            activeConfidences.add(event.confidence)
        }

        // 1. Detect Turns with debouncing
        if (event.turnDirection == TurnDirection.LEFT || event.turnDirection == TurnDirection.RIGHT) {
            if (event.turnDirection == turnCandidateDirection) {
                turnCandidateStreak++
            } else {
                turnCandidateDirection = event.turnDirection
                turnCandidateStreak = 1
                turnCandidateStartTime = System.currentTimeMillis()
            }
        } else {
            turnCandidateStreak = 0
            turnCandidateDirection = TurnDirection.NONE
            turnCandidateStartTime = 0L
        }

        val isSignificantTurn = turnCandidateStreak >= turnStreakThreshold && event.confidence >= minTurnConfidence

        when (activeAction) {
            SegmentAction.WALK -> {
                stationaryStartTime = 0L

                if (isSignificantTurn) {
                    // Stash current WALK segment progress before switching to candidate TURN
                    pendingWalkStartSteps = activeStartSteps
                    pendingWalkStartTime = activeStartTime
                    pendingWalkConfidences.clear()
                    pendingWalkConfidences.addAll(activeConfidences)

                    // Start candidate TURN segment
                    activeAction = SegmentAction.TURN
                    activeDirection = if (event.turnDirection == TurnDirection.LEFT) SegmentDirection.LEFT else SegmentDirection.RIGHT
                    activeStartSteps = event.totalSteps
                    val now = System.currentTimeMillis()
                    activeStartTime = if (turnCandidateStartTime > 0L) turnCandidateStartTime else now
                    activeConfidences.clear()
                    activeConfidences.add(event.confidence)
                    turnCandidateStreak = 0
                    turnCandidateStartTime = 0L
                    onSegmentListChanged?.invoke(getDisplaySegments())
                } else if (event.movementState == MovementState.STATIONARY) {
                    // Check for pause
                    if (stationaryStartTime == 0L) {
                        stationaryStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - stationaryStartTime > pauseThresholdMs) {
                        // Finalize walk and switch to PAUSE
                        val walkSteps = max(0, event.totalSteps - activeStartSteps)
                        if (walkSteps > 0) {
                            val duration = System.currentTimeMillis() - activeStartTime
                            val avgConf = if (activeConfidences.isNotEmpty()) activeConfidences.average().toInt() else 85
                            completedSegments.add(
                                RouteSegment(
                                    action = SegmentAction.WALK,
                                    direction = SegmentDirection.STRAIGHT,
                                    steps = walkSteps,
                                    duration = duration,
                                    confidence = avgConf
                                )
                            )
                        }

                        activeAction = SegmentAction.PAUSE
                        activeDirection = SegmentDirection.NONE
                        activeStartSteps = event.totalSteps
                        activeStartTime = System.currentTimeMillis()
                        activeConfidences.clear()
                        activeConfidences.add(95)
                        onSegmentListChanged?.invoke(getDisplaySegments())
                    }
                }
            }

            SegmentAction.TURN -> {
                val duration = System.currentTimeMillis() - activeStartTime

                // If turn stopped before meeting minimum duration, it's a FALSE TURN:
                // Revert seamlessly back to the ongoing WALK segment without splitting it!
                if (event.turnDirection == TurnDirection.NONE && duration < minTurnDurationMs) {
                    activeAction = SegmentAction.WALK
                    activeDirection = SegmentDirection.STRAIGHT
                    activeStartSteps = pendingWalkStartSteps
                    activeStartTime = pendingWalkStartTime
                    activeConfidences.clear()
                    activeConfidences.addAll(pendingWalkConfidences)
                    if (event.confidence > 0) activeConfidences.add(event.confidence)
                    turnCandidateStreak = 0
                    turnCandidateDirection = TurnDirection.NONE
                    turnCandidateStartTime = 0L
                    onSegmentListChanged?.invoke(getDisplaySegments())
                    return
                }

                val turnCompleted = (event.turnDirection == TurnDirection.NONE && duration >= minTurnDurationMs) || duration >= maxTurnDurationMs

                if (turnCompleted) {
                    // Commit pending WALK segment if it had steps
                    val walkSteps = max(0, activeStartSteps - pendingWalkStartSteps)
                    if (walkSteps > 0) {
                        val walkDuration = max(0L, activeStartTime - pendingWalkStartTime)
                        val avgWalkConf = if (pendingWalkConfidences.isNotEmpty()) pendingWalkConfidences.average().toInt() else 85
                        completedSegments.add(
                            RouteSegment(
                                action = SegmentAction.WALK,
                                direction = SegmentDirection.STRAIGHT,
                                steps = walkSteps,
                                duration = walkDuration,
                                confidence = avgWalkConf
                            )
                        )
                    }

                    // Commit genuine TURN segment
                    val turnSteps = max(0, event.totalSteps - activeStartSteps)
                    val avgConf = if (activeConfidences.isNotEmpty()) activeConfidences.average().toInt() else 85
                    completedSegments.add(
                        RouteSegment(
                            action = SegmentAction.TURN,
                            direction = activeDirection,
                            steps = turnSteps,
                            duration = duration,
                            confidence = avgConf
                        )
                    )

                    // Reset sensor integrated heading
                    sensorEngine.resetIntegratedYaw()

                    // Resume WALK segment
                    val now = System.currentTimeMillis()
                    activeAction = SegmentAction.WALK
                    activeDirection = SegmentDirection.STRAIGHT
                    activeStartSteps = event.totalSteps
                    activeStartTime = now
                    activeConfidences.clear()
                    activeConfidences.add(85)
                    pendingWalkStartSteps = event.totalSteps
                    pendingWalkStartTime = now
                    pendingWalkConfidences.clear()
                    pendingWalkConfidences.add(85)
                    turnCandidateStreak = 0
                    turnCandidateDirection = TurnDirection.NONE
                    turnCandidateStartTime = 0L

                    onSegmentListChanged?.invoke(getDisplaySegments())
                }
            }

            SegmentAction.PAUSE -> {
                // When user resumes walking from a pause
                if (event.movementState == MovementState.WALKING || event.totalSteps > activeStartSteps) {
                    val pauseDuration = System.currentTimeMillis() - activeStartTime
                    if (pauseDuration > 2000L) {
                        completedSegments.add(
                            RouteSegment(
                                action = SegmentAction.PAUSE,
                                direction = SegmentDirection.NONE,
                                steps = 0,
                                duration = pauseDuration,
                                confidence = 95
                            )
                        )
                    }

                    // Start new WALK segment
                    val now = System.currentTimeMillis()
                    activeAction = SegmentAction.WALK
                    activeDirection = SegmentDirection.STRAIGHT
                    activeStartSteps = event.totalSteps
                    activeStartTime = now
                    activeConfidences.clear()
                    activeConfidences.add(85)
                    pendingWalkStartSteps = event.totalSteps
                    pendingWalkStartTime = now
                    pendingWalkConfidences.clear()
                    pendingWalkConfidences.add(85)
                    onSegmentListChanged?.invoke(getDisplaySegments())
                }
            }

            else -> {
                // Default to WALK if user moves
                if (event.totalSteps > 0 || event.movementState == MovementState.WALKING) {
                    val now = System.currentTimeMillis()
                    activeAction = SegmentAction.WALK
                    activeDirection = SegmentDirection.STRAIGHT
                    activeStartSteps = event.totalSteps
                    activeStartTime = now
                    pendingWalkStartSteps = event.totalSteps
                    pendingWalkStartTime = now
                }
            }
        }

        // Live telemetry callback
        val currentDesc = buildCurrentSegmentDescription(event)
        onTelemetryUpdated?.invoke(event, currentDesc, routeTotalSteps)
    }

    private fun finalizeCurrentActiveSegment() {
        val action = activeAction ?: return
        val steps = max(0, lastRecordedSteps - activeStartSteps)
        val duration = System.currentTimeMillis() - activeStartTime
        val avgConf = if (activeConfidences.isNotEmpty()) activeConfidences.average().toInt() else 85

        when (action) {
            SegmentAction.WALK -> {
                if (steps > 0 || completedSegments.isEmpty()) {
                    completedSegments.add(
                        RouteSegment(
                            action = SegmentAction.WALK,
                            direction = SegmentDirection.STRAIGHT,
                            steps = steps,
                            duration = duration,
                            confidence = avgConf
                        )
                    )
                }
            }
            SegmentAction.TURN -> {
                if (duration >= minTurnDurationMs) {
                    val walkSteps = max(0, activeStartSteps - pendingWalkStartSteps)
                    if (walkSteps > 0) {
                        val walkDuration = max(0L, activeStartTime - pendingWalkStartTime)
                        val walkConf = if (pendingWalkConfidences.isNotEmpty()) pendingWalkConfidences.average().toInt() else 85
                        completedSegments.add(
                            RouteSegment(
                                action = SegmentAction.WALK,
                                direction = SegmentDirection.STRAIGHT,
                                steps = walkSteps,
                                duration = walkDuration,
                                confidence = walkConf
                            )
                        )
                    }
                    completedSegments.add(
                        RouteSegment(
                            action = SegmentAction.TURN,
                            direction = activeDirection,
                            steps = steps,
                            duration = duration,
                            confidence = avgConf
                        )
                    )
                } else {
                    // False turn: discard turn and commit full WALK with all steps
                    val totalWalkSteps = max(0, lastRecordedSteps - pendingWalkStartSteps)
                    val totalWalkDuration = max(0L, System.currentTimeMillis() - pendingWalkStartTime)
                    val walkConf = if (pendingWalkConfidences.isNotEmpty()) pendingWalkConfidences.average().toInt() else 85
                    if (totalWalkSteps > 0 || completedSegments.isEmpty()) {
                        completedSegments.add(
                            RouteSegment(
                                action = SegmentAction.WALK,
                                direction = SegmentDirection.STRAIGHT,
                                steps = totalWalkSteps,
                                duration = totalWalkDuration,
                                confidence = walkConf
                            )
                        )
                    }
                }
            }
            SegmentAction.PAUSE -> {
                if (duration > 2000L) {
                    completedSegments.add(
                        RouteSegment(
                            action = SegmentAction.PAUSE,
                            direction = SegmentDirection.NONE,
                            steps = 0,
                            duration = duration,
                            confidence = avgConf
                        )
                    )
                }
            }
            else -> {}
        }
        activeAction = null
    }

    private fun buildCurrentSegmentDescription(event: MovementEvent): String {
        return when (activeAction) {
            SegmentAction.WALK -> {
                val s = max(0, event.totalSteps - activeStartSteps)
                "WALK — $s steps (Live)"
            }
            SegmentAction.TURN -> {
                val dir = if (activeDirection == SegmentDirection.LEFT) "LEFT TURN" else "RIGHT TURN"
                "$dir (In progress)"
            }
            SegmentAction.PAUSE -> "PAUSED / RESTING"
            else -> "Ready"
        }
    }

    fun getDisplaySegments(): List<RouteSegment> {
        val list = ArrayList(completedSegments)
        // Optionally show current in-progress segment
        val curAction = activeAction
        if (curAction != null && learningStatus == LearningStatus.LEARNING) {
            val steps = max(0, lastRecordedSteps - activeStartSteps)
            list.add(
                RouteSegment(
                    id = "in_progress",
                    action = curAction,
                    direction = activeDirection,
                    steps = steps,
                    duration = System.currentTimeMillis() - activeStartTime,
                    confidence = 80
                )
            )
        }
        return list
    }
}
