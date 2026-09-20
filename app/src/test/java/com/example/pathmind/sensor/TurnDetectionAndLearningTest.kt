package com.example.pathmind.sensor

import com.example.pathmind.model.MovementEvent
import com.example.pathmind.model.MovementState
import com.example.pathmind.model.SegmentAction
import com.example.pathmind.model.SegmentDirection
import com.example.pathmind.model.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Focused unit test suite for sensor filtering, turn detection, and route learning segmentation.
 * Validates:
 * a. Straight walking with yaw noise does NOT create a turn.
 * b. Sustained left rotation creates LEFT.
 * c. Sustained right rotation creates RIGHT.
 * d. Short gyro spikes do NOT create turns.
 * e. Walking steps remain associated with WALK segments during false turns.
 * f. No unnecessary 0-step TURN segments are created.
 */
class TurnDetectionAndLearningTest {

    @Test
    fun testStraightWalkingWithYawNoise_doesNotCreateTurn() {
        val sensor = MovementSensorEngine()
        sensor.startListening()

        // Simulate 30 samples of straight walking with natural sway noise (-0.30 to +0.30 rad/s)
        var tNs = 1_000_000_000L
        for (i in 1..30) {
            tNs += 20_000_000L // 20ms (50Hz)
            // Vertical gravity + walking vertical bounce
            val bounce = if (i % 2 == 0) 1.5f else -1.5f
            sensor.processAccelerometer(0.2f, 9.81f + bounce, 0.1f)

            // Oscillating yaw sway within arm swing noise floor (< 0.35 rad/s)
            val sway = if (i % 4 < 2) 0.30f else -0.30f
            sensor.processGyroscope(0.05f, 0.05f, sway, tNs)

            // Step events occurring regularly
            if (i % 10 == 0) {
                sensor.processStepDetector(System.currentTimeMillis())
            }

            assertEquals(TurnDirection.NONE, sensor.getCurrentTurnDirection())
        }

        // Assert that accumulated angle did not cross threshold (25 deg)
        assertTrue(abs(sensor.integratedYawDeg) < MovementSensorEngine.DEFAULT_MIN_TURN_ANGLE_DEG)
    }

    @Test
    fun testSustainedLeftRotation_createsLeftTurn() {
        val sensor = MovementSensorEngine()
        sensor.startListening()

        var tNs = 1_000_000_000L
        // Setup initial gravity vector
        sensor.processAccelerometer(0f, 9.81f, 0f)

        // Rotate left at 1.0 rad/s (~57 deg/s) for 30 samples (600ms = ~34 degrees accumulated)
        for (i in 1..30) {
            tNs += 20_000_000L // 20ms
            sensor.processAccelerometer(0f, 9.81f, 0f)
            sensor.processGyroscope(0f, 1.0f, 0f, tNs) // rotating around vertical gravity Y
        }

        assertTrue("Smoothed yaw rate should be above threshold", sensor.getSmoothedYawRate() > MovementSensorEngine.DEFAULT_TURN_YAW_RATE_THRESHOLD)
        assertTrue("Integrated yaw should exceed min angle", sensor.integratedYawDeg >= MovementSensorEngine.DEFAULT_MIN_TURN_ANGLE_DEG)
        assertEquals(TurnDirection.LEFT, sensor.getCurrentTurnDirection())
    }

    @Test
    fun testSustainedRightRotation_createsRightTurn() {
        val sensor = MovementSensorEngine()
        sensor.startListening()

        var tNs = 1_000_000_000L
        sensor.processAccelerometer(0f, 9.81f, 0f)

        // Rotate right at -1.0 rad/s for 30 samples (600ms = ~ -34 degrees accumulated)
        for (i in 1..30) {
            tNs += 20_000_000L
            sensor.processAccelerometer(0f, 9.81f, 0f)
            sensor.processGyroscope(0f, -1.0f, 0f, tNs)
        }

        assertTrue("Smoothed yaw rate should be below negative threshold", sensor.getSmoothedYawRate() < -MovementSensorEngine.DEFAULT_TURN_YAW_RATE_THRESHOLD)
        assertTrue("Integrated yaw should be <= -25 deg", sensor.integratedYawDeg <= -MovementSensorEngine.DEFAULT_MIN_TURN_ANGLE_DEG)
        assertEquals(TurnDirection.RIGHT, sensor.getCurrentTurnDirection())
    }

