package com.example.pathmind.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.pathmind.MyMemoriesActivity
import com.example.pathmind.R
import com.example.pathmind.data.MemoryRepository
import com.example.pathmind.model.VoiceAction
import com.example.pathmind.model.VoiceExecutionResult
import com.example.pathmind.voice.OfflineSpeechRecognizer
import com.example.pathmind.voice.VoiceCommandOrchestrator
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.util.Locale

/**
 * Stage 8C / Stage 10: Hardened Voice & Spatial Intent Assistant UI.
 * Hero microphone centerpiece, state machine transitions, silence watchdog, auto-dismiss, and offline resilience.
 */
class VoiceCommandBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "PathMindVoiceUX"
    }

    private lateinit var orchestrator: VoiceCommandOrchestrator
    private var speechRecognizer: OfflineSpeechRecognizer? = null

    // UI elements
    private lateinit var tvOfflineBadge: TextView
    private lateinit var viewRippleRing: View
    private lateinit var btnHeroMic: MaterialButton
    private lateinit var tvVoiceStatePrompt: TextView
    private lateinit var tvVoiceStateSubPrompt: TextView

    private lateinit var cardLiveTranscript: CardView
    private lateinit var tvLiveTranscript: TextView
    private lateinit var tvLiveLatencyBadge: TextView

    private lateinit var cardOutcome: CardView
    private lateinit var tvOutcomeBadge: TextView
    private lateinit var tvOutcomeEntity: TextView
    private lateinit var tvOutcomeConfidence: TextView
    private lateinit var tvOutcomeMessage: TextView
    private lateinit var btnLaunchOutcomeAction: MaterialButton
    private lateinit var btnAttachLandmarkPhoto: MaterialButton

    // Stage 10 (Step 4): Disambiguation views
    private lateinit var layoutCandidatesContainer: LinearLayout
    private lateinit var layoutCandidateButtons: LinearLayout
    private lateinit var btnCancelSelection: MaterialButton

    private lateinit var layoutToggleManualInput: View
    private lateinit var tvToggleManualText: TextView
    private lateinit var layoutManualInputContainer: View
    private lateinit var etCommandInput: EditText
    private lateinit var btnExecuteCommand: MaterialButton

    // Stage 9: Landmark photo capture state
    private var tempCaptureFile: java.io.File? = null
    private var lastSavedMemoryId: String? = null

    private val takeLandmarkPictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val tempFile = tempCaptureFile
        val memoryId = lastSavedMemoryId
        if (success && tempFile != null && tempFile.exists() && tempFile.length() > 0 && memoryId != null) {
            val memoryRepo = MemoryRepository(requireContext())
            val memory = memoryRepo.getMemoryById(memoryId)
            if (memory != null) {
                val savedPath = com.example.pathmind.util.LandmarkImageManager.processAndSaveLandmark(
                    requireContext(),
                    tempFile,
                    memory.id
                )
                if (savedPath != null) {
                    memoryRepo.updateMemory(memory.copy(photoPath = savedPath))
                    btnAttachLandmarkPhoto.text = "✓ LANDMARK PHOTO ATTACHED"
                    btnAttachLandmarkPhoto.isEnabled = false
                    btnAttachLandmarkPhoto.setTextColor(ContextCompat.getColor(requireContext(), R.color.indicator_ready))
                    tvOutcomeMessage.text = "${tvOutcomeMessage.text}\n✓ Photo attached to '${memory.name}'!"
                } else {
                    android.widget.Toast.makeText(requireContext(), "Failed to save photo", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Chips
    private lateinit var chipTryFindBike: Chip
    private lateinit var chipTryRememberBike: Chip
    private lateinit var chipTryStartRoute: Chip
    private lateinit var chipTryCancel: Chip

    private var rippleAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isManualDrawerExpanded = false

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Audio permission granted, starting listening")
            startListeningWithGrammar()
        } else {
            Log.w(TAG, "Audio permission denied by user")
            showPermissionDeniedState()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_voice_command, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        orchestrator = VoiceCommandOrchestrator(requireContext())
        initViews(view)
        setupTrySayingChips()
        setupManualInputToggle()
        setupSpeechRecognizer()
    }

    private fun initViews(view: View) {
        tvOfflineBadge = view.findViewById(R.id.tvOfflineBadge)
        viewRippleRing = view.findViewById(R.id.viewRippleRing)
        btnHeroMic = view.findViewById(R.id.btnHeroMic)
        tvVoiceStatePrompt = view.findViewById(R.id.tvVoiceStatePrompt)
        tvVoiceStateSubPrompt = view.findViewById(R.id.tvVoiceStateSubPrompt)

        cardLiveTranscript = view.findViewById(R.id.cardLiveTranscript)
        tvLiveTranscript = view.findViewById(R.id.tvLiveTranscript)
        tvLiveLatencyBadge = view.findViewById(R.id.tvLiveLatencyBadge)

        cardOutcome = view.findViewById(R.id.cardOutcome)
        tvOutcomeBadge = view.findViewById(R.id.tvOutcomeBadge)
        tvOutcomeEntity = view.findViewById(R.id.tvOutcomeEntity)
        tvOutcomeConfidence = view.findViewById(R.id.tvOutcomeConfidence)
        tvOutcomeMessage = view.findViewById(R.id.tvOutcomeMessage)
        btnLaunchOutcomeAction = view.findViewById(R.id.btnLaunchOutcomeAction)
        btnAttachLandmarkPhoto = view.findViewById(R.id.btnAttachLandmarkPhoto)

        // Stage 10 (Step 4): Disambiguation views
        layoutCandidatesContainer = view.findViewById(R.id.layoutCandidatesContainer)
        layoutCandidateButtons = view.findViewById(R.id.layoutCandidateButtons)
        btnCancelSelection = view.findViewById(R.id.btnCancelSelection)

        btnCancelSelection.setOnClickListener {
            hideDisambiguationState()
            setReadyState()
            tvOutcomeBadge.text = "READY"
            tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_cyan))
            tvOutcomeMessage.text = "Selection cancelled. Tap microphone to speak."
        }

        btnAttachLandmarkPhoto.setOnClickListener {
            try {
                val (uri, file) = com.example.pathmind.util.LandmarkImageManager.createTempCaptureUri(requireContext())
                tempCaptureFile = file
                takeLandmarkPictureLauncher.launch(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching camera for landmark", e)
                android.widget.Toast.makeText(requireContext(), "Unable to launch camera: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        layoutToggleManualInput = view.findViewById(R.id.layoutToggleManualInput)
        tvToggleManualText = view.findViewById(R.id.tvToggleManualText)
        layoutManualInputContainer = view.findViewById(R.id.layoutManualInputContainer)
        etCommandInput = view.findViewById(R.id.etCommandInput)
        btnExecuteCommand = view.findViewById(R.id.btnExecuteCommand)

        chipTryFindBike = view.findViewById(R.id.chipTryFindBike)
        chipTryRememberBike = view.findViewById(R.id.chipTryRememberBike)
        chipTryStartRoute = view.findViewById(R.id.chipTryStartRoute)
        chipTryCancel = view.findViewById(R.id.chipTryCancel)

        btnHeroMic.setOnClickListener {
            handleMicTap()
        }

        btnExecuteCommand.setOnClickListener {
            val input = etCommandInput.text.toString().trim()
            if (input.isNotEmpty()) {
                executeUtterance(input, 0)
            }
        }
    }

    private fun setupSpeechRecognizer() {
        val recognizer = OfflineSpeechRecognizer(requireContext())
        speechRecognizer = recognizer

        recognizer.setListener(object : OfflineSpeechRecognizer.Listener {
            override fun onModelLoading(status: String) {
                if (!isAdded) return
                tvVoiceStatePrompt.text = "Preparing offline voice..."
                tvVoiceStateSubPrompt.text = "Unpacking Vosk engine"
            }

            override fun onModelReady(loadDurationMs: Long) {
                if (!isAdded) return
                setReadyState()
            }

            override fun onListeningStateChanged(isListening: Boolean) {
                if (!isAdded) return
                if (isListening) {
                    setListeningState()
                } else {
                    stopRippleAnimation()
                }
            }

            override fun onPartialResult(hypothesis: String) {
                if (!isAdded) return
                cardLiveTranscript.visibility = View.VISIBLE
                tvLiveTranscript.text = "\"$hypothesis\""
                tvLiveLatencyBadge.visibility = View.GONE
            }

            override fun onFinalResult(hypothesis: String, recognitionLatencyMs: Long) {
                if (!isAdded) return
                Log.d(TAG, "ASR Result: \"$hypothesis\" in ${recognitionLatencyMs}ms")
                cardLiveTranscript.visibility = View.VISIBLE
                tvLiveTranscript.text = "\"$hypothesis\""
                tvLiveLatencyBadge.visibility = View.VISIBLE
                tvLiveLatencyBadge.text = "⚡ ${recognitionLatencyMs}ms"

                executeUtterance(hypothesis, recognitionLatencyMs)
            }

            override fun onSilenceTimeout() {
                if (!isAdded) return
                showSilenceTimeoutState()
            }

            override fun onError(errorMessage: String) {
                if (!isAdded) return
                showErrorState(errorMessage)
            }
        })

        // Preload in background
        recognizer.initialize(viewLifecycleOwner.lifecycleScope)
    }

    private fun handleMicTap() {
        val recognizer = speechRecognizer ?: return

        if (recognizer.isCurrentlyListening) {
            recognizer.stopListening()
            setReadyState()
            return
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListeningWithGrammar()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListeningWithGrammar() {
        val recognizer = speechRecognizer ?: return

        val savedPlaceNames = MemoryRepository(requireContext()).getAllMemories().map { it.name }
        val grammar = recognizer.buildGrammar(savedPlaceNames)

        recognizer.startListening(grammar)
    }

    private fun setReadyState() {
        stopRippleAnimation()
        hideDisambiguationState()
        btnHeroMic.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.surface_card)
        )
        btnHeroMic.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.accent_cyan)
        )
        tvVoiceStatePrompt.text = "Tap microphone to speak"
        tvVoiceStateSubPrompt.text = "Vosk On-Device Engine • Zero Cloud"
    }

    private fun setListeningState() {
        startRippleAnimation()
        btnHeroMic.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.dev_confirmed)
        )
        btnHeroMic.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.white)
        )
        tvVoiceStatePrompt.text = "Listening... speak now"
        tvVoiceStateSubPrompt.text = "Audio processed locally in memory"

        cardLiveTranscript.visibility = View.VISIBLE
        tvLiveTranscript.text = "Listening..."
        tvLiveLatencyBadge.visibility = View.GONE
    }

    private fun startRippleAnimation() {
        viewRippleRing.visibility = View.VISIBLE
        if (rippleAnimator == null) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.25f, 1.0f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.25f, 1.0f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 1.0f, 0.4f)
            rippleAnimator = ObjectAnimator.ofPropertyValuesHolder(viewRippleRing, scaleX, scaleY, alpha).apply {
                duration = 1200L
                repeatCount = ObjectAnimator.INFINITE
            }
        }
        rippleAnimator?.start()
    }

    private fun stopRippleAnimation() {
        rippleAnimator?.cancel()
        viewRippleRing.visibility = View.INVISIBLE
    }

    private fun executeUtterance(utterance: String, latencyMs: Long) {
        setReadyState()

        val result = orchestrator.processCommand(utterance)
        displayOutcome(result, latencyMs)
    }

    private fun displayOutcome(result: VoiceExecutionResult, latencyMs: Long) {
        val cmd = result.commandResult

        // Entity Pill
        val entity = cmd.targetEntity
        if (!entity.isNullOrBlank()) {
            tvOutcomeEntity.visibility = View.VISIBLE
            tvOutcomeEntity.text = "Target: \"$entity\""
        } else {
            tvOutcomeEntity.visibility = View.GONE
        }

        // Confidence
        if (cmd.confidence > 0f) {
            tvOutcomeConfidence.visibility = View.VISIBLE
            tvOutcomeConfidence.text = "Conf: ${(cmd.confidence * 100).toInt()}%"
        } else {
            tvOutcomeConfidence.visibility = View.GONE
        }

        tvOutcomeMessage.text = result.displayMessage
        btnAttachLandmarkPhoto.visibility = View.GONE
        hideDisambiguationState()

        when (cmd.action) {
            VoiceAction.REMEMBER_PLACE -> {
                tvOutcomeBadge.text = "📍 PLACE SAVED"
                tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.indicator_ready))
                btnLaunchOutcomeAction.visibility = View.VISIBLE
                btnLaunchOutcomeAction.text = "VIEW IN MY MEMORIES"
                btnLaunchOutcomeAction.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
                btnLaunchOutcomeAction.setOnClickListener {
                    startActivity(Intent(requireContext(), MyMemoriesActivity::class.java))
                    dismiss()
                }

                if (result.success) {
                    val memoryRepo = MemoryRepository(requireContext())
                    val latestMemory = memoryRepo.getAllMemories().maxByOrNull { it.createdAt }
                    lastSavedMemoryId = latestMemory?.id
                    if (latestMemory != null) {
                        btnAttachLandmarkPhoto.visibility = View.VISIBLE
                        btnAttachLandmarkPhoto.isEnabled = true
                        btnAttachLandmarkPhoto.text = "📷 ADD LANDMARK PHOTO"
                        btnAttachLandmarkPhoto.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_cyan))
                    }
                }
            }
            VoiceAction.FIND_PLACE -> {
                if (result.requiresSelection && result.candidateMemories.isNotEmpty()) {
                    // Stage 10 (Step 4): Disambiguation candidate choice
                    showDisambiguationState(result)
                } else if (result.success && result.navigationIntent != null) {
                    tvOutcomeBadge.text = "🧭 MEMORY FOUND"
                    tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_cyan))
                    val target = cmd.targetEntity?.uppercase(Locale.US) ?: "DESTINATION"
                    btnLaunchOutcomeAction.visibility = View.VISIBLE
                    btnLaunchOutcomeAction.text = "START NAVIGATION TO $target"
                    btnLaunchOutcomeAction.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.indicator_ready))
                    btnLaunchOutcomeAction.setOnClickListener {
                        startActivity(result.navigationIntent)
                        dismiss()
                    }
                } else {
                    // Unknown place in memory
                    tvOutcomeBadge.text = "⚠️ PLACE NOT FOUND"
                    tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.dev_possible))
                    btnLaunchOutcomeAction.visibility = View.GONE
                }
            }
            VoiceAction.START_ROUTE -> {
                tvOutcomeBadge.text = "🚀 ROUTE READY"
                tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
                btnLaunchOutcomeAction.visibility = View.VISIBLE
                btnLaunchOutcomeAction.text = "START ROUTE NAVIGATION"
                btnLaunchOutcomeAction.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.indicator_ready))
                btnLaunchOutcomeAction.setOnClickListener {
                    if (result.navigationIntent != null) {
                        startActivity(result.navigationIntent)
                    }
                    dismiss()
                }
            }
            VoiceAction.CANCEL -> {
                tvOutcomeBadge.text = "✕ CANCELLED"
                tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
                btnLaunchOutcomeAction.visibility = View.GONE

                // Smooth auto-dismiss after 1.2 seconds
                mainHandler.postDelayed({
                    if (isAdded) {
                        dismiss()
                    }
                }, 1200L)
            }
            VoiceAction.UNKNOWN -> {
                tvOutcomeBadge.text = "❓ UNCLEAR COMMAND"
                tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.dev_confirmed))
                btnLaunchOutcomeAction.visibility = View.GONE
            }
        }
    }

    private fun showSilenceTimeoutState() {
        setReadyState()
        btnAttachLandmarkPhoto.visibility = View.GONE
        tvOutcomeBadge.text = "⏱ NO SPEECH DETECTED"
        tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.dev_possible))
        tvOutcomeEntity.visibility = View.GONE
        tvOutcomeConfidence.visibility = View.GONE
        tvOutcomeMessage.text = "No speech detected. Tap the microphone and speak your command clearly."
        btnLaunchOutcomeAction.visibility = View.GONE
    }

    private fun showErrorState(message: String) {
        setReadyState()
        btnAttachLandmarkPhoto.visibility = View.GONE
        tvOutcomeBadge.text = "⚠️ SPEECH NOTICE"
        tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.dev_confirmed))
        tvOutcomeEntity.visibility = View.GONE
        tvOutcomeConfidence.visibility = View.GONE
        tvOutcomeMessage.text = message
        btnLaunchOutcomeAction.visibility = View.GONE
    }

    private fun showPermissionDeniedState() {
        setReadyState()
        btnAttachLandmarkPhoto.visibility = View.GONE
        tvOutcomeBadge.text = "🔒 MIC PERMISSION REQUIRED"
        tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.dev_confirmed))
        tvOutcomeEntity.visibility = View.GONE
        tvOutcomeConfidence.visibility = View.GONE
        tvOutcomeMessage.text = "Microphone access denied. You can type commands below, or grant audio in phone Settings."
        btnLaunchOutcomeAction.visibility = View.GONE

        // Automatically expand manual input drawer as fallback
        expandManualInputDrawer()
    }

    private fun showDisambiguationState(result: VoiceExecutionResult) {
        tvOutcomeBadge.text = "🔍 CHOOSE DESTINATION"
        tvOutcomeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_cyan))
        val target = result.commandResult.targetEntity ?: ""
        tvOutcomeMessage.text = "Multiple places match \"$target\". Tap your intended destination:"
        btnLaunchOutcomeAction.visibility = View.GONE
        btnAttachLandmarkPhoto.visibility = View.GONE

        layoutCandidatesContainer.visibility = View.VISIBLE
        layoutCandidateButtons.removeAllViews()

        val routeRepo = com.example.pathmind.data.RouteRepository(requireContext())
        val routesMap = routeRepo.getRoutes().associateBy { it.id }

        for (candidate in result.candidateMemories) {
            val routeName = routesMap[candidate.routeId]?.name ?: "Learned Route"

            val btnCandidate = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.surface_dark)
                )
                strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.surface_card_stroke)
                )
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                cornerRadius = (10 * resources.displayMetrics.density).toInt()
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                textSize = 13f

                text = "🧭 ${candidate.name}\n   Along '$routeName'"

                setOnClickListener {
                    val navIntent = Intent(requireContext(), com.example.pathmind.NavigationActivity::class.java).apply {
                        putExtra(com.example.pathmind.NavigationActivity.EXTRA_ROUTE_ID, candidate.routeId)
                        putExtra(com.example.pathmind.NavigationActivity.EXTRA_TARGET_SEGMENT_ID, candidate.segmentId)
                        putExtra(com.example.pathmind.NavigationActivity.EXTRA_TARGET_NAME, candidate.name)
                    }
                    startActivity(navIntent)
                    dismiss()
                }
            }
            layoutCandidateButtons.addView(btnCandidate)
        }
    }

    private fun hideDisambiguationState() {
        if (::layoutCandidatesContainer.isInitialized) {
            layoutCandidatesContainer.visibility = View.GONE
        }
        if (::layoutCandidateButtons.isInitialized) {
            layoutCandidateButtons.removeAllViews()
        }
    }

    private fun setupTrySayingChips() {
        chipTryFindBike.setOnClickListener {
            executeChipCommand("Where did I park my bike?")
        }
        chipTryRememberBike.setOnClickListener {
            executeChipCommand("Remember where I parked my bike.")
        }
        chipTryStartRoute.setOnClickListener {
            executeChipCommand("Start my route.")
        }
        chipTryCancel.setOnClickListener {
            executeChipCommand("Cancel")
        }
    }

    private fun executeChipCommand(text: String) {
        cardLiveTranscript.visibility = View.VISIBLE
        tvLiveTranscript.text = "\"$text\""
        tvLiveLatencyBadge.visibility = View.GONE
        executeUtterance(text, 0)
    }

    private fun setupManualInputToggle() {
        layoutToggleManualInput.setOnClickListener {
            if (isManualDrawerExpanded) {
                collapseManualInputDrawer()
            } else {
                expandManualInputDrawer()
            }
        }
    }

    private fun expandManualInputDrawer() {
        isManualDrawerExpanded = true
        layoutManualInputContainer.visibility = View.VISIBLE
        tvToggleManualText.text = "▲ Hide text input"
        etCommandInput.requestFocus()
    }

    private fun collapseManualInputDrawer() {
        isManualDrawerExpanded = false
        layoutManualInputContainer.visibility = View.GONE
        tvToggleManualText.text = "⌨ Type a command instead"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)
        stopRippleAnimation()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
