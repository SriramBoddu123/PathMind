package com.example.pathmind.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.pathmind.model.MovementEvent
import com.example.pathmind.model.MovementState
import com.example.pathmind.model.SensorAvailability
import com.example.pathmind.model.TurnDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Real device sensor engine for PathMind.
 * Processes Accelerometer, Gyroscope, Step Detector, and Step Counter.
 * Implements low-pass filtering, variance analysis, debouncing, and orientation-independent yaw projection.
 */
class MovementSensorEngine(context: Context? = null) : SensorEventListener {

    companion object {
        // Calibration starting points for sensor filtering and turn detection
        const val DEFAULT_GRAVITY_ALPHA = 0.08f           // Low-pass filter factor for gravity vector (~0.25-0.5s at 50Hz)
        const val DEFAULT_GYRO_ALPHA = 0.20f              // EMA smoothing factor for yaw angular velocity
        const val DEFAULT_TURN_YAW_RATE_THRESHOLD = 0.60f // rad/s (~34 deg/s) required for turn detection
        const val DEFAULT_MIN_TURN_ANGLE_DEG = 25.0f      // degrees - minimum accumulated heading change
        const val DEFAULT_YAW_NOISE_FLOOR = 0.18f         // rad/s below which yaw integration bleeds off
        const val DEFAULT_YAW_BLEED_RATE = 0.95f          // decay factor per sample when below noise floor
    }

    // Calibratable parameters
    var gravityAlpha: Float = DEFAULT_GRAVITY_ALPHA
    var gyroAlpha: Float = DEFAULT_GYRO_ALPHA
    var turnYawRateThreshold: Float = DEFAULT_TURN_YAW_RATE_THRESHOLD
    var minTurnAngleDeg: Float = DEFAULT_MIN_TURN_ANGLE_DEG
    var yawNoiseFloor: Float = DEFAULT_YAW_NOISE_FLOOR
    var yawBleedRate: Float = DEFAULT_YAW_BLEED_RATE

    private val sensorManager: SensorManager? = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val stepDetector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val stepCounter: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    var onMovementUpdated: ((MovementEvent, rawDebug: String) -> Unit)? = null

    // Real Step Tracking
    private var stepsFromDetector: Int = 0
    private var initialStepCounterValue: Int = -1
    private var stepsFromCounter: Int = 0
    private var lastStepTimestamp: Long = 0L

    // Accelerometer processing (Sliding window for variance / energy)
    private val accelWindowSize = 15
    private val accelMagnitudes = ArrayList<Float>(accelWindowSize)
    private var lastAccelX: Float = 0f
    private var lastAccelY: Float = 9.81f
    private var lastAccelZ: Float = 0f

    // Low-pass filtered gravity vector estimation
    // Separates slow gravity orientation from high-frequency dynamic footfall acceleration
    private var gravityX: Float = 0f
    private var gravityY: Float = 9.81f
    private var gravityZ: Float = 0f

    // Gyroscope processing (Vertical yaw projection)
    private var smoothedYawRate: Float = 0f

    // State Smoothing / Debouncing
    private var candidateState: MovementState = MovementState.UNKNOWN
    private var candidateStreak: Int = 0
    private val debounceThreshold: Int = 4 // Consecutive samples required to change state
    private var currentState: MovementState = MovementState.UNKNOWN
    private var currentConfidence: Int = 0
    private var currentTurnDirection: TurnDirection = TurnDirection.NONE

    // Kinematic Feature Statistics for AI/ML
    var currentVariance: Float = 0f; private set
    var integratedYawDeg: Float = 0f; private set
    var stepCadenceHz: Float = 0f; private set
    var stateTransitionJitter: Float = 0f; private set
    var recentHesitationScore: Float = 0f; private set
    val sensorConfidenceNorm: Float get() = (currentConfidence / 100f).coerceIn(0f, 1f)
    fun getSmoothedYawRate(): Float = smoothedYawRate
    fun getEstimatedGravity(): Triple<Float, Float, Float> = Triple(gravityX, gravityY, gravityZ)
    fun getCurrentMovementState(): MovementState = currentState
    fun getCurrentTurnDirection(): TurnDirection = currentTurnDirection

    private var lastGyroTimestampNs: Long = 0L
    private val recentStepTimestamps = ArrayList<Long>(5)
    private val recentStateHistory = ArrayList<MovementState>(12)

    private var isListening: Boolean = false

    fun checkAvailability(): SensorAvailability {
        return SensorAvailability(
            accelerometer = accelerometer != null,
            gyroscope = gyroscope != null,
            stepDetector = stepDetector != null,
            stepCounter = stepCounter != null
        )
    }

