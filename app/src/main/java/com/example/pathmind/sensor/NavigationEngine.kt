package com.example.pathmind.sensor

import android.content.Context
import com.example.pathmind.model.DeviationState
import com.example.pathmind.model.MovementEvent
import com.example.pathmind.model.MovementState
import com.example.pathmind.model.NavigationSnapshot
import com.example.pathmind.model.NavigationState
import com.example.pathmind.model.Route
import com.example.pathmind.model.RouteSegment
import com.example.pathmind.model.SegmentAction
import com.example.pathmind.model.SegmentDirection
import com.example.pathmind.model.SpatialMLClass
import com.example.pathmind.model.SpatialModelOutput
import com.example.pathmind.model.TurnDirection
import kotlin.math.abs
import kotlin.math.max

/**
 * Stage 7 Real-Time Offline Navigation Engine with On-Device AI/ML Spatial Reasoning.
 * Guides the user along a learned route or to a remembered place using real phone sensor movement events.
 * Employs an on-device Deep MLP to predict spatial intent, early drift, and deviation.
 * Strictly zero GPS, zero cloud APIs, zero fake coordinates.
 */
class NavigationEngine(context: Context) {

    private val sensorEngine = MovementSensorEngine(context)
    private val deviationDetector = DeviationDetector()
    private val spatialMLEngine = SpatialMLInferenceEngine(context)
    private var lastMLOutput: SpatialModelOutput? = null

    var onSnapshotUpdated: ((NavigationSnapshot) -> Unit)? = null

    private var activeRoute: Route? = null
    private var navSegments: List<RouteSegment> = emptyList()
    private var targetSegmentId: String? = null
    private var targetName: String = "Destination"

    private var currentState: NavigationState = NavigationState.IDLE
    private var currentInstruction: String = "READY"
    private var currentSegmentIndex: Int = 0
    private var segmentStartSteps: Int = 0
    private var totalStepsObserved: Int = 0
    private var turnCandidateStreak: Int = 0
    private var turnCandidateDirection: TurnDirection = TurnDirection.NONE

    private var isNavigating: Boolean = false
    private var activeSimulationState: String? = null

    init {
        sensorEngine.onMovementUpdated = { event, debugInfo ->
            if (isNavigating) {
                processMovement(event, debugInfo)
            }
        }
    }

    fun loadRoute(
        route: Route,
        targetSegmentId: String? = null,
        targetName: String? = null
    ) {
        this.activeRoute = route
        this.targetSegmentId = targetSegmentId
        this.targetName = targetName ?: route.name

        // If target segment is specified (e.g. remembered place), slice route up to that segment
        if (!targetSegmentId.isNullOrBlank()) {
            val targetIdx = route.segments.indexOfFirst { it.id == targetSegmentId }
            navSegments = if (targetIdx >= 0) {
                route.segments.subList(0, targetIdx + 1)
            } else {
                route.segments
            }
        } else {
            navSegments = route.segments
        }

        currentSegmentIndex = 0
        segmentStartSteps = 0
        totalStepsObserved = 0
        turnCandidateStreak = 0
        turnCandidateDirection = TurnDirection.NONE
        currentState = NavigationState.IDLE
        currentInstruction = "READY TO NAVIGATE"
        activeSimulationState = null
        deviationDetector.reset(0)

        emitSnapshot()
    }

    fun startNavigation(): Boolean {
        if (navSegments.isEmpty()) return false

        isNavigating = true
        currentSegmentIndex = 0
        segmentStartSteps = 0
        totalStepsObserved = 0
        turnCandidateStreak = 0
        turnCandidateDirection = TurnDirection.NONE
        activeSimulationState = null
        deviationDetector.reset(0)

        val firstSeg = navSegments.firstOrNull()
        if (firstSeg?.action == SegmentAction.START) {
            currentState = NavigationState.STARTING
            currentInstruction = "START WALKING"
        } else {
            currentState = NavigationState.NAVIGATING
            currentInstruction = getInstructionForSegment(firstSeg)
        }

        sensorEngine.resetSteps()
        sensorEngine.startListening()

        emitSnapshot()
        return true
    }

    fun stopNavigation() {
        if (!isNavigating) return
        isNavigating = false
        sensorEngine.stopListening()
        currentSegmentIndex = 0
        segmentStartSteps = 0
        totalStepsObserved = 0
        turnCandidateStreak = 0
        turnCandidateDirection = TurnDirection.NONE
        currentState = NavigationState.IDLE
        currentInstruction = "NAVIGATION STOPPED"
        activeSimulationState = null
        deviationDetector.reset(0)
        emitSnapshot()
    }

