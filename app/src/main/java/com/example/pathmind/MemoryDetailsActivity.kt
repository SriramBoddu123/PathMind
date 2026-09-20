package com.example.pathmind

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pathmind.data.MemoryRepository
import com.example.pathmind.data.RouteRepository
import com.example.pathmind.model.PlaceMemory
import com.example.pathmind.model.Route
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date

class MemoryDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEMORY_ID = "extra_memory_id"
    }

    private lateinit var memoryRepository: MemoryRepository
    private lateinit var routeRepository: RouteRepository

    private var currentMemory: PlaceMemory? = null
    private var associatedRoute: Route? = null

    private lateinit var tvDetailsMemoryName: TextView
    private lateinit var tvDetailsConfidence: TextView
    private lateinit var tvDetailsDescription: TextView
    private lateinit var tvDetailsCreatedDate: TextView
    private lateinit var tvDetailsRouteName: TextView
    private lateinit var tvDetailsPosition: TextView
    private lateinit var tvDetailsSegmentAction: TextView
    private lateinit var btnViewAssociatedRoute: MaterialButton
    private lateinit var btnNavigateToMemory: MaterialButton

    // Stage 9: Visual Landmark views
    private lateinit var cardDetailsLandmark: androidx.cardview.widget.CardView
    private lateinit var ivDetailsLandmarkPhoto: android.widget.ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_memory_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.memoryDetailsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        memoryRepository = MemoryRepository(this)
        routeRepository = RouteRepository(this)

        initViews()

        val memoryId = intent.getStringExtra(EXTRA_MEMORY_ID)
        if (memoryId != null) {
            currentMemory = memoryRepository.getMemoryById(memoryId)
        }

        if (currentMemory == null) {
            Toast.makeText(this, "Memory not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        associatedRoute = routeRepository.getRouteById(currentMemory!!.routeId)
        bindMemoryDetails(currentMemory!!, associatedRoute)
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        val btnDelete = findViewById<ImageButton>(R.id.btnDelete)
        btnDelete.setOnClickListener { confirmDelete() }

        tvDetailsMemoryName = findViewById(R.id.tvDetailsMemoryName)
        tvDetailsConfidence = findViewById(R.id.tvDetailsConfidence)
        tvDetailsDescription = findViewById(R.id.tvDetailsDescription)
        tvDetailsCreatedDate = findViewById(R.id.tvDetailsCreatedDate)
        tvDetailsRouteName = findViewById(R.id.tvDetailsRouteName)
        tvDetailsPosition = findViewById(R.id.tvDetailsPosition)
        tvDetailsSegmentAction = findViewById(R.id.tvDetailsSegmentAction)
        btnViewAssociatedRoute = findViewById(R.id.btnViewAssociatedRoute)
        btnNavigateToMemory = findViewById(R.id.btnNavigateToMemory)
        cardDetailsLandmark = findViewById(R.id.cardDetailsLandmark)
        ivDetailsLandmarkPhoto = findViewById(R.id.ivDetailsLandmarkPhoto)

        btnNavigateToMemory.setOnClickListener {
            val memory = currentMemory ?: return@setOnClickListener
            val route = associatedRoute
            if (route == null) {
                Toast.makeText(this, "Associated route not found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, NavigationActivity::class.java).apply {
                putExtra(NavigationActivity.EXTRA_ROUTE_ID, route.id)
                putExtra(NavigationActivity.EXTRA_TARGET_SEGMENT_ID, memory.segmentId)
                putExtra(NavigationActivity.EXTRA_TARGET_NAME, memory.name)
            }
            startActivity(intent)
        }

        btnViewAssociatedRoute.setOnClickListener {
            val route = associatedRoute
            if (route != null) {
                val intent = Intent(this, RouteDetailsActivity::class.java).apply {
                    putExtra(RouteDetailsActivity.EXTRA_ROUTE_ID, route.id)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Associated route no longer exists", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindMemoryDetails(memory: PlaceMemory, route: Route?) {
        tvDetailsMemoryName.text = memory.name
        tvDetailsConfidence.text = "${memory.confidence}%"

        if (!memory.description.isNullOrBlank()) {
            tvDetailsDescription.text = memory.description
            tvDetailsDescription.visibility = View.VISIBLE
        } else {
            tvDetailsDescription.visibility = View.GONE
        }

        val dateStr = DateFormat.format("MMMM dd, yyyy • HH:mm", Date(memory.createdAt)).toString()
        tvDetailsCreatedDate.text = "Recorded on $dateStr"

        // Stage 9: Load Visual Landmark Photo if present
        if (!memory.photoPath.isNullOrBlank()) {
            val bmp = com.example.pathmind.util.LandmarkImageManager.loadLandmarkBitmap(memory.photoPath, 800)
            if (bmp != null) {
                ivDetailsLandmarkPhoto.setImageBitmap(bmp)
                cardDetailsLandmark.visibility = View.VISIBLE
            } else {
                cardDetailsLandmark.visibility = View.GONE
            }
        } else {
            cardDetailsLandmark.visibility = View.GONE
        }

        if (route != null) {
            tvDetailsRouteName.text = route.name

            val segmentIndex = route.segments.indexOfFirst { it.id == memory.segmentId }
            val segNumber = if (segmentIndex >= 0) "Segment ${segmentIndex + 1}" else "Segment"
            tvDetailsPosition.text = "$segNumber • ${memory.stepPosition} steps from route start"

            if (segmentIndex >= 0) {
                val seg = route.segments[segmentIndex]
                tvDetailsSegmentAction.text = "Anchor Action: ${seg.action.name} (${seg.direction.name})"
                tvDetailsSegmentAction.visibility = View.VISIBLE
            } else {
                tvDetailsSegmentAction.visibility = View.GONE
            }

            btnViewAssociatedRoute.visibility = View.VISIBLE
            btnNavigateToMemory.visibility = View.VISIBLE
        } else {
            tvDetailsRouteName.text = "Unknown / Deleted Route"
            tvDetailsPosition.text = "${memory.stepPosition} steps from route start"
            tvDetailsSegmentAction.visibility = View.GONE
            btnViewAssociatedRoute.visibility = View.GONE
            btnNavigateToMemory.visibility = View.GONE
        }
    }

    private fun confirmDelete() {
        val memory = currentMemory ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Memory")
            .setMessage("Are you sure you want to delete '${memory.name}'?")
            .setPositiveButton("DELETE") { _, _ ->
                memoryRepository.deleteMemory(memory.id)
                Toast.makeText(this, "Memory deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
