package com.example.pathmind.sensor

import android.content.Context
import android.util.Log
import com.example.pathmind.model.SpatialMLClass
import com.example.pathmind.model.SpatialModelOutput
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Stage 7: Genuine On-Device AI/ML Spatial Reasoning Engine.
 *
 * Executes the genuinely trained PathMind Deep MLP (18 -> 32 -> 16 -> 5)
 * directly on the phone's CPU with zero cloud APIs, zero internet, zero GPS.
 *
 * Computes:
 * - 18-feature z-score normalization
 * - 2-layer ReLU feedforward pass
 * - Numerically stable Softmax distribution
 * - Normalized Shannon Entropy: H = -sum(p * ln(p)) / ln(5)
 * - Calibrated Mathematical Confidence: max(p) * (1 - 0.5 * H)
 * - Microsecond-precision inference latency benchmark via System.nanoTime()
 */
class SpatialMLInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "SpatialMLInference"
        private const val MODEL_ASSET_NAME = "pathmind_spatial_model.bin"
        private const val MAGIC_HEADER = 0x4C4D4D50 // 'PMML' in little-endian

        const val NUM_FEATURES = 18
        const val NUM_CLASSES = 5
        private val LN_5 = ln(5.0)
    }

    private var isModelLoaded = false
    private var modelFileSizeKb: Float = 0f

    // Layer dimensions
    private var inDim: Int = 18
    private var h1Dim: Int = 32
    private var h2Dim: Int = 16
    private var outDim: Int = 5

    // Normalization vectors
    private lateinit var featureMean: FloatArray
    private lateinit var featureStd: FloatArray

    // Trained weights & biases
    private lateinit var w1: FloatArray // inDim x h1Dim
    private lateinit var b1: FloatArray // h1Dim
    private lateinit var w2: FloatArray // h1Dim x h2Dim
    private lateinit var b2: FloatArray // h2Dim
    private lateinit var w3: FloatArray // h2Dim x outDim
    private lateinit var b3: FloatArray // outDim

    // Reusable buffers to avoid GC allocations during real-time inference
    private val h1Buffer = FloatArray(32)
    private val h2Buffer = FloatArray(16)
    private val logitsBuffer = FloatArray(5)
    private val expLogitsBuffer = DoubleArray(5)
    private val normFeaturesBuffer = FloatArray(18)

    // Latency benchmark tracker (last 100 inferences)
    private val latencyHistory = ArrayList<Float>(100)

    init {
        loadModel()
    }

    /**
     * Loads the genuinely trained model weights and normalization parameters from assets.
     */
    private fun loadModel() {
        try {
            val assetManager = context.assets
            val inputStream: InputStream = assetManager.open(MODEL_ASSET_NAME)
            val bytes = inputStream.readBytes()
            inputStream.close()

            modelFileSizeKb = bytes.size / 1024f

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buffer.int
            if (magic != MAGIC_HEADER) {
                Log.e(TAG, "Invalid model magic header: 0x${Integer.toHexString(magic)}")
                return
            }

            val version = buffer.int
            inDim = buffer.int
            h1Dim = buffer.int
            h2Dim = buffer.int
            outDim = buffer.int

            Log.i(TAG, "Loading model v$version: $inDim -> $h1Dim -> $h2Dim -> $outDim (${bytes.size} bytes)")

            // Read normalization parameters
            featureMean = FloatArray(inDim)
            for (i in 0 until inDim) featureMean[i] = buffer.float

            featureStd = FloatArray(inDim)
            for (i in 0 until inDim) {
                val s = buffer.float
                featureStd[i] = if (s < 1e-5f) 1.0f else s
            }

            // Read W1 & b1
            w1 = FloatArray(inDim * h1Dim)
            for (i in 0 until (inDim * h1Dim)) w1[i] = buffer.float
            b1 = FloatArray(h1Dim)
            for (i in 0 until h1Dim) b1[i] = buffer.float

            // Read W2 & b2
            w2 = FloatArray(h1Dim * h2Dim)
            for (i in 0 until (h1Dim * h2Dim)) w2[i] = buffer.float
            b2 = FloatArray(h2Dim)
            for (i in 0 until h2Dim) b2[i] = buffer.float

            // Read W3 & b3
            w3 = FloatArray(h2Dim * outDim)
            for (i in 0 until (h2Dim * outDim)) w3[i] = buffer.float
            b3 = FloatArray(outDim)
            for (i in 0 until outDim) b3[i] = buffer.float

            isModelLoaded = true
            Log.i(TAG, "Successfully loaded PathMind spatial ML model (${modelFileSizeKb} KB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets: ${e.message}", e)
            isModelLoaded = false
        }
    }

    /**
     * Executes forward inference with live microsecond latency benchmark.
     */
    fun predict(rawFeatures: FloatArray): SpatialModelOutput {
        if (!isModelLoaded || rawFeatures.size < NUM_FEATURES) {
            return fallbackOutput(rawFeatures)
        }

        val startTimeNs = System.nanoTime()

        // 1. Z-Score Normalization: (x - mean) / std
        for (i in 0 until NUM_FEATURES) {
            normFeaturesBuffer[i] = (rawFeatures[i] - featureMean[i]) / featureStd[i]
        }

        // 2. Hidden Layer 1: ReLU(W1 * x + b1)
        for (j in 0 until h1Dim) {
            var sum = b1[j]
            for (i in 0 until inDim) {
                sum += normFeaturesBuffer[i] * w1[i * h1Dim + j]
            }
            h1Buffer[j] = if (sum > 0f) sum else 0f
        }

        // 3. Hidden Layer 2: ReLU(W2 * h1 + b2)
        for (j in 0 until h2Dim) {
            var sum = b2[j]
            for (i in 0 until h1Dim) {
                sum += h1Buffer[i] * w2[i * h2Dim + j]
            }
            h2Buffer[j] = if (sum > 0f) sum else 0f
        }

        // 4. Output Logits: W3 * h2 + b3
        var maxLogit = Float.NEGATIVE_INFINITY
        for (j in 0 until outDim) {
            var sum = b3[j]
            for (i in 0 until h2Dim) {
                sum += h2Buffer[i] * w3[i * outDim + j]
            }
            logitsBuffer[j] = sum
            if (sum > maxLogit) {
                maxLogit = sum
            }
        }

        // 5. Numerically Stable Softmax: exp(z - max) / sum(exp(z - max))
        var sumExp = 0.0
        for (j in 0 until outDim) {
            val expVal = exp((logitsBuffer[j] - maxLogit).toDouble())
            expLogitsBuffer[j] = expVal
            sumExp += expVal
        }

        val probabilities = FloatArray(outDim)
        var maxProb = 0f
        var maxIndex = 0
        for (j in 0 until outDim) {
            val p = (expLogitsBuffer[j] / maxExpSumSafe(sumExp)).toFloat()
            probabilities[j] = p
            if (p > maxProb) {
                maxProb = p
                maxIndex = j
            }
        }

        // 6. Normalized Shannon Entropy: H = -sum(p * ln(p)) / ln(5)
        var shannonEntropy = 0.0
        for (j in 0 until outDim) {
            val p = probabilities[j]
            if (p > 1e-7f) {
                shannonEntropy -= p * ln(p.toDouble())
            }
        }
        val normalizedEntropy = (shannonEntropy / LN_5).toFloat().coerceIn(0f, 1f)

        // 7. Calibrated Mathematical Confidence: max(p) * (1 - 0.5 * H)
        val calibratedConf = (maxProb * (1f - 0.5f * normalizedEntropy) * 100f).toInt().coerceIn(0, 100)

        // 8. Benchmark latency
        val endTimeNs = System.nanoTime()
        val latencyMs = ((endTimeNs - startTimeNs) / 1_000_000f)

        synchronized(latencyHistory) {
            if (latencyHistory.size >= 100) {
                latencyHistory.removeAt(0)
            }
            latencyHistory.add(latencyMs)
        }

        val predictedClass = SpatialMLClass.fromIndex(maxIndex)

        return SpatialModelOutput(
            predictedClass = predictedClass,
            probabilities = probabilities,
            normalizedEntropy = normalizedEntropy,
            calibratedConfidence = calibratedConf,
            inferenceLatencyMs = latencyMs,
            rawFeatures = rawFeatures.copyOf()
        )
    }

    private fun maxExpSumSafe(sum: Double): Double = if (sum <= 0.0) 1e-12 else sum

    private fun fallbackOutput(features: FloatArray): SpatialModelOutput {
        return SpatialModelOutput(
            predictedClass = SpatialMLClass.UNCERTAIN,
            probabilities = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f),
            normalizedEntropy = 1.0f,
            calibratedConfidence = 20,
            inferenceLatencyMs = 0.1f,
            rawFeatures = if (features.size == NUM_FEATURES) features.copyOf() else FloatArray(NUM_FEATURES)
        )
    }

    fun isReady(): Boolean = isModelLoaded

    fun getModelFileSizeKb(): Float = modelFileSizeKb

    fun getAverageLatencyMs(): Float {
        synchronized(latencyHistory) {
            if (latencyHistory.isEmpty()) return 0f
            return latencyHistory.average().toFloat()
        }
    }
}