    fun isNavigating(): Boolean = isNavigating

    private fun processMovement(event: MovementEvent, rawDebug: String) {
        if (!isNavigating || currentState == NavigationState.ARRIVED) return

        totalStepsObserved = event.totalSteps
        val stepsInSegment = max(0, totalStepsObserved - segmentStartSteps)
        val currentSeg = navSegments.getOrNull(currentSegmentIndex)

        if (currentSeg == null || currentSegmentIndex >= navSegments.size) {
            markArrived()
            return
        }

        // Track turning with debounce
        if (event.turnDirection == TurnDirection.LEFT || event.turnDirection == TurnDirection.RIGHT) {
            if (event.turnDirection == turnCandidateDirection) {
                turnCandidateStreak++
            } else {
                turnCandidateDirection = event.turnDirection
                turnCandidateStreak = 1
            }
        } else {
            turnCandidateStreak = 0
            turnCandidateDirection = TurnDirection.NONE
        }

        // Run On-Device ML Spatial Intent & Alignment Inference (preserve simulated vector if test active)
        val ml = if (activeSimulationState != null && lastMLOutput != null) {
            lastMLOutput!!
        } else {
            evaluateML(event, stepsInSegment, currentSeg)
        }

        // Run Deviation Evaluation against expected route segment
        deviationDetector.evaluate(currentSegmentIndex, currentSeg, event, stepsInSegment)

        // Synthesize ML Reasoning with Navigation State
        when {
            ml.predictedClass == SpatialMLClass.DEVIATION_CONFIRMED ||
            deviationDetector.deviationState == DeviationState.DEVIATION_CONFIRMED -> {
                currentState = NavigationState.UNCERTAIN
                currentInstruction = "DEVIATION DETECTED • TURN BACK"
                emitSnapshot(rawDebug)
                return
            }
            ml.predictedClass == SpatialMLClass.DRIFT_WARNING ||
            deviationDetector.deviationState == DeviationState.POSSIBLE_DEVIATION -> {
                val nextSeg = navSegments.getOrNull(currentSegmentIndex + 1)
                currentInstruction = if (nextSeg?.action == SegmentAction.TURN) {
                    if (nextSeg.direction == SegmentDirection.LEFT) "CHECK HEADING • PREPARE TO TURN LEFT" else "CHECK HEADING • PREPARE TO TURN RIGHT"
                } else {
                    "POSSIBLE DRIFT • CHECK PATH"
                }
                emitSnapshot(rawDebug)
                return
            }
            ml.predictedClass == SpatialMLClass.RECOVERING ||
            deviationDetector.deviationState == DeviationState.RECOVERING -> {
                currentInstruction = "RECOVERING • RETURN TO ROUTE"
                emitSnapshot(rawDebug)
                return
            }
            deviationDetector.deviationState == DeviationState.RECOVERED -> {
                currentInstruction = "ROUTE RECOVERED"
                currentSegmentIndex = deviationDetector.lastConfirmedSegmentIndex
                val resumedSeg = navSegments.getOrNull(currentSegmentIndex)
                currentState = if (resumedSeg?.action == SegmentAction.TURN) NavigationState.TURN_REQUIRED else NavigationState.NAVIGATING
                emitSnapshot(rawDebug)
                return
            }
            ml.predictedClass == SpatialMLClass.UNCERTAIN ||
            ml.normalizedEntropy > 0.75f ||
            deviationDetector.deviationState == DeviationState.UNCERTAIN -> {
                currentState = NavigationState.UNCERTAIN
                currentInstruction = "UNCERTAIN — MOVE CAREFULLY"
                emitSnapshot(rawDebug)
                return
            }
            ml.predictedClass == SpatialMLClass.ON_ROUTE -> {
                // Continue standard topological progression
            }
        }

        // Standard Route Progression Logic
        when (currentSeg.action) {
            SegmentAction.START -> {
                if (totalStepsObserved > 0 || event.movementState == MovementState.WALKING) {
                    advanceToNextSegment()
                } else {
                    currentState = NavigationState.STARTING
                    currentInstruction = "START WALKING"
                }
            }

            SegmentAction.WALK -> {
                val expectedSteps = currentSeg.steps
                val isApproachingEnd = (currentSegmentIndex == navSegments.size - 2) || (currentSegmentIndex == navSegments.size - 1)

                currentState = NavigationState.NAVIGATING
                currentInstruction = when {
                    isApproachingEnd && stepsInSegment >= max(1, expectedSteps - 2) -> {
                        "YOU ARE APPROACHING YOUR DESTINATION"
                    }
                    else -> {
                        val nextSeg = navSegments.getOrNull(currentSegmentIndex + 1)
                        if (nextSeg?.action == SegmentAction.TURN && stepsInSegment >= max(1, expectedSteps - 2)) {
                            if (nextSeg.direction == SegmentDirection.LEFT) "PREPARE TO TURN LEFT" else "PREPARE TO TURN RIGHT"
                        } else {
                            "CONTINUE STRAIGHT"
                        }
                    }
                }

                // Transition when expected steps are fulfilled or next segment turn is triggered
                val nextSeg = navSegments.getOrNull(currentSegmentIndex + 1)
                val nextIsTurn = nextSeg?.action == SegmentAction.TURN
                val turnMatchNext = nextIsTurn && (
                        (nextSeg.direction == SegmentDirection.LEFT && event.turnDirection == TurnDirection.LEFT) ||
                                (nextSeg.direction == SegmentDirection.RIGHT && event.turnDirection == TurnDirection.RIGHT)
                        )

                if (stepsInSegment >= expectedSteps || (turnMatchNext && stepsInSegment >= max(1, expectedSteps / 2))) {
                    advanceToNextSegment()
                }
            }

            SegmentAction.TURN -> {
                val expectedTurn = if (currentSeg.direction == SegmentDirection.LEFT) TurnDirection.LEFT else TurnDirection.RIGHT
                currentState = NavigationState.TURN_REQUIRED
                currentInstruction = if (expectedTurn == TurnDirection.LEFT) "TURN LEFT" else "TURN RIGHT"

                // Check if user is performing the expected turn
                if (event.turnDirection == expectedTurn && event.confidence >= 50) {
                    advanceToNextSegment()
                }
            }

            SegmentAction.PAUSE -> {
                if (event.movementState == MovementState.WALKING || stepsInSegment > 0) {
                    advanceToNextSegment()
                }
            }

            SegmentAction.DESTINATION -> {
                markArrived()
                return
            }
        }

        emitSnapshot(rawDebug)
    }

