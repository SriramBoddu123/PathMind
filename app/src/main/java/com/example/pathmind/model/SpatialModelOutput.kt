package com.example.pathmind.model

/**
 * Output classes for the PathMind On-Device Spatial Intent Classifier.
 */
enum class SpatialMLClass(val label: String, val index: Int) {
    ON_ROUTE("ON ROUTE", 0),
    DRIFT_WARNING("DRIFT WARNING", 1),
    DEVIATION_CONFIRMED("DEVIATION CONFIRMED", 2),
    RECOVERING("RECOVERING", 3),
    UNCERTAIN("UNCERTAIN", 4);

    companion object {
        fun fromIndex(index: Int): SpatialMLClass =
            entries.firstOrNull { it.index == index } ?: UNCERTAIN
    }
}

/**
 * Full inference result from the on-device neural model.
 * Contains Softmax probabilities, Shannon entropy, calibrated confidence,
 * inference latency, and the input feature vector.
 */
data class SpatialModelOutput(
    val predictedClass: SpatialMLClass,
    val probabilities: FloatArray, // Size 5, sums to 1.0
    val normalizedEntropy: Float,  // 0.0 (certain) to 1.0 (uniform confusion)
    val calibratedConfidence: Int, // 0 to 100%
    val inferenceLatencyMs: Float, // Measured in ms via System.nanoTime()
    val rawFeatures: FloatArray    // 18-element raw kinematic feature vector
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpatialModelOutput

        if (predictedClass != other.predictedClass) return false
        if (!probabilities.contentEquals(other.probabilities)) return false
        if (normalizedEntropy != other.normalizedEntropy) return false
        if (calibratedConfidence != other.calibratedConfidence) return false
        if (inferenceLatencyMs != other.inferenceLatencyMs) return false
        if (!rawFeatures.contentEquals(other.rawFeatures)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = predictedClass.hashCode()
        result = 31 * result + probabilities.contentHashCode()
        result = 31 * result + normalizedEntropy.hashCode()
        result = 31 * result + calibratedConfidence
        result = 31 * result + inferenceLatencyMs.hashCode()
        result = 31 * result + rawFeatures.contentHashCode()
        return result
    }
}
