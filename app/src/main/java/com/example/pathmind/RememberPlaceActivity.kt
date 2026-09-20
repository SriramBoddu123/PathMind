package com.example.pathmind

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pathmind.data.MemoryRepository
import com.example.pathmind.data.RouteRepository
import com.example.pathmind.model.PlaceMemory
import com.example.pathmind.model.Route
import com.example.pathmind.model.RouteSegment
import com.example.pathmind.model.SegmentAction
import com.google.android.material.button.MaterialButton
import java.util.UUID

class RememberPlaceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROUTE_ID = "extra_route_id"
        const val EXTRA_SEGMENT_ID = "extra_segment_id"
        const val EXTRA_STEP_POSITION = "extra_step_position"
        const val EXTRA_CONFIDENCE = "extra_confidence"
    }

    private lateinit var routeRepository: RouteRepository
    private lateinit var memoryRepository: MemoryRepository

    private lateinit var etPlaceName: EditText
    private lateinit var etDescription: EditText
    private lateinit var spinnerRoute: Spinner
    private lateinit var spinnerSegment: Spinner
    private lateinit var tvRelativePositionDesc: TextView
    private lateinit var tvAnchorConfidence: TextView
    private lateinit var btnSaveMemory: MaterialButton
    private lateinit var layoutForm: LinearLayout
    private lateinit var cardNoRoutesWarning: CardView
    private lateinit var btnLearnRouteFirst: MaterialButton

    // Stage 9: Visual landmark views & state
    private lateinit var layoutPhotoPreview: LinearLayout
    private lateinit var ivLandmarkPreview: android.widget.ImageView
    private lateinit var btnRemovePhoto: MaterialButton
    private lateinit var btnCapturePhoto: MaterialButton

    private var tempCaptureFile: java.io.File? = null
    private var currentPhotoPath: String? = null
    private val memoryId: String = UUID.randomUUID().toString()
    private var memorySavedSuccessfully: Boolean = false

    private val takePictureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        val tempFile = tempCaptureFile
        if (success && tempFile != null && tempFile.exists() && tempFile.length() > 0) {
            val savedPath = com.example.pathmind.util.LandmarkImageManager.processAndSaveLandmark(
                this,
                tempFile,
                memoryId
            )
            if (savedPath != null) {
                currentPhotoPath = savedPath
                val bmp = com.example.pathmind.util.LandmarkImageManager.loadLandmarkBitmap(savedPath, 400)
                if (bmp != null) {
                    ivLandmarkPreview.setImageBitmap(bmp)
                    layoutPhotoPreview.visibility = View.VISIBLE
                    btnCapturePhoto.text = "📷 RETAKE LANDMARK PHOTO"
                }
            } else {
                Toast.makeText(this, "Failed to process landmark photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var availableRoutes: List<Route> = emptyList()
    private var selectedRoute: Route? = null
    private var selectedSegment: RouteSegment? = null
    private var selectedCumulativeSteps: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_remember_place)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rememberPlaceRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        routeRepository = RouteRepository(this)
        memoryRepository = MemoryRepository(this)

        initViews()
        loadRoutesAndSetupSpinners()
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        etPlaceName = findViewById(R.id.etPlaceName)
        etDescription = findViewById(R.id.etDescription)
        spinnerRoute = findViewById(R.id.spinnerRoute)
        spinnerSegment = findViewById(R.id.spinnerSegment)
        tvRelativePositionDesc = findViewById(R.id.tvRelativePositionDesc)
        tvAnchorConfidence = findViewById(R.id.tvAnchorConfidence)
        btnSaveMemory = findViewById(R.id.btnSaveMemory)
        layoutForm = findViewById(R.id.layoutForm)
        cardNoRoutesWarning = findViewById(R.id.cardNoRoutesWarning)
        btnLearnRouteFirst = findViewById(R.id.btnLearnRouteFirst)

        // Stage 9: Landmark views initialization
        layoutPhotoPreview = findViewById(R.id.layoutPhotoPreview)
        ivLandmarkPreview = findViewById(R.id.ivLandmarkPreview)
        btnRemovePhoto = findViewById(R.id.btnRemovePhoto)
        btnCapturePhoto = findViewById(R.id.btnCapturePhoto)

        btnCapturePhoto.setOnClickListener {
            try {
                val (uri, file) = com.example.pathmind.util.LandmarkImageManager.createTempCaptureUri(this)
                tempCaptureFile = file
                takePictureLauncher.launch(uri)
            } catch (e: Exception) {
                android.util.Log.e("RememberPlaceActivity", "Error launching camera", e)
                Toast.makeText(this, "Unable to launch camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnRemovePhoto.setOnClickListener {
            if (currentPhotoPath != null) {
                com.example.pathmind.util.LandmarkImageManager.deleteLandmarkImage(currentPhotoPath)
                currentPhotoPath = null
            }
            ivLandmarkPreview.setImageDrawable(null)
            layoutPhotoPreview.visibility = View.GONE
            btnCapturePhoto.text = "📷 ATTACH LANDMARK PHOTO"
        }

        val btnOpenMemoriesList = findViewById<ImageButton>(R.id.btnOpenMemoriesList)
        btnOpenMemoriesList.setOnClickListener {
            val intent = Intent(this, MyMemoriesActivity::class.java)
            startActivity(intent)
        }

        btnSaveMemory.setOnClickListener { saveMemory() }

        btnLearnRouteFirst.setOnClickListener {
            val intent = Intent(this, RouteLearningActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun loadRoutesAndSetupSpinners() {
        availableRoutes = routeRepository.getRoutes()

        if (availableRoutes.isEmpty()) {
            cardNoRoutesWarning.visibility = View.VISIBLE
            layoutForm.visibility = View.GONE
            btnSaveMemory.visibility = View.GONE
            return
        }

        cardNoRoutesWarning.visibility = View.GONE
        layoutForm.visibility = View.VISIBLE
        btnSaveMemory.visibility = View.VISIBLE

        val initialRouteId = intent.getStringExtra(EXTRA_ROUTE_ID)
        val initialSegmentId = intent.getStringExtra(EXTRA_SEGMENT_ID)

        // Setup Route Spinner
        val routeNames = availableRoutes.map { it.name }
        val routeAdapter = ArrayAdapter(this, R.layout.spinner_item, routeNames).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerRoute.adapter = routeAdapter

        var initialRouteIndex = 0
        if (!initialRouteId.isNullOrBlank()) {
            val idx = availableRoutes.indexOfFirst { it.id == initialRouteId }
            if (idx >= 0) initialRouteIndex = idx
        }
        spinnerRoute.setSelection(initialRouteIndex)
        selectedRoute = availableRoutes[initialRouteIndex]

        setupSegmentSpinner(selectedRoute!!, initialSegmentId)

        spinnerRoute.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedRoute = availableRoutes[position]
                setupSegmentSpinner(selectedRoute!!, null)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSegmentSpinner(route: Route, preselectedSegmentId: String?) {
        if (route.segments.isEmpty()) {
            spinnerSegment.adapter = ArrayAdapter(this, R.layout.spinner_item, listOf("No segments"))
            return
        }

        val segmentDescriptions = mutableListOf<String>()
        var runningSteps = 0
        val cumulativeStepsList = mutableListOf<Int>()

        route.segments.forEachIndexed { index, seg ->
            runningSteps += seg.steps
            cumulativeStepsList.add(runningSteps)

            val actionDesc = when (seg.action) {
                SegmentAction.START -> "START (Origin)"
                SegmentAction.WALK -> "WALK (${seg.steps} steps)"
                SegmentAction.TURN -> "${seg.direction} TURN"
                SegmentAction.PAUSE -> "PAUSE (${seg.duration / 1000}s)"
                SegmentAction.DESTINATION -> "DESTINATION"
            }
            segmentDescriptions.add("Segment ${index + 1}: $actionDesc • $runningSteps steps total")
        }

        val segmentAdapter = ArrayAdapter(this, R.layout.spinner_item, segmentDescriptions).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerSegment.adapter = segmentAdapter

        var segmentIndex = 0
        if (!preselectedSegmentId.isNullOrBlank()) {
            val idx = route.segments.indexOfFirst { it.id == preselectedSegmentId }
            if (idx >= 0) segmentIndex = idx
        }
        spinnerSegment.setSelection(segmentIndex)
        updateRelativePositionDisplay(route, segmentIndex, cumulativeStepsList[segmentIndex])

        spinnerSegment.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateRelativePositionDisplay(route, position, cumulativeStepsList[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateRelativePositionDisplay(route: Route, segmentIndex: Int, cumulativeSteps: Int) {
        if (segmentIndex in route.segments.indices) {
            selectedSegment = route.segments[segmentIndex]
            selectedCumulativeSteps = cumulativeSteps

            tvRelativePositionDesc.text = "Segment ${segmentIndex + 1} / $cumulativeSteps steps from start"
            tvAnchorConfidence.text = "${selectedSegment?.confidence ?: 90}%"
        }
    }

    private fun saveMemory() {
        val name = etPlaceName.text.toString().trim()
        if (name.isBlank()) {
            etPlaceName.error = "Please enter a place name"
            etPlaceName.requestFocus()
            return
        }

        val route = selectedRoute
        val segment = selectedSegment

        if (route == null || segment == null) {
            Toast.makeText(this, "Please select a valid route and segment", Toast.LENGTH_SHORT).show()
            return
        }

        val description = etDescription.text.toString().trim().ifBlank { null }

        val memory = PlaceMemory(
            id = memoryId,
            name = name,
            createdAt = System.currentTimeMillis(),
            routeId = route.id,
            segmentId = segment.id,
            stepPosition = selectedCumulativeSteps,
            confidence = segment.confidence,
            description = description,
            photoPath = currentPhotoPath
        )

        val success = memoryRepository.saveMemory(memory)
        if (success) {
            memorySavedSuccessfully = true
            Toast.makeText(this, "Remembered place '$name' saved!", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MyMemoriesActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            Toast.makeText(this, "Failed to save memory", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !memorySavedSuccessfully && currentPhotoPath != null) {
            com.example.pathmind.util.LandmarkImageManager.deleteLandmarkImage(currentPhotoPath)
        }
    }
}