    private fun advanceToNextSegment() {
        currentSegmentIndex++
        segmentStartSteps = totalStepsObserved
        turnCandidateStreak = 0
        turnCandidateDirection = TurnDirection.NONE

        deviationDetector.confirmSegmentReached(currentSegmentIndex)

        if (currentSegmentIndex >= navSegments.size) {
            markArrived()
            return
        }

        val nextSeg = navSegments[currentSegmentIndex]
        if (nextSeg.action == SegmentAction.DESTINATION) {
            markArrived()
            return
        }

        when (nextSeg.action) {
            SegmentAction.WALK -> {
                currentState = NavigationState.NAVIGATING
                currentInstruction = "CONTINUE STRAIGHT"
            }
            SegmentAction.TURN -> {
                currentState = NavigationState.TURN_REQUIRED
                currentInstruction = if (nextSeg.direction == SegmentDirection.LEFT) "TURN LEFT" else "TURN RIGHT"
            }
            SegmentAction.PAUSE -> {
                currentState = NavigationState.NAVIGATING
                currentInstruction = "PAUSED / RESTING"
            }
            else -> {
                currentState = NavigationState.NAVIGATING
                currentInstruction = "CONTINUE STRAIGHT"
            }
        }

        emitSnapshot()
    }

    private fun markArrived() {
        currentState = NavigationState.ARRIVED
        currentInstruction = if (targetSegmentId != null) "ARRIVED AT $targetName" else "ARRIVED"
        sensorEngine.stopListening()
        isNavigating = false
        emitSnapshot()
    }

    private fun getInstructionForSegment(seg: RouteSegment?): String {
        if (seg == null) return "READY"
        return when (seg.action) {
            SegmentAction.START -> "START WALKING"
            SegmentAction.WALK -> "CONTINUE STRAIGHT"
            SegmentAction.TURN -> if (seg.direction == SegmentDirection.LEFT) "TURN LEFT" else "TURN RIGHT"
            SegmentAction.PAUSE -> "PAUSED"
            SegmentAction.DESTINATION -> "ARRIVED"
        }
    }