    fun startListening(): Boolean {
        if (isListening) return true

        // Reset runtime values
        stepsFromDetector = 0
        initialStepCounterValue = -1
        stepsFromCounter = 0
        lastStepTimestamp = 0L
        accelMagnitudes.clear()
        smoothedYawRate = 0f
        integratedYawDeg = 0f
        gravityX = 0f
        gravityY = 9.81f
        gravityZ = 0f
        candidateStreak = 0
        currentState = MovementState.UNKNOWN
        currentTurnDirection = TurnDirection.NONE
        lastGyroTimestampNs = 0L

        // Register available sensors with UI delay (~50-60Hz)
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        stepDetector?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        stepCounter?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        isListening = true
        return true
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
    }

    fun isRunning(): Boolean = isListening

    fun resetSteps() {
        stepsFromDetector = 0
        initialStepCounterValue = -1
        stepsFromCounter = 0
        lastStepTimestamp = 0L
        emitEvent()
    }

    fun resetIntegratedYaw() {
        integratedYawDeg = 0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                processStepDetector()
            }

            Sensor.TYPE_STEP_COUNTER -> {
                processStepCounter(event.values[0].toInt())
            }

            Sensor.TYPE_ACCELEROMETER -> {
                processAccelerometer(event.values[0], event.values[1], event.values[2])
            }

