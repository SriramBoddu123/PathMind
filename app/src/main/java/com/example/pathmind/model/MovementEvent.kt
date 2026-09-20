package com.example.pathmind.model

enum class MovementState {
    STATIONARY,
    WALKING,
    TURNING,
    UNKNOWN
}

enum class TurnDirection {
    LEFT,
    RIGHT,
    NONE,
    UNKNOWN
}

data class MovementEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val movementState: MovementState = MovementState.UNKNOWN,
    val totalSteps: Int = 0,
    val turnDirection: TurnDirection = TurnDirection.NONE,
    val confidence: Int = 0 // 0 to 100%
)

data class SensorAvailability(
    val accelerometer: Boolean = false,
    val gyroscope: Boolean = false,
    val stepDetector: Boolean = false,
    val stepCounter: Boolean = false
)
