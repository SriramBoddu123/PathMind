package com.example.pathmind.sensor

import com.example.pathmind.model.DeviationState
import com.example.pathmind.model.MovementEvent
import com.example.pathmind.model.MovementState
import com.example.pathmind.model.RouteSegment
import com.example.pathmind.model.SegmentAction
import com.example.pathmind.model.SegmentDirection
import com.example.pathmind.model.TurnDirection
import kotlin.math.max

/**
 * Stage 6: Real-Time Deviation Detector and Recovery Engine.
 * Analyzes phone sensor events against route segments, filters noise,
 * confirms deviations with repeated evidence, and coordinates recovery back
 * to the nearest known recoverable segment.
 * Strictly zero GPS, zero cloud APIs, zero fake coordinates.
 */
class DeviationDetector {

    var deviationState: DeviationState = DeviationState.ON_ROUTE
        private set

    var deviationMessage: String = "ON ROUTE"
        private set

    var recoveryInstruction: String? = null
        private set

    var lastConfirmedSegmentIndex: Int = 0
        private set

    // Evidence tracking
    private var contradictoryEvidenceStreak: Int = 0
    private var ambiguousStreak: Int = 0
    private var recoveryProgressStreak: Int = 0

    // Contextual deviation memory
    private var deviatingTurn: TurnDirection = TurnDirection.NONE
    private var expectedTurn: TurnDirection = TurnDirection.NONE

    fun reset(initialSegmentIndex: Int = 0) {
        deviationState = DeviationState.ON_ROUTE
        deviationMessage = "ON ROUTE"
        recoveryInstruction = null
        lastConfirmedSegmentIndex = initialSegmentIndex
        contradictoryEvidenceStreak = 0
        ambiguousStreak = 0
        recoveryProgressStreak = 0
        deviatingTurn = TurnDirection.NONE
        expectedTurn = TurnDirection.NONE
    }

    fun confirmSegmentReached(segmentIndex: Int) {
        lastConfirmedSegmentIndex = segmentIndex
        if (deviationState == DeviationState.ON_ROUTE || deviationState == DeviationState.RECOVERED) {
            deviationState = DeviationState.ON_ROUTE
            deviationMessage = "ON ROUTE"
            recoveryInstruction = null
            contradictoryEvidenceStreak = 0
            ambiguousStreak = 0
            recoveryProgressStreak = 0
        }
    }

    /**
     * Evaluates live sensor event against expected route segment geometry.
     * Returns true if deviation or recovery state changed.
     */
    fun evaluate(
        currentSegmentIndex: Int,
        expectedSegment: RouteSegment?,
        event: MovementEvent,
        stepsInCurrentSegment: Int
    ): Boolean {
        if (expectedSegment == null) return false

        // 1. Check for Weak / Ambiguous Evidence (< 50% confidence or UNKNOWN state)
        if (event.confidence < 50 || event.movementState == MovementState.UNKNOWN) {
            ambiguousStreak++
            if (ambiguousStreak >= 2 && deviationState != DeviationState.DEVIATION_CONFIRMED && deviationState != DeviationState.RECOVERING) {
                deviationState = DeviationState.UNCERTAIN
                deviationMessage = "UNCERTAIN — MOVE CAREFULLY"
                recoveryInstruction = "AMBIGUOUS SENSOR EVIDENCE"
                return true
            }
            return false
        } else {
            // If we were UNCERTAIN, require sustained clear evidence before clearing
            if (deviationState == DeviationState.UNCERTAIN) {
                ambiguousStreak--
                if (ambiguousStreak <= 0) {
                    ambiguousStreak = 0
                    deviationState = DeviationState.ON_ROUTE
                    deviationMessage = "ON ROUTE"
                    recoveryInstruction = null
                    return true
                }
                return false
            }
            ambiguousStreak = 0
        }

        // 2. Evaluation based on current Deviation State
        return when (deviationState) {
            DeviationState.ON_ROUTE, DeviationState.POSSIBLE_DEVIATION -> {
                evaluateOnRoute(currentSegmentIndex, expectedSegment, event, stepsInCurrentSegment)
            }
            DeviationState.DEVIATION_CONFIRMED -> {
                evaluateInDeviation(event)
            }
            DeviationState.RECOVERING -> {
                evaluateInRecovery(event)
            }
            DeviationState.RECOVERED -> {
                // Return to ON_ROUTE once walking is confirmed
                if (event.movementState == MovementState.WALKING || event.totalSteps > 0) {
                    deviationState = DeviationState.ON_ROUTE
                    deviationMessage = "ON ROUTE"
                    recoveryInstruction = null
                    true
                } else {
                    false
                }
            }
            DeviationState.UNCERTAIN -> false
        }
    }

