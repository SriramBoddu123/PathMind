package com.example.pathmind

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pathmind.data.MemoryEmptyStateType
import com.example.pathmind.data.MemoryFilterEngine
import com.example.pathmind.data.MemoryRepository
import com.example.pathmind.data.RouteRepository
import com.example.pathmind.model.PlaceMemory
import com.example.pathmind.model.Route
import com.example.pathmind.ui.MemoryListAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MyMemoriesActivity : AppCompatActivity() {

    private lateinit var memoryRepository: MemoryRepository
    private lateinit var routeRepository: RouteRepository
    private lateinit var adapter: MemoryListAdapter

    // Full in-memory cached state
    private var allMemories: List<PlaceMemory> = emptyList()
    private var routesMap: Map<String, Route> = emptyMap()
    private var currentSearchQuery: String = ""
    private var selectedRouteId: String? = null

    // UI Components
    private lateinit var tvMemoriesCount: TextView
    private lateinit var rvMemories: RecyclerView
    private lateinit var layoutSearchAndFilter: LinearLayout
    private lateinit var etSearchMemories: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var chipGroupRoutes: ChipGroup

    // Empty state views
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var ivEmptyIcon: ImageView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var btnEmptyRememberNew: MaterialButton

    private lateinit var btnRememberNewPlace: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_my_memories)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.myMemoriesRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        memoryRepository = MemoryRepository(this)
        routeRepository = RouteRepository(this)

        initViews()
        setupSearchAndFilter()
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        loadMemories()
    }

    private fun initViews() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        tvMemoriesCount = findViewById(R.id.tvMemoriesCount)
        rvMemories = findViewById(R.id.rvMemories)
        layoutSearchAndFilter = findViewById(R.id.layoutSearchAndFilter)
        etSearchMemories = findViewById(R.id.etSearchMemories)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        chipGroupRoutes = findViewById(R.id.chipGroupRoutes)

        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        btnEmptyRememberNew = findViewById(R.id.btnEmptyRememberNew)
        btnRememberNewPlace = findViewById(R.id.btnRememberNewPlace)

        val openRememberPlace = View.OnClickListener {
            val intent = Intent(this, RememberPlaceActivity::class.java)
            startActivity(intent)
        }

        btnEmptyRememberNew.setOnClickListener(openRememberPlace)
        btnRememberNewPlace.setOnClickListener(openRememberPlace)
    }

    private fun setupSearchAndFilter() {
        etSearchMemories.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString().orEmpty()
                btnClearSearch.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            etSearchMemories.text.clear()
        }

        etSearchMemories.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = MemoryListAdapter(
            memories = emptyList(),
            routesMap = emptyMap(),
            onViewClick = { memory -> openMemoryDetails(memory) },
            onDeleteClick = { memory -> confirmDeleteMemory(memory) }
        )
        rvMemories.layoutManager = LinearLayoutManager(this)
        rvMemories.adapter = adapter
    }

    private fun loadMemories() {
        allMemories = memoryRepository.getAllMemories()
        val routes = routeRepository.getRoutes()
        routesMap = routes.associateBy { it.id }

        populateRouteChips()
        applyFilters()
    }

    private fun populateRouteChips() {
        chipGroupRoutes.removeAllViews()

        val associatedRouteIds = MemoryFilterEngine.getAssociatedRouteIds(allMemories)

        // If previously selected route is no longer associated with any saved memory, reset to All Routes
        if (selectedRouteId != null && selectedRouteId !in associatedRouteIds) {
            selectedRouteId = null
        }

        // 1. "All Routes" Chip
        val allChip = layoutInflater.inflate(R.layout.item_route_chip, chipGroupRoutes, false) as Chip
        allChip.id = View.generateViewId()
        allChip.text = "All Routes"
        allChip.isChecked = (selectedRouteId == null)
        allChip.setOnClickListener {
            selectedRouteId = null
            applyFilters()
        }
        chipGroupRoutes.addView(allChip)

        // 2. Distinct Route Chips
        for (routeId in associatedRouteIds) {
            val route = routesMap[routeId]
            val routeName = route?.name ?: "Route $routeId"
            val chip = layoutInflater.inflate(R.layout.item_route_chip, chipGroupRoutes, false) as Chip
            chip.id = View.generateViewId()
            chip.text = routeName
            chip.isChecked = (selectedRouteId == routeId)
            chip.setOnClickListener {
                selectedRouteId = routeId
                applyFilters()
            }
            chipGroupRoutes.addView(chip)
        }
    }

    private fun applyFilters() {
        val filtered = MemoryFilterEngine.filterMemories(allMemories, currentSearchQuery, selectedRouteId)
        adapter.updateData(filtered, routesMap)

        // Update count badge
        if (allMemories.isEmpty()) {
            tvMemoriesCount.text = "0 Places"
        } else if (filtered.size == allMemories.size) {
            tvMemoriesCount.text = "${allMemories.size} Places"
        } else {
            tvMemoriesCount.text = "${filtered.size} of ${allMemories.size} Places"
        }

        // Handle visibility and empty states
        if (allMemories.isEmpty()) {
            layoutSearchAndFilter.visibility = View.GONE
        } else {
            layoutSearchAndFilter.visibility = View.VISIBLE
        }

        val emptyState = MemoryFilterEngine.determineEmptyState(
            totalCount = allMemories.size,
            filteredCount = filtered.size,
            query = currentSearchQuery,
            selectedRouteId = selectedRouteId
        )

        when (emptyState) {
            MemoryEmptyStateType.NONE -> {
                layoutEmptyState.visibility = View.GONE
                rvMemories.visibility = View.VISIBLE
            }
            MemoryEmptyStateType.NO_MEMORIES_SAVED -> {
                layoutEmptyState.visibility = View.VISIBLE
                rvMemories.visibility = View.GONE
                ivEmptyIcon.setImageResource(R.drawable.ic_place)
                tvEmptyTitle.text = "NO MEMORIES YET"
                tvEmptyMessage.text = "Anchor meaningful locations like your bike, desk, or car to relative route positions."
                btnEmptyRememberNew.visibility = View.VISIBLE
                btnEmptyRememberNew.text = "REMEMBER A PLACE"
                btnEmptyRememberNew.setIconResource(R.drawable.ic_place)
                btnEmptyRememberNew.setOnClickListener {
                    val intent = Intent(this, RememberPlaceActivity::class.java)
                    startActivity(intent)
                }
            }
            MemoryEmptyStateType.NO_MATCHING_SEARCH -> {
                layoutEmptyState.visibility = View.VISIBLE
                rvMemories.visibility = View.GONE
                ivEmptyIcon.setImageResource(R.drawable.ic_search)
                tvEmptyTitle.text = "NO MATCHING MEMORIES"
                tvEmptyMessage.text = "No saved memories match your search query."
                btnEmptyRememberNew.visibility = View.VISIBLE
                btnEmptyRememberNew.text = "CLEAR SEARCH"
                btnEmptyRememberNew.setIconResource(R.drawable.ic_close)
                btnEmptyRememberNew.setOnClickListener {
                    etSearchMemories.text.clear()
                }
            }
            MemoryEmptyStateType.NO_MEMORIES_ON_ROUTE -> {
                layoutEmptyState.visibility = View.VISIBLE
                rvMemories.visibility = View.GONE
                ivEmptyIcon.setImageResource(R.drawable.ic_route)
                tvEmptyTitle.text = "NO MEMORIES ON THIS ROUTE"
                val routeName = routesMap[selectedRouteId]?.name ?: "this route"
                tvEmptyMessage.text = "No saved memories are associated with $routeName."
                btnEmptyRememberNew.visibility = View.VISIBLE
                btnEmptyRememberNew.text = "ALL ROUTES"
                btnEmptyRememberNew.setIconResource(R.drawable.ic_route)
                btnEmptyRememberNew.setOnClickListener {
                    selectedRouteId = null
                    populateRouteChips()
                    applyFilters()
                }
            }
        }
    }

    private fun openMemoryDetails(memory: PlaceMemory) {
        val intent = Intent(this, MemoryDetailsActivity::class.java).apply {
            putExtra(MemoryDetailsActivity.EXTRA_MEMORY_ID, memory.id)
        }
        startActivity(intent)
    }

    private fun confirmDeleteMemory(memory: PlaceMemory) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Memory")
            .setMessage("Are you sure you want to delete '${memory.name}'? This cannot be undone.")
            .setPositiveButton("DELETE") { _, _ ->
                memoryRepository.deleteMemory(memory.id)
                Toast.makeText(this, "Memory deleted", Toast.LENGTH_SHORT).show()
                loadMemories()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