            Sensor.TYPE_GYROSCOPE -> {
                processGyroscope(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for standard movement processing
    }

    fun processStepDetector(timestampMs: Long = System.currentTimeMillis()) {
        stepsFromDetector++
        lastStepTimestamp = timestampMs
        recentStepTimestamps.add(timestampMs)
        if (recentStepTimestamps.size > 4) recentStepTimestamps.removeAt(0)
        if (recentStepTimestamps.size >= 2) {
            val dtSec = (recentStepTimestamps.last() - recentStepTimestamps.first()) / 1000f
            if (dtSec > 0.2f) {
                stepCadenceHz = ((recentStepTimestamps.size - 1) / dtSec).coerceIn(0f, 3.5f)
            }
        }
        emitEvent()
    }

    fun processStepCounter(rawCount: Int, timestampMs: Long = System.currentTimeMillis()) {
        if (initialStepCounterValue < 0) {
            initialStepCounterValue = rawCount
        }
        val prevSteps = stepsFromCounter
        stepsFromCounter = max(0, rawCount - initialStepCounterValue)
        if (stepsFromCounter > prevSteps) {
            lastStepTimestamp = timestampMs
            recentStepTimestamps.add(timestampMs)
            if (recentStepTimestamps.size > 4) recentStepTimestamps.removeAt(0)
            if (recentStepTimestamps.size >= 2) {
                val dtSec = (recentStepTimestamps.last() - recentStepTimestamps.first()) / 1000f
                if (dtSec > 0.2f) {
                    stepCadenceHz = ((recentStepTimestamps.size - 1) / dtSec).coerceIn(0f, 3.5f)
                }
            }
        }
        emitEvent()
    }

    fun processAccelerometer(x: Float, y: Float, z: Float) {
        // Low-pass filter gravity estimation: isolates vertical orientation from footfall shocks
        gravityX = (1f - gravityAlpha) * gravityX + gravityAlpha * x
        gravityY = (1f - gravityAlpha) * gravityY + gravityAlpha * y
        gravityZ = (1f - gravityAlpha) * gravityZ + gravityAlpha * z

        lastAccelX = x
        lastAccelY = y
        lastAccelZ = z

        val magnitude = sqrt(x * x + y * y + z * z)

        accelMagnitudes.add(magnitude)
        if (accelMagnitudes.size > accelWindowSize) {
            accelMagnitudes.removeAt(0)
        }

        evaluateMovementState()
    }

    fun processGyroscope(wx: Float, wy: Float, wz: Float, timestampNs: Long = 0L) {
        // Project angular velocity onto low-pass estimated gravity vector:
        // yawRate = (w · g) / ||g||
        val gravNorm = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        val rawYawRate = if (gravNorm > 1.0f) {
            (wx * gravityX + wy * gravityY + wz * gravityZ) / gravNorm
        } else {
            wz // Fallback to Z axis rotation if gravity magnitude is near 0
        }

        // Integrate yaw rotation angle over time
        if (lastGyroTimestampNs > 0L && timestampNs > 0L) {
            val dt = (timestampNs - lastGyroTimestampNs) / 1_000_000_000.0f
            if (dt in 0.001f..0.2f) {
                val deltaDeg = (rawYawRate * (180.0 / Math.PI)).toFloat() * dt
                integratedYawDeg = (integratedYawDeg + deltaDeg).coerceIn(-180f, 180f)
            }
        }
        lastGyroTimestampNs = timestampNs

        // Apply exponential moving average (EMA) filter
        smoothedYawRate = gyroAlpha * rawYawRate + (1f - gyroAlpha) * smoothedYawRate

        // Slowly bleed integrated yaw towards 0 if yaw rate is below noise floor
        if (abs(smoothedYawRate) < yawNoiseFloor) {
            integratedYawDeg *= yawBleedRate
            if (abs(integratedYawDeg) < 0.5f) {
                integratedYawDeg = 0f
            }
        }

        // Determine turn direction: requires sustained angular velocity AND accumulated heading change
        currentTurnDirection = when {
            sensorManager != null && gyroscope == null -> TurnDirection.UNKNOWN
            smoothedYawRate > turnYawRateThreshold && integratedYawDeg >= minTurnAngleDeg -> TurnDirection.LEFT
            smoothedYawRate < -turnYawRateThreshold && integratedYawDeg <= -minTurnAngleDeg -> TurnDirection.RIGHT
            else -> TurnDirection.NONE
        }

        evaluateMovementState()
    }

    private fun evaluateMovementState() {
        if (accelMagnitudes.size < 5) {
            currentState = MovementState.UNKNOWN
            currentConfidence = 20
            emitEvent()
            return
        }

        // Calculate variance (energy of movement)
        val mean = accelMagnitudes.average().toFloat()
        var sumSquares = 0f
        for (m in accelMagnitudes) {
            val diff = m - mean
            sumSquares += diff * diff
        }
        val variance = sumSquares / accelMagnitudes.size
        currentVariance = variance

        val timeSinceStep = System.currentTimeMillis() - lastStepTimestamp
        val hasRecentStep = lastStepTimestamp > 0L && timeSinceStep < 2200L
        if (timeSinceStep > 2400L) {
            stepCadenceHz = 0f
        }
        recentHesitationScore = if (hasRecentStep && timeSinceStep > 1200L) {
            ((timeSinceStep - 1200f) / 1000f).coerceIn(0f, 1f)
        } else {
            0f
        }

        val absYaw = abs(smoothedYawRate)

        // Classify candidate state based on real physics
        val (stateCandidate, confidenceCandidate) = when {
            // Significant angular velocity AND accumulated heading change indicate turning
            absYaw >= turnYawRateThreshold && abs(integratedYawDeg) >= minTurnAngleDeg -> {
                val conf = ((absYaw / 1.5f).coerceIn(0.70f, 0.95f) * 100).toInt()
                MovementState.TURNING to conf
            }

            // Real physical steps or periodic walking acceleration variance
            hasRecentStep || variance > 0.65f -> {
                val conf = if (hasRecentStep) 92 else 78
                MovementState.WALKING to conf
            }

            // Low variance and low gyro rotation indicates resting/stationary
            variance < 0.14f && absYaw < yawNoiseFloor -> {
                val stability = (1f - (variance / 0.14f)).coerceIn(0.85f, 0.98f)
                MovementState.STATIONARY to (stability * 100).toInt()
            }

            // Borderline or noisy reading without clear pattern -> UNKNOWN
            else -> {
                MovementState.UNKNOWN to 35
            }
        }

        // Track candidate state history for jitter calculation
        recentStateHistory.add(stateCandidate)
        if (recentStateHistory.size > 10) recentStateHistory.removeAt(0)
        if (recentStateHistory.size >= 3) {
            var flips = 0
            for (i in 1 until recentStateHistory.size) {
                if (recentStateHistory[i] != recentStateHistory[i - 1]) flips++
            }
            stateTransitionJitter = (flips.toFloat() / (recentStateHistory.size - 1)).coerceIn(0f, 1f)
        }

        // Apply debouncing / smoothing to prevent noisy flipping
        if (stateCandidate == candidateState) {
            candidateStreak++
            if (candidateStreak >= debounceThreshold) {
                currentState = candidateState
                currentConfidence = confidenceCandidate
            }
        } else {
            candidateState = stateCandidate
            candidateStreak = 1
        }

        emitEvent(variance, absYaw)
    }

    private fun emitEvent(variance: Float = 0f, yawRate: Float = 0f) {
        val totalSteps = max(stepsFromDetector, stepsFromCounter)

        val event = MovementEvent(
            timestamp = System.currentTimeMillis(),
            movementState = currentState,
            totalSteps = totalSteps,
            turnDirection = currentTurnDirection,
            confidence = currentConfidence
        )

        val debugInfo = "Var: %.2f | Yaw: %.2f rad/s | Steps(Det: %d, Ctr: %d)".format(
            variance,
            smoothedYawRate,
            stepsFromDetector,
            stepsFromCounter
        )

        onMovementUpdated?.invoke(event, debugInfo)
    }
}