    @Test
    fun testShortGyroSpikes_doNotCreateTurns() {
        val sensor = MovementSensorEngine()
        sensor.startListening()

        var tNs = 1_000_000_000L
        sensor.processAccelerometer(0f, 9.81f, 0f)

        // A violent instantaneous gyro spike (2.0 rad/s) lasting only 3 samples (60ms)
        for (i in 1..3) {
            tNs += 20_000_000L
            sensor.processAccelerometer(0f, 9.81f, 0f)
            sensor.processGyroscope(0f, 2.0f, 0f, tNs)
        }

        // Even though instantaneous rate was high, accumulated angle is only ~6.8 degrees (< 25 deg)
        assertTrue("Integrated angle must be < 25 deg", sensor.integratedYawDeg < MovementSensorEngine.DEFAULT_MIN_TURN_ANGLE_DEG)
        assertEquals(TurnDirection.NONE, sensor.getCurrentTurnDirection())

        // Returning to straight motion allows quick bleed towards 0
        for (i in 1..10) {
            tNs += 20_000_000L
            sensor.processGyroscope(0f, 0f, 0f, tNs)
        }
        assertEquals(TurnDirection.NONE, sensor.getCurrentTurnDirection())
    }

    @Test
    fun testLowPassGravityFilter_isolatesFootfallShocks() {
        val sensor = MovementSensorEngine()
        sensor.startListening()

        // Initial phone upright: gravity Y = 9.81
        // Simulate strong forward footfall shock: Ax = 5.0 m/s^2 (horizontal acceleration)
        for (i in 1..5) {
            sensor.processAccelerometer(5.0f, 9.81f, 0f)
        }

        val (gx, gy, gz) = sensor.getEstimatedGravity()
        // Low-pass filtered gravity should still be predominantly along Y, gx should only slowly adjust
        assertTrue("Estimated gravity Y should remain dominant", gy > 8.0f)
        assertTrue("Estimated gravity X should be filtered and significantly less than raw 5.0", gx < 2.0f)
    }

