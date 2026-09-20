package com.example.pathmind

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pathmind.data.RouteRepository
import com.example.pathmind.model.DeviationState
import com.example.pathmind.model.NavigationSnapshot
import com.example.pathmind.model.NavigationState
import com.example.pathmind.model.Route
import com.example.pathmind.model.SegmentAction
import com.example.pathmind.model.SegmentDirection
import com.example.pathmind.sensor.NavigationEngine
import com.google.android.material.button.MaterialButton
import java.util.Locale

class NavigationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROUTE_ID = "extra_route_id"
        const val EXTRA_TARGET_SEGMENT_ID = "extra_target_segment_id"
        const val EXTRA_TARGET_NAME = "extra_target_name"
    }

    private lateinit var routeRepository: RouteRepository
    private lateinit var navigationEngine: NavigationEngine

    private var currentRoute: Route? = null
    private var targetSegmentId: String? = null
    private var targetName: String? = null
    private var currentNavigationState: NavigationState = NavigationState.IDLE

    // UI elements
    private lateinit var tvNavTitle: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var ivStatusDot: ImageView
    private lateinit var tvNavTargetName: TextView
    private lateinit var tvNavSubTarget: TextView
    private lateinit var cardDeviationBanner: CardView
    private lateinit var ivDeviationDot: ImageView
    private lateinit var tvDeviationBadge: TextView
    private lateinit var tvDeviationDetails: TextView
    private lateinit var cardInstruction: CardView
    private lateinit var ivNavIcon: ImageView
    private lateinit var tvNavInstruction: TextView
    private lateinit var tvRecoveryCallout: TextView
    private lateinit var tvNavSegmentSubtitle: TextView
    private lateinit var tvNavTotalSteps: TextView
    private lateinit var tvNavSegmentSteps: TextView
    private lateinit var tvNavConfidence: TextView

    // Stage 7: AI Model Inspector UI Elements
    private lateinit var tvAILatency: TextView
    private lateinit var tvAIEntropy: TextView
    private lateinit var pbProbOnRoute: ProgressBar
    private lateinit var tvProbOnRoute: TextView
    private lateinit var pbProbDrift: ProgressBar
    private lateinit var tvProbDrift: TextView
    private lateinit var pbProbDeviation: ProgressBar
    private lateinit var tvProbDeviation: TextView
    private lateinit var pbProbRecovering: ProgressBar
    private lateinit var tvProbRecovering: TextView
    private lateinit var pbProbUncertain: ProgressBar
    private lateinit var tvProbUncertain: TextView
    private lateinit var tvAIFeatureSummary: TextView

    private lateinit var btnManualAdvance: MaterialButton
    private lateinit var btnTestWrongTurn: MaterialButton
    private lateinit var btnTestRecovery: MaterialButton
    private lateinit var btnTestNoise: MaterialButton
    private lateinit var btnTestResetRoute: MaterialButton
    private lateinit var btnStartStopNav: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_navigation)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.navigationRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Keep screen on while navigating
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        routeRepository = RouteRepository(this)
        navigationEngine = NavigationEngine(this)

        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
        targetSegmentId = intent.getStringExtra(EXTRA_TARGET_SEGMENT_ID)
        targetName = intent.getStringExtra(EXTRA_TARGET_NAME)

        if (routeId != null) {
            currentRoute = routeRepository.getRouteById(routeId)
        }

        if (currentRoute == null) {
            Toast.makeText(this, "Route not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupEngine()
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        tvNavTitle = findViewById(R.id.tvNavTitle)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        ivStatusDot = findViewById(R.id.ivStatusDot)
        tvNavTargetName = findViewById(R.id.tvNavTargetName)
        tvNavSubTarget = findViewById(R.id.tvNavSubTarget)
        cardDeviationBanner = findViewById(R.id.cardDeviationBanner)
        ivDeviationDot = findViewById(R.id.ivDeviationDot)
        tvDeviationBadge = findViewById(R.id.tvDeviationBadge)
        tvDeviationDetails = findViewById(R.id.tvDeviationDetails)
        cardInstruction = findViewById(R.id.cardInstruction)
        ivNavIcon = findViewById(R.id.ivNavIcon)
        tvNavInstruction = findViewById(R.id.tvNavInstruction)
        tvRecoveryCallout = findViewById(R.id.tvRecoveryCallout)
        tvNavSegmentSubtitle = findViewById(R.id.tvNavSegmentSubtitle)
        tvNavTotalSteps = findViewById(R.id.tvNavTotalSteps)
        tvNavSegmentSteps = findViewById(R.id.tvNavSegmentSteps)
        tvNavConfidence = findViewById(R.id.tvNavConfidence)

        // Stage 7 AI Inspector Views
        tvAILatency = findViewById(R.id.tvAILatency)
        tvAIEntropy = findViewById(R.id.tvAIEntropy)
        pbProbOnRoute = findViewById(R.id.pbProbOnRoute)
        tvProbOnRoute = findViewById(R.id.tvProbOnRoute)
        pbProbDrift = findViewById(R.id.pbProbDrift)
        tvProbDrift = findViewById(R.id.tvProbDrift)
        pbProbDeviation = findViewById(R.id.pbProbDeviation)
        tvProbDeviation = findViewById(R.id.tvProbDeviation)
        pbProbRecovering = findViewById(R.id.pbProbRecovering)
        tvProbRecovering = findViewById(R.id.tvProbRecovering)
        pbProbUncertain = findViewById(R.id.pbProbUncertain)
        tvProbUncertain = findViewById(R.id.tvProbUncertain)
        tvAIFeatureSummary = findViewById(R.id.tvAIFeatureSummary)

        btnManualAdvance = findViewById(R.id.btnManualAdvance)
        btnTestWrongTurn = findViewById(R.id.btnTestWrongTurn)
        btnTestRecovery = findViewById(R.id.btnTestRecovery)
        btnTestNoise = findViewById(R.id.btnTestNoise)
        btnTestResetRoute = findViewById(R.id.btnTestResetRoute)
        btnStartStopNav = findViewById(R.id.btnStartStopNav)

        val route = currentRoute!!
        if (!targetName.isNullOrBlank()) {
            tvNavTargetName.text = "Destination: $targetName"
            tvNavSubTarget.text = "Anchored along '${route.name}' • Personal Spatial Memory"
        } else {
            tvNavTargetName.text = "Route: ${route.name}"
            tvNavSubTarget.text = "Full Route (${route.totalSegments} segments, ${route.totalSteps} steps)"
        }

        btnStartStopNav.setOnClickListener {
            if (navigationEngine.isNavigating()) {
                navigationEngine.stopNavigation()
                finish()
            } else if (currentNavigationState == NavigationState.ARRIVED) {
                finish()
            } else {
                val started = navigationEngine.startNavigation()
                if (started) {
                    btnStartStopNav.text = "STOP NAVIGATION"
                    btnStartStopNav.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_turning)
                    btnStartStopNav.setIconResource(R.drawable.ic_close)
                }
            }
        }

        // Test Control Listeners
        btnManualAdvance.setOnClickListener {
            if (navigationEngine.isNavigating()) {
                navigationEngine.manualAdvanceSegment()
            } else {
                Toast.makeText(this, "Start navigation first", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestWrongTurn.setOnClickListener {
            if (navigationEngine.isNavigating()) {
                navigationEngine.simulateWrongTurn()
            } else {
                Toast.makeText(this, "Start navigation first", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestRecovery.setOnClickListener {
            if (navigationEngine.isNavigating()) {
                navigationEngine.simulateRecovery()
            } else {
                Toast.makeText(this, "Start navigation first", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestNoise.setOnClickListener {
            if (navigationEngine.isNavigating()) {
                navigationEngine.simulateAmbiguousNoise()
            } else {
                Toast.makeText(this, "Start navigation first", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestResetRoute.setOnClickListener {
            if (navigationEngine.isNavigating()) {
                navigationEngine.resetToOnRoute()
            } else {
                Toast.makeText(this, "Start navigation first", Toast.LENGTH_SHORT).show()
            }
        }

        val layoutTestControls = findViewById<View>(R.id.layoutTestControls)
        tvNavTitle.setOnLongClickListener {
            val isVis = layoutTestControls.visibility == View.VISIBLE
            layoutTestControls.visibility = if (isVis) View.GONE else View.VISIBLE
            Toast.makeText(this, if (!isVis) "Dev test controls shown" else "Dev test controls hidden", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setupEngine() {
        navigationEngine.onSnapshotUpdated = { snapshot ->
            runOnUiThread {
                updateUI(snapshot)
            }
        }

        navigationEngine.loadRoute(
            route = currentRoute!!,
            targetSegmentId = targetSegmentId,
            targetName = targetName
        )
    }

    private fun updateUI(snapshot: NavigationSnapshot) {
        currentNavigationState = snapshot.state

        // 1. Navigation State Badge
        when (snapshot.state) {
            NavigationState.IDLE -> {
                tvStatusBadge.text = "READY"
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.accent_cyan))
            }
            NavigationState.STARTING -> {
                tvStatusBadge.text = "STARTING"
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.accent_blue))
            }
            NavigationState.NAVIGATING -> {
                tvStatusBadge.text = "NAVIGATING"
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.indicator_ready))
                ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.indicator_ready))
            }
            NavigationState.TURN_REQUIRED -> {
                tvStatusBadge.text = "TURN REQUIRED"
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.state_turning))
                ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.state_turning))
            }
            NavigationState.UNCERTAIN -> {
                tvStatusBadge.text = "UNCERTAIN"
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.dev_uncertain))
                ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.dev_uncertain))
            }
            NavigationState.ARRIVED -> {
                tvStatusBadge.text = "ARRIVED"
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.indicator_ready))
                ivStatusDot.setColorFilter(ContextCompat.getColor(this, R.color.indicator_ready))
            }
        }

        // 2. Stage 6: Deviation Status Banner
        when (snapshot.deviationState) {
            DeviationState.ON_ROUTE -> {
                cardDeviationBanner.setCardBackgroundColor(Color.parseColor("#13231E"))
                tvDeviationBadge.text = "ON ROUTE"
                tvDeviationBadge.setTextColor(ContextCompat.getColor(this, R.color.dev_on_route))
                ivDeviationDot.setColorFilter(ContextCompat.getColor(this, R.color.dev_on_route))
                tvDeviationDetails.text = "Movement matches learned route"
                tvRecoveryCallout.visibility = View.GONE
            }
            DeviationState.POSSIBLE_DEVIATION -> {
                cardDeviationBanner.setCardBackgroundColor(Color.parseColor("#2D2115"))
                tvDeviationBadge.text = "POSSIBLE DEVIATION"
                tvDeviationBadge.setTextColor(ContextCompat.getColor(this, R.color.dev_possible))
                ivDeviationDot.setColorFilter(ContextCompat.getColor(this, R.color.dev_possible))
                tvDeviationDetails.text = "Unexpected turn or movement detected"
                tvRecoveryCallout.visibility = View.VISIBLE
                tvRecoveryCallout.text = "POSSIBLE DEVIATION • VERIFYING..."
                tvRecoveryCallout.setTextColor(ContextCompat.getColor(this, R.color.dev_possible))
            }
            DeviationState.DEVIATION_CONFIRMED -> {
                cardDeviationBanner.setCardBackgroundColor(Color.parseColor("#3D1418"))
                tvDeviationBadge.text = "DEVIATION DETECTED"
                tvDeviationBadge.setTextColor(ContextCompat.getColor(this, R.color.dev_confirmed))
                ivDeviationDot.setColorFilter(ContextCompat.getColor(this, R.color.dev_confirmed))
                tvDeviationDetails.text = "User diverged from known route"
                tvRecoveryCallout.visibility = View.VISIBLE
                tvRecoveryCallout.text = snapshot.recoveryInstruction ?: "TURN BACK • RETURN TO LAST KNOWN POINT"
                tvRecoveryCallout.setTextColor(ContextCompat.getColor(this, R.color.dev_confirmed))
            }
            DeviationState.RECOVERING -> {
                cardDeviationBanner.setCardBackgroundColor(Color.parseColor("#102B3F"))
                tvDeviationBadge.text = "RECOVERING..."
                tvDeviationBadge.setTextColor(ContextCompat.getColor(this, R.color.dev_recovering))
                ivDeviationDot.setColorFilter(ContextCompat.getColor(this, R.color.dev_recovering))
                tvDeviationDetails.text = "Moving back toward last confirmed segment"
                tvRecoveryCallout.visibility = View.VISIBLE
                tvRecoveryCallout.text = snapshot.recoveryInstruction ?: "RETURN TO LAST KNOWN POINT"
                tvRecoveryCallout.setTextColor(ContextCompat.getColor(this, R.color.dev_recovering))
            }
            DeviationState.RECOVERED -> {
                cardDeviationBanner.setCardBackgroundColor(Color.parseColor("#15372C"))
                tvDeviationBadge.text = "ROUTE RECOVERED"
                tvDeviationBadge.setTextColor(ContextCompat.getColor(this, R.color.dev_recovered))
                ivDeviationDot.setColorFilter(ContextCompat.getColor(this, R.color.dev_recovered))
                tvDeviationDetails.text = "Back on known route • Navigation resuming"
                tvRecoveryCallout.visibility = View.VISIBLE
                tvRecoveryCallout.text = "ROUTE RECOVERED • FOLLOW KNOWN ROUTE"
                tvRecoveryCallout.setTextColor(ContextCompat.getColor(this, R.color.dev_recovered))
            }
            DeviationState.UNCERTAIN -> {
                cardDeviationBanner.setCardBackgroundColor(Color.parseColor("#371A1A"))
                tvDeviationBadge.text = "UNCERTAIN"
                tvDeviationBadge.setTextColor(ContextCompat.getColor(this, R.color.dev_uncertain))
                ivDeviationDot.setColorFilter(ContextCompat.getColor(this, R.color.dev_uncertain))
                tvDeviationDetails.text = "Ambiguous sensor evidence • Move carefully"
                tvRecoveryCallout.visibility = View.VISIBLE
                tvRecoveryCallout.text = "UNCERTAIN — MOVE CAREFULLY"
                tvRecoveryCallout.setTextColor(ContextCompat.getColor(this, R.color.dev_uncertain))
            }
        }

        // 3. Primary Instruction & Guidance Card Styling
        tvNavInstruction.text = snapshot.instruction
        val curSeg = snapshot.currentSegment

        when {
            snapshot.state == NavigationState.ARRIVED -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#15372C"))
                ivNavIcon.setImageResource(R.drawable.ic_destination_flag)
                ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.indicator_ready))
                btnStartStopNav.text = "EXIT NAVIGATION"
                btnStartStopNav.backgroundTintList = ContextCompat.getColorStateList(this, R.color.indicator_ready)
                btnStartStopNav.setIconResource(R.drawable.ic_destination_flag)
                btnStartStopNav.isEnabled = true
            }
            snapshot.deviationState == DeviationState.DEVIATION_CONFIRMED -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#3D1418"))
                ivNavIcon.setImageResource(R.drawable.ic_arrow_back)
                ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.dev_confirmed))
            }
            snapshot.deviationState == DeviationState.RECOVERING -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#102B3F"))
                ivNavIcon.setImageResource(R.drawable.ic_route)
                ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.dev_recovering))
            }
            snapshot.deviationState == DeviationState.RECOVERED -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#15372C"))
                ivNavIcon.setImageResource(R.drawable.ic_destination_flag)
                ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.indicator_ready))
            }
            snapshot.deviationState == DeviationState.POSSIBLE_DEVIATION -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#2E2012"))
                ivNavIcon.setImageResource(R.drawable.ic_dot_active)
                ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.dev_possible))
            }
            snapshot.state == NavigationState.UNCERTAIN || snapshot.deviationState == DeviationState.UNCERTAIN -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#371A1A"))
                ivNavIcon.setImageResource(R.drawable.ic_dot_active)
                ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.dev_uncertain))
            }
            snapshot.state == NavigationState.TURN_REQUIRED -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#2E2012"))
                val isLeft = curSeg?.direction == SegmentDirection.LEFT
                ivNavIcon.setImageResource(if (isLeft) R.drawable.ic_turn_left else R.drawable.ic_turn_right)
                ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.state_turning))
            }
            else -> {
                cardInstruction.setCardBackgroundColor(Color.parseColor("#1A2436"))
                if (curSeg?.action == SegmentAction.START) {
                    ivNavIcon.setImageResource(R.drawable.ic_start_flag)
                    ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.indicator_ready))
                } else {
                    ivNavIcon.setImageResource(R.drawable.ic_walk_steps)
                    ivNavIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_cyan))
                }
            }
        }

        // 4. Subtitle: Segment X of Y
        val segNum = snapshot.currentSegmentIndex + 1
        val actionName = curSeg?.action?.name ?: "COMPLETED"
        val dirName = if (curSeg?.direction != SegmentDirection.NONE && curSeg?.direction != SegmentDirection.STRAIGHT) {
            " (${curSeg?.direction?.name})"
        } else ""
        tvNavSegmentSubtitle.text = "Segment $segNum of ${snapshot.totalSegments} • $actionName$dirName"

        // 5. Live Metrics
        tvNavTotalSteps.text = snapshot.totalStepsObserved.toString()
        tvNavSegmentSteps.text = "${snapshot.stepsInCurrentSegment} / ${snapshot.expectedStepsInSegment}"
        tvNavConfidence.text = "${snapshot.confidence}%"

        // 6. Stage 7: On-Device AI Model Inspector
        val ml = snapshot.mlOutput
        if (ml != null) {
            tvAILatency.text = String.format(Locale.US, "%.1f ms", ml.inferenceLatencyMs)
            tvAIEntropy.text = String.format(Locale.US, "Entropy: %.2f", ml.normalizedEntropy)

            val p = ml.probabilities
            val p0 = (p[0] * 100f).toInt().coerceIn(0, 100)
            val p1 = (p[1] * 100f).toInt().coerceIn(0, 100)
            val p2 = (p[2] * 100f).toInt().coerceIn(0, 100)
            val p3 = (p[3] * 100f).toInt().coerceIn(0, 100)
            val p4 = (p[4] * 100f).toInt().coerceIn(0, 100)

            pbProbOnRoute.progress = p0
            tvProbOnRoute.text = "$p0%"

            pbProbDrift.progress = p1
            tvProbDrift.text = "$p1%"

            pbProbDeviation.progress = p2
            tvProbDeviation.text = "$p2%"

            pbProbRecovering.progress = p3
            tvProbRecovering.text = "$p3%"

            pbProbUncertain.progress = p4
            tvProbUncertain.text = "$p4%"

            val f = ml.rawFeatures
            if (f.size >= 18) {
                tvAIFeatureSummary.text = String.format(
                    Locale.US,
                    "Cadence: %.1f Hz | Var: %.2f | Yaw: %.1f° | Disp: %.1f°",
                    f[0], f[1], f[3], f[16]
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        navigationEngine.stopNavigation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
