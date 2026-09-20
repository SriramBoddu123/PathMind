package com.example.pathmind

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pathmind.data.RouteRepository
import com.example.pathmind.model.MovementEvent
import com.example.pathmind.model.MovementState
import com.example.pathmind.model.RouteSegment
import com.example.pathmind.model.TurnDirection
import com.example.pathmind.sensor.LearningStatus
import com.example.pathmind.sensor.RouteLearningEngine
import com.example.pathmind.ui.RouteSegmentAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RouteLearningActivity : AppCompatActivity() {

    private lateinit var engine: RouteLearningEngine
    private lateinit var repository: RouteRepository
    private lateinit var adapter: RouteSegmentAdapter

    private lateinit var tvLearningStatus: TextView
    private lateinit var ivStatusDot: ImageView
    private lateinit var tvCurrentMovement: TextView
    private lateinit var tvTotalSteps: TextView
    private lateinit var tvCurrentSegmentDesc: TextView
    private lateinit var tvLastTurn: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var rvSegments: RecyclerView
    private lateinit var btnStartPause: MaterialButton
    private lateinit var btnStopSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_route_learning)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.routeLearningRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = RouteRepository(this)
        engine = RouteLearningEngine(this)

        initViews()
        setupRecyclerView()
        setupEngineCallbacks()
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { handleBackPressed() }

        tvLearningStatus = findViewById(R.id.tvLearningStatus)
        ivStatusDot = findViewById(R.id.ivStatusDot)
        tvCurrentMovement = findViewById(R.id.tvCurrentMovement)
        tvTotalSteps = findViewById(R.id.tvTotalSteps)
        tvCurrentSegmentDesc = findViewById(R.id.tvCurrentSegmentDesc)
        tvLastTurn = findViewById(R.id.tvLastTurn)
        tvConfidence = findViewById(R.id.tvConfidence)
        rvSegments = findViewById(R.id.rvSegments)
        btnStartPause = findViewById(R.id.btnStartPause)
        btnStopSave = findViewById(R.id.btnStopSave)

        btnStartPause.setOnClickListener {
            when (engine.learningStatus) {
                LearningStatus.IDLE -> startLearning()
                LearningStatus.LEARNING -> pauseLearning()
                LearningStatus.PAUSED -> resumeLearning()
                LearningStatus.COMPLETED -> startLearning()
            }
        }

        btnStopSave.setOnClickListener {
            showSaveRouteDialog()
        }
    }

    private fun setupRecyclerView() {
        adapter = RouteSegmentAdapter()
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = false
        rvSegments.layoutManager = layoutManager
        rvSegments.adapter = adapter
    }

    private fun setupEngineCallbacks() {
        engine.onSegmentListChanged = { segments ->
            runOnUiThread {
                adapter.updateSegments(segments)
                if (segments.isNotEmpty()) {
                    rvSegments.smoothScrollToPosition(segments.size - 1)
                }
            }
        }

        engine.onTelemetryUpdated = { event, desc, totalSteps ->
            runOnUiThread {
                updateTelemetryUI(event, desc, totalSteps)
            }
        }
    }

    private fun startLearning() {
        engine.startLearning()
        updateStatusBadge(LearningStatus.LEARNING)

        btnStartPause.text = "PAUSE"
        btnStartPause.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
        btnStartPause.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        btnStartPause.setStrokeColorResource(R.color.surface_card_stroke)
        btnStartPause.strokeWidth = 2

        btnStopSave.isEnabled = true
        btnStopSave.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue)
        btnStopSave.setTextColor(ContextCompat.getColor(this, R.color.white))
        btnStopSave.strokeWidth = 0
    }

    private fun pauseLearning() {
        engine.pauseLearning()
        updateStatusBadge(LearningStatus.PAUSED)

        btnStartPause.text = "RESUME"
        btnStartPause.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue)
        btnStartPause.setTextColor(ContextCompat.getColor(this, R.color.white))
        btnStartPause.strokeWidth = 0
    }

    private fun resumeLearning() {
        engine.resumeLearning()
        updateStatusBadge(LearningStatus.LEARNING)

        btnStartPause.text = "PAUSE"
        btnStartPause.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
        btnStartPause.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        btnStartPause.setStrokeColorResource(R.color.surface_card_stroke)
        btnStartPause.strokeWidth = 2
    }

    private fun updateStatusBadge(status: LearningStatus) {
        when (status) {
            LearningStatus.LEARNING -> {
                tvLearningStatus.text = "LEARNING"
                tvLearningStatus.setTextColor(ContextCompat.getColor(this, R.color.indicator_ready))
                ivStatusDot.setImageResource(R.drawable.ic_dot_active)
            }
            LearningStatus.PAUSED -> {
                tvLearningStatus.text = "PAUSED"
                tvLearningStatus.setTextColor(ContextCompat.getColor(this, R.color.state_turning))
                ivStatusDot.setImageResource(R.drawable.ic_dot_active)
            }
            LearningStatus.IDLE -> {
                tvLearningStatus.text = "IDLE"
                tvLearningStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
            LearningStatus.COMPLETED -> {
                tvLearningStatus.text = "COMPLETED"
                tvLearningStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            }
        }
    }

    private fun updateTelemetryUI(event: MovementEvent, currentDesc: String, totalSteps: Int) {
        tvCurrentMovement.text = event.movementState.name
        when (event.movementState) {
            MovementState.WALKING -> tvCurrentMovement.setTextColor(ContextCompat.getColor(this, R.color.state_walking))
            MovementState.STATIONARY -> tvCurrentMovement.setTextColor(ContextCompat.getColor(this, R.color.state_stationary))
            MovementState.TURNING -> tvCurrentMovement.setTextColor(ContextCompat.getColor(this, R.color.state_turning))
            MovementState.UNKNOWN -> tvCurrentMovement.setTextColor(ContextCompat.getColor(this, R.color.state_unknown))
        }

        tvTotalSteps.text = totalSteps.toString()
        tvCurrentSegmentDesc.text = currentDesc

        tvLastTurn.text = event.turnDirection.name
        when (event.turnDirection) {
            TurnDirection.LEFT, TurnDirection.RIGHT -> tvLastTurn.setTextColor(ContextCompat.getColor(this, R.color.state_turning))
            TurnDirection.NONE -> tvLastTurn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            TurnDirection.UNKNOWN -> tvLastTurn.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }

        tvConfidence.text = "${event.confidence}%"
    }

    private fun showSaveRouteDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Bike to Main Block"
            setPadding(40, 30, 40, 30)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setBackgroundResource(R.drawable.bg_button_secondary)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 10)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Save Learned Route")
            .setMessage("Enter a name for this spatial route:")
            .setView(container)
            .setPositiveButton("SAVE") { _, _ ->
                val name = input.text.toString().trim()
                val finalName = if (name.isNotBlank()) name else "Route ${System.currentTimeMillis() % 10000}"
                saveAndFinish(finalName)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun saveAndFinish(name: String) {
        val route = engine.stopAndCreateRoute(name)
        val saved = repository.saveRoute(route)

        if (saved) {
            Toast.makeText(this, "Route '$name' saved successfully!", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, RouteDetailsActivity::class.java).apply {
                putExtra(RouteDetailsActivity.EXTRA_ROUTE_ID, route.id)
            }
            startActivity(intent)
            finish()
        } else {
            Toast.makeText(this, "Error saving route", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleBackPressed() {
        if (engine.learningStatus == LearningStatus.LEARNING || engine.learningStatus == LearningStatus.PAUSED) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Discard Route?")
                .setMessage("A route is currently recording. Are you sure you want to exit without saving?")
                .setPositiveButton("DISCARD") { _, _ ->
                    engine.cancelLearning()
                    finish()
                }
                .setNegativeButton("KEEP RECORDING", null)
                .show()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancelLearning()
    }
}