    @Test
    fun testWalkingStepsRemainAssociatedWithWalkSegments_whenFalseTurnOccurs() {
        val engine = RouteLearningEngine()
        engine.startLearning()

        // Walk 5 steps straight
        for (step in 1..5) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        // Send a candidate turn streak (15 samples)
        for (i in 1..15) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = 5,
                    movementState = MovementState.TURNING,
                    turnDirection = TurnDirection.LEFT,
                    confidence = 80
                )
            )
        }

        // While in candidate turn, 2 steps are taken, but the turn immediately ceases (duration < 800ms)
        engine.processMovementEvent(
            MovementEvent(
                totalSteps = 7,
                movementState = MovementState.WALKING,
                turnDirection = TurnDirection.NONE,
                confidence = 85
            )
        )

        // User walks 3 more steps (total 10 steps)
        for (step in 8..10) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        val route = engine.stopAndCreateRoute("Test Walk Preservation")

        // Route should NOT have split the walk into multiple walk segments or added a fake turn
        // Segments should be: START (0), WALK (10 steps), DESTINATION (0)
        assertEquals(3, route.segments.size)
        assertEquals(SegmentAction.START, route.segments[0].action)
        assertEquals(SegmentAction.WALK, route.segments[1].action)
        assertEquals(10, route.segments[1].steps)
        assertEquals(SegmentAction.DESTINATION, route.segments[2].action)
        assertEquals(10, route.totalSteps)
    }

    @Test
    fun testNoUnnecessaryZeroStepTurnSegmentsAreCreated() {
        val engine = RouteLearningEngine()
        engine.startLearning()

        // User walks 6 steps
        for (step in 1..6) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        // A false turn candidate fires for 15 samples with 0 steps
        for (i in 1..15) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = 6,
                    movementState = MovementState.TURNING,
                    turnDirection = TurnDirection.RIGHT,
                    confidence = 75
                )
            )
        }

        // Turn drops back to NONE immediately (< 800ms)
        engine.processMovementEvent(
            MovementEvent(
                totalSteps = 6,
                movementState = MovementState.WALKING,
                turnDirection = TurnDirection.NONE,
                confidence = 85
            )
        )

        val route = engine.stopAndCreateRoute("No False Turn Route")

        // Must NOT contain any TURN segment
        val turnSegments = route.segments.filter { it.action == SegmentAction.TURN }
        assertTrue("No false 0-step TURN segment should exist", turnSegments.isEmpty())
        assertEquals(6, route.totalSteps)
    }

    @Test
    fun testGenuineTurn_candidateBeginsBeforeStreakThreshold_successfullyRecordedWithDefaultThreshold() {
        val engine = RouteLearningEngine()
        // Use default production threshold: 800ms
        assertEquals(800L, engine.minTurnDurationMs)
        engine.startLearning()

        // Walk 5 steps straight
        for (step in 1..5) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        // Candidate begins at t0. We simulate 15 samples (streak threshold)
        for (i in 1..15) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = 5,
                    movementState = MovementState.TURNING,
                    turnDirection = TurnDirection.LEFT,
                    confidence = 85
                )
            )
        }

        // The turn continues until total elapsed time from candidate start exceeds 800ms
        // Sleep to simulate genuine physical turn duration (850ms total from candidate start)
        Thread.sleep(850)

        // Turn completes: rotation stops, returning to straight walking
        engine.processMovementEvent(
            MovementEvent(
                totalSteps = 6,
                movementState = MovementState.WALKING,
                turnDirection = TurnDirection.NONE,
                confidence = 85
            )
        )

        // User continues walking straight for 5 more steps
        for (step in 7..11) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        val route = engine.stopAndCreateRoute("Candidate Start Timing Route")

        // Genuine turn must NOT be rejected!
        // Segments: START -> WALK (5 steps) -> TURN (1 step, LEFT) -> WALK (5 steps) -> DESTINATION
        assertEquals(5, route.segments.size)
        assertEquals(SegmentAction.START, route.segments[0].action)
        assertEquals(SegmentAction.WALK, route.segments[1].action)
        assertEquals(5, route.segments[1].steps)
        assertEquals(SegmentAction.TURN, route.segments[2].action)
        assertEquals(SegmentDirection.LEFT, route.segments[2].direction)
        assertEquals(1, route.segments[2].steps)
        assertEquals(SegmentAction.WALK, route.segments[3].action)
        assertEquals(5, route.segments[3].steps)
        assertEquals(SegmentAction.DESTINATION, route.segments[4].action)
        assertEquals(11, route.totalSteps)
    }

    @Test
    fun testGenuineRightTurn_recordedCorrectly() {
        val engine = RouteLearningEngine()
        engine.minTurnDurationMs = 50L
        engine.startLearning()

        // Walk 4 steps straight
        for (step in 1..4) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        // Sustained RIGHT turn evidence (15 samples)
        for (i in 1..15) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = 4,
                    movementState = MovementState.TURNING,
                    turnDirection = TurnDirection.RIGHT,
                    confidence = 85
                )
            )
        }

        // Wait to exceed minTurnDurationMs
        Thread.sleep(70)

        // Turn completes
        engine.processMovementEvent(
            MovementEvent(
                totalSteps = 5,
                movementState = MovementState.WALKING,
                turnDirection = TurnDirection.NONE,
                confidence = 85
            )
        )

        // Walk 3 more steps
        for (step in 6..8) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        val route = engine.stopAndCreateRoute("Right Turn Route")

        assertEquals(5, route.segments.size)
        assertEquals(SegmentAction.START, route.segments[0].action)
        assertEquals(SegmentAction.WALK, route.segments[1].action)
        assertEquals(4, route.segments[1].steps)
        assertEquals(SegmentAction.TURN, route.segments[2].action)
        assertEquals(SegmentDirection.RIGHT, route.segments[2].direction)
        assertEquals(1, route.segments[2].steps)
        assertEquals(SegmentAction.WALK, route.segments[3].action)
        assertEquals(3, route.segments[3].steps)
        assertEquals(SegmentAction.DESTINATION, route.segments[4].action)
        assertEquals(8, route.totalSteps)
    }

    @Test
    fun testShortFalseTurnSpike_stillRejectedBeforeMinimumDuration() {
        val engine = RouteLearningEngine()
        // Default 800ms minimum turn duration
        engine.minTurnDurationMs = 800L
        engine.startLearning()

        // Walk 6 steps straight
        for (step in 1..6) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        // Streak reaches 15 samples
        for (i in 1..15) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = 6,
                    movementState = MovementState.TURNING,
                    turnDirection = TurnDirection.LEFT,
                    confidence = 85
                )
            )
        }

        // But rotation ceases immediately (only ~50ms total elapsed, far below 800ms)
        Thread.sleep(50)
        engine.processMovementEvent(
            MovementEvent(
                totalSteps = 6,
                movementState = MovementState.WALKING,
                turnDirection = TurnDirection.NONE,
                confidence = 85
            )
        )

        // Walk 4 more steps
        for (step in 7..10) {
            engine.processMovementEvent(
                MovementEvent(
                    totalSteps = step,
                    movementState = MovementState.WALKING,
                    turnDirection = TurnDirection.NONE,
                    confidence = 90
                )
            )
        }

        val route = engine.stopAndCreateRoute("Rejected Spike Route")

        // Must NOT contain any TURN segment, walk must NOT be split
        val turnSegments = route.segments.filter { it.action == SegmentAction.TURN }
        assertTrue("Short spike must be rejected as false turn", turnSegments.isEmpty())
        assertEquals(3, route.segments.size) // START, WALK (10 steps), DESTINATION
        assertEquals(10, route.segments[1].steps)
        assertEquals(10, route.totalSteps)
    }
}
