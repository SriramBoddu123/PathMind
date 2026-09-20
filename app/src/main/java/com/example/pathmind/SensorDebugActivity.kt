package com.example.pathmind

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pathmind.model.MovementEvent
import com.example.pathmind.model.MovementState
import com.example.pathmind.model.TurnDirection
import com.example.pathmind.sensor.MovementSensorEngine
import com.google.android.material.button.MaterialButton

class SensorDebugActivity : AppCompatActivity() {

    private lateinit var sensorEngine: MovementSensorEngine

    private lateinit var tvMovementState: TextView
    private lateinit var tvConfidenceValue: TextView
    private lateinit var pbConfidence: ProgressBar
    private lateinit var tvStepsValue: TextView
    private lateinit var tvTurnValue: TextView
    private lateinit var tvSensorAccel: TextView
    private lateinit var tvSensorGyro: TextView
    private lateinit var tvSensorStepDetector: TextView
    private lateinit var tvSensorStepCounter: TextView
    private lateinit var tvRawDebug: TextView
    private lateinit var tvEngineState: TextView
    private lateinit var ivLiveDot: ImageView
    private lateinit var btnToggleSensorTest: MaterialButton
    private lateinit var btnResetSteps: MaterialButton
    private lateinit var cardPermissionWarning: CardView
    private lateinit var btnGrantPermission: MaterialButton

    private var isSensorTestActive = true

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            cardPermissionWarning.visibility = View.GONE
            updateSensorAvailability()
            if (isSensorTestActive) {
                sensorEngine.startListening()
            }
        } else {
            cardPermissionWarning.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sensor_debug)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.debugRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        initSensorEngine()
        checkPermissions()
        updateSensorAvailability()
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        tvMovementState = findViewById(R.id.tvMovementState)
        tvConfidenceValue = findViewById(R.id.tvConfidenceValue)
        pbConfidence = findViewById(R.id.pbConfidence)
        tvStepsValue = findViewById(R.id.tvStepsValue)
        tvTurnValue = findViewById(R.id.tvTurnValue)
        tvSensorAccel = findViewById(R.id.tvSensorAccel)
        tvSensorGyro = findViewById(R.id.tvSensorGyro)
        tvSensorStepDetector = findViewById(R.id.tvSensorStepDetector)
        tvSensorStepCounter = findViewById(R.id.tvSensorStepCounter)
        tvRawDebug = findViewById(R.id.tvRawDebug)
        tvEngineState = findViewById(R.id.tvEngineState)
        ivLiveDot = findViewById(R.id.ivLiveDot)
        btnToggleSensorTest = findViewById(R.id.btnToggleSensorTest)
        btnResetSteps = findViewById(R.id.btnResetSteps)
        cardPermissionWarning = findViewById(R.id.cardPermissionWarning)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        btnToggleSensorTest.setOnClickListener {
            toggleSensorTest()
        }

        btnResetSteps.setOnClickListener {
            sensorEngine.resetSteps()
        }

        btnGrantPermission.setOnClickListener {
            requestActivityRecognitionPermission()
        }
    }

    private fun initSensorEngine() {
        sensorEngine = MovementSensorEngine(this)
        sensorEngine.onMovementUpdated = { event, rawDebug ->
            runOnUiThread {
                updateUI(event, rawDebug)
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                cardPermissionWarning.visibility = View.VISIBLE
                requestActivityRecognitionPermission()
            } else {
                cardPermissionWarning.visibility = View.GONE
            }
        } else {
            cardPermissionWarning.visibility = View.GONE
        }
    }

    private fun requestActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun updateSensorAvailability() {
        val availability = sensorEngine.checkAvailability()

        setSensorStatus(tvSensorAccel, availability.accelerometer)
        setSensorStatus(tvSensorGyro, availability.gyroscope)
        setSensorStatus(tvSensorStepDetector, availability.stepDetector)
        setSensorStatus(tvSensorStepCounter, availability.stepCounter)
    }

    private fun setSensorStatus(textView: TextView, isAvailable: Boolean) {
        if (isAvailable) {
            textView.text = "Available"
            textView.setTextColor(ContextCompat.getColor(this, R.color.indicator_ready))
        } else {
            textView.text = "Unavailable"
            textView.setTextColor(ContextCompat.getColor(this, R.color.status_unavailable))
        }
    }

    private fun toggleSensorTest() {
        if (isSensorTestActive) {
            sensorEngine.stopListening()
            isSensorTestActive = false
            btnToggleSensorTest.text = "START SENSOR TEST"
            btnToggleSensorTest.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
            tvEngineState.text = "PAUSED"
            tvEngineState.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            ivLiveDot.visibility = View.INVISIBLE
        } else {
            sensorEngine.startListening()
            isSensorTestActive = true
            btnToggleSensorTest.text = "STOP SENSOR TEST"
            btnToggleSensorTest.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue)
            tvEngineState.text = "ACTIVE"
            tvEngineState.setTextColor(ContextCompat.getColor(this, R.color.indicator_ready))
            ivLiveDot.visibility = View.VISIBLE
        }
    }

    private fun updateUI(event: MovementEvent, rawDebug: String) {
        // Movement state
        tvMovementState.text = event.movementState.name
        when (event.movementState) {
            MovementState.STATIONARY -> tvMovementState.setTextColor(ContextCompat.getColor(this, R.color.state_stationary))
            MovementState.WALKING -> tvMovementState.setTextColor(ContextCompat.getColor(this, R.color.state_walking))
            MovementState.TURNING -> tvMovementState.setTextColor(ContextCompat.getColor(this, R.color.state_turning))
            MovementState.UNKNOWN -> tvMovementState.setTextColor(ContextCompat.getColor(this, R.color.state_unknown))
        }

        // Confidence
        tvConfidenceValue.text = "${event.confidence}%"
        pbConfidence.progress = event.confidence

        // Real steps
        tvStepsValue.text = event.totalSteps.toString()

        // Turn direction
        tvTurnValue.text = event.turnDirection.name
        when (event.turnDirection) {
            TurnDirection.LEFT -> tvTurnValue.setTextColor(ContextCompat.getColor(this, R.color.state_turning))
            TurnDirection.RIGHT -> tvTurnValue.setTextColor(ContextCompat.getColor(this, R.color.state_turning))
            TurnDirection.NONE -> tvTurnValue.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            TurnDirection.UNKNOWN -> tvTurnValue.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }

        // Raw filter diagnostics
        tvRawDebug.text = rawDebug
    }

    override fun onResume() {
        super.onResume()
        if (isSensorTestActive) {
            sensorEngine.startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorEngine.stopListening()
    }
}