    private fun evaluateML(
        event: MovementEvent,
        stepsInSegment: Int,
        currentSeg: RouteSegment?
    ): SpatialModelOutput {
        val cadence = sensorEngine.stepCadenceHz
        val accelVar = sensorEngine.currentVariance
        val yawRate = sensorEngine.getSmoothedYawRate()
        val yawDeg = sensorEngine.integratedYawDeg
        val movStateCode = when (event.movementState) {
            MovementState.STATIONARY -> 0f
            MovementState.WALKING -> 1f
            MovementState.TURNING -> 2f
            MovementState.UNKNOWN -> 3f
        }
        val jitter = sensorEngine.stateTransitionJitter
        val hesitation = sensorEngine.recentHesitationScore
        val sensorConf = sensorEngine.sensorConfidenceNorm

        val actionCode = when (currentSeg?.action) {
            SegmentAction.START -> 0f
            SegmentAction.WALK -> 1f
            SegmentAction.TURN -> 2f
            SegmentAction.PAUSE -> 3f
            SegmentAction.DESTINATION -> 4f
            null -> 3f
        }
        val expectedTurn = if (currentSeg?.action == SegmentAction.TURN) {
            if (currentSeg.direction == SegmentDirection.LEFT) -90f else 90f
        } else 0f

        val budgetSteps = (currentSeg?.steps ?: 30).toFloat()
        val learnedDur = (currentSeg?.duration ?: 15000L).toFloat()
        val isMemory = if (targetSegmentId != null && currentSeg?.id == targetSegmentId) 1f else 0f
        val routeComp = if (navSegments.isNotEmpty()) (currentSegmentIndex.toFloat() / navSegments.size) else 0f
        val progRatio = stepsInSegment.toFloat() / max(1f, budgetSteps)
        val overshoot = max(0f, (stepsInSegment - budgetSteps))
        val headingDisp = abs(yawDeg - expectedTurn)

        val learnedStepPace = learnedDur / max(1f, budgetSteps)
        val currentStepPace = if (cadence > 0.2f) (1000f / cadence) else learnedStepPace
        val paceDisp = currentStepPace / max(100f, learnedStepPace)

        val features = floatArrayOf(
            cadence, accelVar, yawRate, yawDeg, movStateCode,
            jitter, hesitation, sensorConf, actionCode, expectedTurn,
            budgetSteps, learnedDur, isMemory, routeComp,
            progRatio, overshoot, headingDisp, paceDisp
        )

        val output = spatialMLEngine.predict(features)
        lastMLOutput = output
        return output
    }

    private fun emitSnapshot(rawDebug: String = "") {
        val currentSeg = navSegments.getOrNull(currentSegmentIndex)
        val stepsInSegment = max(0, totalStepsObserved - segmentStartSteps)
        val expectedSteps = currentSeg?.steps ?: 0

        // Use calibrated mathematical confidence from ML model modulated by Shannon entropy
        val mlConf = lastMLOutput?.calibratedConfidence
        val finalConf = when {
            currentState == NavigationState.ARRIVED -> 100
            mlConf != null -> mlConf
            else -> when (deviationDetector.deviationState) {
                DeviationState.DEVIATION_CONFIRMED -> 35
                DeviationState.POSSIBLE_DEVIATION -> 55
                DeviationState.RECOVERING -> 70
                DeviationState.RECOVERED -> 95
                DeviationState.UNCERTAIN -> 40
                DeviationState.ON_ROUTE -> 85
            }
        }

        val isTargetReached = currentState == NavigationState.ARRIVED

        val snapshot = NavigationSnapshot(
            state = currentState,
            instruction = currentInstruction,
            currentSegmentIndex = currentSegmentIndex,
            totalSegments = navSegments.size,
            currentSegment = currentSeg,
            stepsInCurrentSegment = stepsInSegment,
            expectedStepsInSegment = expectedSteps,
            totalStepsObserved = totalStepsObserved,
            confidence = finalConf,
            isTargetReached = isTargetReached,
            targetName = targetName,
            deviationState = deviationDetector.deviationState,
            deviationMessage = deviationDetector.deviationMessage,
            lastConfirmedSegmentIndex = deviationDetector.lastConfirmedSegmentIndex,
            recoveryInstruction = deviationDetector.recoveryInstruction,
            mlOutput = lastMLOutput,
            debugInfo = rawDebug
        )

        onSnapshotUpdated?.invoke(snapshot)
    }

