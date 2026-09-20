package com.example.pathmind

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pathmind.data.RouteRepository
import com.example.pathmind.model.Route
import com.example.pathmind.ui.RouteSegmentAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date

class RouteDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROUTE_ID = "extra_route_id"
    }

    private lateinit var repository: RouteRepository
    private var currentRoute: Route? = null

    private lateinit var tvDetailsRouteName: TextView
    private lateinit var tvDetailsDate: TextView
    private lateinit var tvDetailsSteps: TextView
    private lateinit var tvDetailsSegments: TextView
    private lateinit var tvDetailsConfidence: TextView
    private lateinit var rvDetailsSegments: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_route_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.routeDetailsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = RouteRepository(this)
        initViews()

        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
        if (routeId != null) {
            currentRoute = repository.getRouteById(routeId)
        }

        if (currentRoute == null) {
            Toast.makeText(this, "Route not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindRouteDetails(currentRoute!!)
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        val btnDelete = findViewById<ImageButton>(R.id.btnDelete)
        btnDelete.setOnClickListener { confirmDelete() }

        tvDetailsRouteName = findViewById(R.id.tvDetailsRouteName)
        tvDetailsDate = findViewById(R.id.tvDetailsDate)
        tvDetailsSteps = findViewById(R.id.tvDetailsSteps)
        tvDetailsSegments = findViewById(R.id.tvDetailsSegments)
        tvDetailsConfidence = findViewById(R.id.tvDetailsConfidence)
        rvDetailsSegments = findViewById(R.id.rvDetailsSegments)

        rvDetailsSegments.layoutManager = LinearLayoutManager(this)

        val btnNavigateRoute = findViewById<MaterialButton>(R.id.btnNavigateRoute)
        btnNavigateRoute.setOnClickListener {
            val route = currentRoute ?: return@setOnClickListener
            if (route.segments.isEmpty()) {
                Toast.makeText(this, "Route has no segments to navigate", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, NavigationActivity::class.java).apply {
                putExtra(NavigationActivity.EXTRA_ROUTE_ID, route.id)
            }
            startActivity(intent)
        }

        val btnRememberPlaceOnRoute = findViewById<MaterialButton>(R.id.btnRememberPlaceOnRoute)
        btnRememberPlaceOnRoute.setOnClickListener {
            val route = currentRoute ?: return@setOnClickListener
            if (route.segments.isEmpty()) {
                Toast.makeText(this, "Route has no segments to anchor a memory", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, RememberPlaceActivity::class.java).apply {
                putExtra(RememberPlaceActivity.EXTRA_ROUTE_ID, route.id)
            }
            startActivity(intent)
        }
    }

    private fun bindRouteDetails(route: Route) {
        tvDetailsRouteName.text = route.name
        val dateStr = DateFormat.format("MMMM dd, yyyy • HH:mm", Date(route.createdAt)).toString()
        tvDetailsDate.text = "Recorded on $dateStr"

        tvDetailsSteps.text = route.totalSteps.toString()
        tvDetailsSegments.text = route.totalSegments.toString()

        val avgConfidence = if (route.segments.isNotEmpty()) {
            route.segments.map { it.confidence }.average().toInt()
        } else {
            100
        }
        tvDetailsConfidence.text = "$avgConfidence%"

        val adapter = RouteSegmentAdapter(route.segments) { segment, index ->
            var cumulativeSteps = 0
            for (i in 0..index) {
                cumulativeSteps += route.segments[i].steps
            }
            val intent = Intent(this, RememberPlaceActivity::class.java).apply {
                putExtra(RememberPlaceActivity.EXTRA_ROUTE_ID, route.id)
                putExtra(RememberPlaceActivity.EXTRA_SEGMENT_ID, segment.id)
                putExtra(RememberPlaceActivity.EXTRA_STEP_POSITION, cumulativeSteps)
                putExtra(RememberPlaceActivity.EXTRA_CONFIDENCE, segment.confidence)
            }
            startActivity(intent)
        }
        rvDetailsSegments.adapter = adapter
    }

    private fun confirmDelete() {
        val route = currentRoute ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Route")
            .setMessage("Are you sure you want to delete '${route.name}'?")
            .setPositiveButton("DELETE") { _, _ ->
                repository.deleteRoute(route.id)
                Toast.makeText(this, "Route deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