    private fun evaluateOnRoute(
        currentSegmentIndex: Int,
        expectedSegment: RouteSegment,
        event: MovementEvent,
        stepsInSegment: Int
    ): Boolean {
        var contradictionDetected = false

        when (expectedSegment.action) {
            SegmentAction.TURN -> {
                val expectedDirection = if (expectedSegment.direction == SegmentDirection.LEFT) TurnDirection.LEFT else TurnDirection.RIGHT
                this.expectedTurn = expectedDirection

                // Check for opposite / conflicting turn
                if (event.turnDirection != TurnDirection.NONE && event.turnDirection != expectedDirection && event.confidence >= 60) {
                    contradictionDetected = true
                    deviatingTurn = event.turnDirection
                } else if (event.turnDirection == expectedDirection) {
                    // Turn is correct! Clear contradiction streak
                    contradictoryEvidenceStreak = 0
                    deviationState = DeviationState.ON_ROUTE
                    deviationMessage = "ON ROUTE"
                    recoveryInstruction = null
                    return true
                } else if (stepsInSegment >= 4 && event.movementState == MovementState.WALKING) {
                    // Missed the turn and walked straight past it
                    contradictionDetected = true
                    deviatingTurn = TurnDirection.NONE
                }
            }

            SegmentAction.WALK -> {
                // Expected to walk straight, but a distinct turn is detected
                if ((event.turnDirection == TurnDirection.LEFT || event.turnDirection == TurnDirection.RIGHT) && event.confidence >= 65) {
                    contradictionDetected = true
                    deviatingTurn = event.turnDirection
                } else if (event.movementState == MovementState.WALKING) {
                    // Walking correctly along straight segment
                    if (contradictoryEvidenceStreak > 0) {
                        contradictoryEvidenceStreak--
                    }
                    if (contradictoryEvidenceStreak == 0 && deviationState == DeviationState.POSSIBLE_DEVIATION) {
                        deviationState = DeviationState.ON_ROUTE
                        deviationMessage = "ON ROUTE"
                        recoveryInstruction = null
                        return true
                    }
                }
            }

            else -> {
                // START, PAUSE, DESTINATION
            }
        }

        if (contradictionDetected) {
            contradictoryEvidenceStreak++
            if (contradictoryEvidenceStreak == 1) {
                // Single event: POSSIBLE_DEVIATION (do not declare confirmed deviation from 1 noisy event)
                deviationState = DeviationState.POSSIBLE_DEVIATION
                deviationMessage = "POSSIBLE DEVIATION"
                recoveryInstruction = "VERIFYING SENSOR EVIDENCE..."
                return true
            } else if (contradictoryEvidenceStreak >= 2) {
                // Repeated consistent evidence: DEVIATION_CONFIRMED
                deviationState = DeviationState.DEVIATION_CONFIRMED
                deviationMessage = "DEVIATION DETECTED"
                recoveryInstruction = "TURN BACK • RETURN TO LAST KNOWN POINT"
                return true
            }
        }

        return false
    }

    private fun evaluateInDeviation(event: MovementEvent): Boolean {
        // Look for recovery action: user turning back or moving back toward known route
        val isOppositeTurn = (deviatingTurn == TurnDirection.RIGHT && event.turnDirection == TurnDirection.LEFT) ||
                (deviatingTurn == TurnDirection.LEFT && event.turnDirection == TurnDirection.RIGHT) ||
                (deviatingTurn == TurnDirection.NONE && (event.turnDirection == TurnDirection.LEFT || event.turnDirection == TurnDirection.RIGHT))

        if (isOppositeTurn || (event.movementState == MovementState.WALKING && event.confidence >= 65)) {
            recoveryProgressStreak++
            if (recoveryProgressStreak >= 1) {
                deviationState = DeviationState.RECOVERING
                deviationMessage = "RECOVERING..."
                recoveryInstruction = "RETURN TO LAST KNOWN POINT"
                return true
            }
        }
        return false
    }

    private fun evaluateInRecovery(event: MovementEvent): Boolean {
        // User continues recovery movement
        if (event.movementState == MovementState.WALKING || event.turnDirection != TurnDirection.NONE) {
            recoveryProgressStreak++
            if (recoveryProgressStreak >= 3) {
                // Route recovered!
                deviationState = DeviationState.RECOVERED
                deviationMessage = "ROUTE RECOVERED"
                recoveryInstruction = "FOLLOW KNOWN ROUTE"
                contradictoryEvidenceStreak = 0
                deviatingTurn = TurnDirection.NONE
                return true
            }
        }
        return false
    }

    // ==========================================
    // Test Hooks for Verifiable Device Testing
    // ==========================================

    fun triggerWrongTurn(): DeviationState {
        contradictoryEvidenceStreak++
        if (contradictoryEvidenceStreak == 1) {
            deviationState = DeviationState.POSSIBLE_DEVIATION
            deviationMessage = "POSSIBLE DEVIATION"
            recoveryInstruction = "VERIFYING SENSOR EVIDENCE..."
        } else {
            deviationState = DeviationState.DEVIATION_CONFIRMED
            deviationMessage = "DEVIATION DETECTED"
            recoveryInstruction = "TURN BACK • RETURN TO LAST KNOWN POINT"
        }
        return deviationState
    }

    fun triggerRecovery(): DeviationState {
        recoveryProgressStreak++
        if (recoveryProgressStreak == 1) {
            deviationState = DeviationState.RECOVERING
            deviationMessage = "RECOVERING..."
            recoveryInstruction = "RETURN TO LAST KNOWN POINT"
        } else {
            deviationState = DeviationState.RECOVERED
            deviationMessage = "ROUTE RECOVERED"
            recoveryInstruction = "FOLLOW KNOWN ROUTE"
        }
        return deviationState
    }

    fun triggerAmbiguousNoise(): DeviationState {
        ambiguousStreak = 500
        deviationState = DeviationState.UNCERTAIN
        deviationMessage = "UNCERTAIN — MOVE CAREFULLY"
        recoveryInstruction = "AMBIGUOUS SENSOR EVIDENCE"
        return deviationState
    }

    fun triggerResetOnRoute(): DeviationState {
        deviationState = DeviationState.ON_ROUTE
        deviationMessage = "ON ROUTE"
        recoveryInstruction = null
        contradictoryEvidenceStreak = 0
        recoveryProgressStreak = 0
        ambiguousStreak = 0
        return deviationState
    }
}