    // =======================================================
    // Test & Recovery Control Hooks for Physical Verification
    // =======================================================

    fun manualAdvanceSegment() {
        if (isNavigating && currentState != NavigationState.ARRIVED) {
            advanceToNextSegment()
        }
    }

    fun simulateWrongTurn() {
        if (!isNavigating) return
        val curSeg = navSegments.getOrNull(currentSegmentIndex)
        val expectedTurn = if (curSeg?.action == SegmentAction.TURN && curSeg.direction == SegmentDirection.LEFT) -90f else 90f
        // Synthesize wrong-turn kinematic vector for ML inspection: opposite turn, 180 disparity
        val wrongFeatures = floatArrayOf(
            1.8f, 1.8f, -expectedTurn / 90f * 1.2f, -expectedTurn, 2f,
            0.15f, 0.1f, 0.9f, 2f, expectedTurn,
            (curSeg?.steps ?: 30).toFloat(), 15000f, 0f, 0.5f,
            0.8f, 0f, 180f, 1.0f
        )
        activeSimulationState = "WRONG_TURN"
        lastMLOutput = spatialMLEngine.predict(wrongFeatures)
        deviationDetector.triggerWrongTurn()
        currentState = NavigationState.UNCERTAIN
        currentInstruction = "DEVIATION DETECTED • TURN BACK"
        emitSnapshot("SIMULATED_WRONG_TURN")
    }

    fun simulateRecovery() {
        if (!isNavigating) return
        val curSeg = navSegments.getOrNull(currentSegmentIndex)
        // Synthesize backtracking recovery kinematic vector: ~180 U-turn heading to anchor
        val recovFeatures = floatArrayOf(
            1.6f, 1.5f, 1.4f, 180f, 2f,
            0.2f, 0.2f, 0.85f, 1f, 0f,
            (curSeg?.steps ?: 30).toFloat(), 15000f, 1f, 0.5f,
            0.3f, 0f, 160f, 1.1f
        )
        activeSimulationState = "RECOVERY"
        lastMLOutput = spatialMLEngine.predict(recovFeatures)
        deviationDetector.triggerRecovery()
        currentInstruction = "RECOVERING • RETURN TO ROUTE"
        emitSnapshot("SIMULATED_RECOVERY")
    }

    fun simulateAmbiguousNoise() {
        if (!isNavigating) return
        val curSeg = navSegments.getOrNull(currentSegmentIndex)
        // Synthesize noisy/pocket jitter kinematic vector: low confidence, rapid state oscillation
        val noiseFeatures = floatArrayOf(
            0.2f, 3.8f, 2.5f, 95f, 3f,
            0.85f, 0.8f, 0.25f, 1f, 0f,
            (curSeg?.steps ?: 30).toFloat(), 15000f, 0f, 0.5f,
            0.5f, 0f, 95f, 2.5f
        )
        activeSimulationState = "NOISE"
        lastMLOutput = spatialMLEngine.predict(noiseFeatures)
        deviationDetector.triggerAmbiguousNoise()
        currentState = NavigationState.UNCERTAIN
        currentInstruction = "UNCERTAIN — MOVE CAREFULLY"
        emitSnapshot("SIMULATED_NOISE")
    }

    fun resetToOnRoute() {
        if (!isNavigating) return
        activeSimulationState = null
        val curSeg = navSegments.getOrNull(currentSegmentIndex)
        // Synthesize perfectly aligned on-route kinematic vector
        val onRouteFeatures = floatArrayOf(
            1.8f, 1.5f, 0.05f, 0f, 1f,
            0.05f, 0.05f, 0.95f, 1f, 0f,
            (curSeg?.steps ?: 30).toFloat(), 15000f, 0f, 0.5f,
            0.5f, 0f, 5f, 1.0f
        )
        lastMLOutput = spatialMLEngine.predict(onRouteFeatures)
        deviationDetector.triggerResetOnRoute()
        currentState = if (curSeg?.action == SegmentAction.TURN) NavigationState.TURN_REQUIRED else NavigationState.NAVIGATING
        currentInstruction = getInstructionForSegment(curSeg)
        emitSnapshot("RESET_ON_ROUTE")
    }
}
