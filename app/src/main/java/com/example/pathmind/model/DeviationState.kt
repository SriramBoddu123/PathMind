package com.example.pathmind.model

/**
 * Represents the deviation and route compliance state during navigation.
 */
enum class DeviationState {
    ON_ROUTE,
    POSSIBLE_DEVIATION,
    DEVIATION_CONFIRMED,
    RECOVERING,
    RECOVERED,
    UNCERTAIN
}
