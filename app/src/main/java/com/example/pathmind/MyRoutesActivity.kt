package com.example.pathmind

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.example.pathmind.ui.RouteListAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MyRoutesActivity : AppCompatActivity() {

    private lateinit var repository: RouteRepository
    private lateinit var adapter: RouteListAdapter

    private lateinit var tvRoutesCount: TextView
    private lateinit var rvRoutes: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var btnEmptyRecordNew: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_my_routes)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.myRoutesRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = RouteRepository(this)
        initViews()
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        loadRoutes()
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        tvRoutesCount = findViewById(R.id.tvRoutesCount)
        rvRoutes = findViewById(R.id.rvRoutes)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        btnEmptyRecordNew = findViewById(R.id.btnEmptyRecordNew)

        btnEmptyRecordNew.setOnClickListener {
            val intent = Intent(this, RouteLearningActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupRecyclerView() {
        adapter = RouteListAdapter(
            routes = emptyList(),
            onViewClick = { route -> openRouteDetails(route) },
            onDeleteClick = { route -> confirmDeleteRoute(route) }
        )
        rvRoutes.layoutManager = LinearLayoutManager(this)
        rvRoutes.adapter = adapter
    }

    private fun loadRoutes() {
        val routes = repository.getRoutes()
        adapter.updateRoutes(routes)

        tvRoutesCount.text = "${routes.size} Routes"

        if (routes.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvRoutes.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvRoutes.visibility = View.VISIBLE
        }
    }

    private fun openRouteDetails(route: Route) {
        val intent = Intent(this, RouteDetailsActivity::class.java).apply {
            putExtra(RouteDetailsActivity.EXTRA_ROUTE_ID, route.id)
        }
        startActivity(intent)
    }

    private fun confirmDeleteRoute(route: Route) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Route")
            .setMessage("Are you sure you want to delete '${route.name}'? This cannot be undone.")
            .setPositiveButton("DELETE") { _, _ ->
                repository.deleteRoute(route.id)
                Toast.makeText(this, "Route deleted", Toast.LENGTH_SHORT).show()
                loadRoutes()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
