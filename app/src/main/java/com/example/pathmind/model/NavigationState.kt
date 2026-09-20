package com.example.pathmind.model

enum class NavigationState {
    IDLE,
    STARTING,
    NAVIGATING,
    TURN_REQUIRED,
    UNCERTAIN,
    ARRIVED
}

data class NavigationSnapshot(
    val state: NavigationState,
    val instruction: String,
    val currentSegmentIndex: Int,
    val totalSegments: Int,
    val currentSegment: RouteSegment?,
    val stepsInCurrentSegment: Int,
    val expectedStepsInSegment: Int,
    val totalStepsObserved: Int,
    val confidence: Int,
    val isTargetReached: Boolean,
    val targetName: String,
    val deviationState: DeviationState = DeviationState.ON_ROUTE,
    val deviationMessage: String = "",
    val lastConfirmedSegmentIndex: Int = 0,
    val recoveryInstruction: String? = null,
    val mlOutput: SpatialModelOutput? = null,
    val debugInfo: String = ""
)

