package com.example.pathmind.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Stage 8C: Hardened Offline On-Device Speech Recognition Engine using Vosk Android.
 * Features 7-second silence timeout watchdog, 10-second max duration cap, and debouncing.
 */
class OfflineSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "PathMindVosk"
        private const val MODEL_ZIP_ASSET = "models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000.0f
        private const val SILENCE_TIMEOUT_MS = 7000L
        private const val MAX_DURATION_MS = 10000L
    }

    interface Listener {
        fun onModelLoading(status: String)
        fun onModelReady(loadDurationMs: Long)
        fun onListeningStateChanged(isListening: Boolean)
        fun onPartialResult(hypothesis: String)
        fun onFinalResult(hypothesis: String, recognitionLatencyMs: Long)
        fun onSilenceTimeout()
        fun onError(errorMessage: String)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var silenceWatchdogRunnable: Runnable? = null
    private var maxDurationRunnable: Runnable? = null
    private var hasHeardSpeech = false

    private var speechStartTimeMs: Long = 0
    private var isModelLoading = false
    private var isListening = false
    private var lastActionTimeMs: Long = 0

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    val isReady: Boolean
        get() = model != null

    val isCurrentlyListening: Boolean
        get() = isListening

    /**
     * Initializes the Vosk model in a background coroutine.
     * Unpacks from assets if not already cached in internal storage.
     */
    fun initialize(scope: CoroutineScope, onReady: (() -> Unit)? = null) {
        if (model != null) {
            listener?.onModelReady(0)
            onReady?.invoke()
            return
        }
        if (isModelLoading) return
        isModelLoading = true

        listener?.onModelLoading("Preparing offline voice engine...")

        scope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val modelDir = ensureModelExtracted()
                Log.d(TAG, "Model directory verified at: ${modelDir.absolutePath}")

                val initStartTime = System.currentTimeMillis()
                val loadedModel = Model(modelDir.absolutePath)
                val initDuration = System.currentTimeMillis() - initStartTime
                val totalDuration = System.currentTimeMillis() - startTime

                Log.d(TAG, "ModelInit: Native load time = ${initDuration}ms, Total setup = ${totalDuration}ms")

                model = loadedModel
                isModelLoading = false

                withContext(Dispatchers.Main) {
                    listener?.onModelReady(totalDuration)
                    onReady?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load offline speech model", e)
                isModelLoading = false
                withContext(Dispatchers.Main) {
                    listener?.onError("Model load failed: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Ensures model is extracted from APK assets to private internal storage.
     */
    private fun ensureModelExtracted(): File {
        val targetDir = File(context.filesDir, MODEL_DIR_NAME)
        val markerFile = File(targetDir, "am/final.mdl")

        if (markerFile.exists()) {
            Log.d(TAG, "Model already cached in internal storage (${targetDir.absolutePath})")
            return targetDir
        }

        Log.d(TAG, "Unpacking model from asset: $MODEL_ZIP_ASSET to ${targetDir.absolutePath}")
        targetDir.mkdirs()

        val assetStream: InputStream = context.assets.open(MODEL_ZIP_ASSET)
        ZipInputStream(assetStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            val buffer = ByteArray(8192)

            while (entry != null) {
                val entryName = entry.name
                val cleanName = if (entryName.startsWith("$MODEL_DIR_NAME/")) {
                    entryName.substring("$MODEL_DIR_NAME/".length)
                } else {
                    entryName
                }

                if (cleanName.isNotEmpty()) {
                    val outFile = File(targetDir, cleanName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (!markerFile.exists()) {
            throw IllegalStateException("Model extraction incomplete: am/final.mdl not found")
        }

        return targetDir
    }

    /**
     * Builds a grammar constraint JSON string for high-precision domain recognition.
     */
    fun buildGrammar(customWords: List<String> = emptyList()): String {
        val baseWords = listOf(
            "remember", "where", "did", "i", "we", "park", "parked", "left", "placed", "put", "am", "at",
            "save", "this", "current", "place", "location", "spot", "as", "mark",
            "my", "the", "a", "an", "here", "to", "take", "me", "navigate", "find", "guide", "get", "how",
            "route", "start", "begin", "follow", "navigation", "cancel", "stop", "dismiss", "exit", "never", "mind",
            "bike", "car", "canteen", "library", "office", "desk", "room", "door", "gate", "sai",
            "[unk]"
        )

        val allWords = (baseWords + customWords.map { it.lowercase().trim() })
            .filter { it.isNotEmpty() }
            .distinct()

        val jsonArray = JSONArray(allWords)
        return jsonArray.toString()
    }

    /**
     * Starts listening for speech from the microphone with debouncing and watchdog timers.
     */
    fun startListening(grammarJson: String? = null) {
        val now = System.currentTimeMillis()
        if (now - lastActionTimeMs < 400L) return // Debounce rapid taps
        lastActionTimeMs = now

        val activeModel = model
        if (activeModel == null) {
            listener?.onError("Model not loaded yet")
            return
        }

        if (isListening) return

        try {
            recognizer?.close()
            val rec = if (!grammarJson.isNullOrBlank()) {
                Recognizer(activeModel, SAMPLE_RATE, grammarJson)
            } else {
                Recognizer(activeModel, SAMPLE_RATE)
            }
            recognizer = rec

            speechStartTimeMs = System.currentTimeMillis()
            hasHeardSpeech = false
            val service = SpeechService(rec, SAMPLE_RATE)
            speechService = service

            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val partialText = parseHypothesis(hypothesis, "partial")
                    if (partialText.isNotBlank()) {
                        hasHeardSpeech = true
                        cancelSilenceWatchdog()
                        listener?.onPartialResult(partialText)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = parseHypothesis(hypothesis, "text")
                    if (text.isNotBlank()) {
                        hasHeardSpeech = true
                        cancelSilenceWatchdog()
                        val latency = System.currentTimeMillis() - speechStartTimeMs
                        listener?.onFinalResult(text, latency)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = parseHypothesis(hypothesis, "text")
                    val latency = System.currentTimeMillis() - speechStartTimeMs
                    if (text.isNotBlank()) {
                        hasHeardSpeech = true
                        cancelSilenceWatchdog()
                        listener?.onFinalResult(text, latency)
                    }
                    stopListening()
                }

                override fun onError(e: Exception?) {
                    Log.e(TAG, "Vosk Recognition error", e)
                    listener?.onError(e?.localizedMessage ?: "Speech error")
                    stopListening()
                }

                override fun onTimeout() {
                    Log.d(TAG, "Vosk speech timeout")
                    if (!hasHeardSpeech) {
                        listener?.onSilenceTimeout()
                    }
                    stopListening()
                }
            })

            isListening = true
            listener?.onListeningStateChanged(true)
            Log.d(TAG, "SpeechService started listening at 16kHz")

            // Setup 7-second silence watchdog
            cancelSilenceWatchdog()
            silenceWatchdogRunnable = Runnable {
                if (isListening && !hasHeardSpeech) {
                    Log.d(TAG, "Silence timeout watchdog expired (7s)")
                    stopListening()
                    listener?.onSilenceTimeout()
                }
            }
            mainHandler.postDelayed(silenceWatchdogRunnable!!, SILENCE_TIMEOUT_MS)

            // Setup 10-second max duration cap
            maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
            maxDurationRunnable = Runnable {
                if (isListening) {
                    Log.d(TAG, "Max recording duration reached (10s)")
                    stopListening()
                }
            }
            mainHandler.postDelayed(maxDurationRunnable!!, MAX_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            listener?.onError("Failed to access microphone: ${e.localizedMessage}")
            listener?.onListeningStateChanged(false)
        }
    }

    /**
     * Stops listening and cleans up watchdog timers and SpeechService.
     */
    fun stopListening() {
        val now = System.currentTimeMillis()
        lastActionTimeMs = now

        cancelSilenceWatchdog()
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null

        if (!isListening) return
        isListening = false

        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            Log.d(TAG, "SpeechService stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeechService", e)
        }
        listener?.onListeningStateChanged(false)
    }

    private fun cancelSilenceWatchdog() {
        silenceWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceWatchdogRunnable = null
    }

    private fun parseHypothesis(jsonString: String?, key: String): String {
        if (jsonString.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(jsonString)
            json.optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Releases native memory when destroying the controller.
     */
    fun destroy() {
        stopListening()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying Vosk model", e)
        }
    }
}
