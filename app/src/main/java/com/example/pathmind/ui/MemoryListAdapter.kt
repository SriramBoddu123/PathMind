package com.example.pathmind.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pathmind.R
import com.example.pathmind.model.PlaceMemory
import com.google.android.material.button.MaterialButton

class MemoryListAdapter(
    private var memories: List<PlaceMemory> = emptyList(),
    private var routesMap: Map<String, com.example.pathmind.model.Route> = emptyMap(),
    private val onViewClick: (PlaceMemory) -> Unit,
    private val onDeleteClick: (PlaceMemory) -> Unit
) : RecyclerView.Adapter<MemoryListAdapter.MemoryViewHolder>() {

    fun updateData(newMemories: List<PlaceMemory>, newMap: Map<String, com.example.pathmind.model.Route>) {
        memories = newMemories
        routesMap = newMap
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_memory, parent, false)
        return MemoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: MemoryViewHolder, position: Int) {
        val memory = memories[position]
        val route = routesMap[memory.routeId]
        holder.bind(memory, route, onViewClick, onDeleteClick)
    }

    override fun getItemCount(): Int = memories.size

    class MemoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMemoryName: TextView = itemView.findViewById(R.id.tvMemoryName)
        private val tvMemoryDescription: TextView = itemView.findViewById(R.id.tvMemoryDescription)
        private val tvMemoryRouteName: TextView = itemView.findViewById(R.id.tvMemoryRouteName)
        private val tvMemoryPosition: TextView = itemView.findViewById(R.id.tvMemoryPosition)
        private val tvMemoryConfidence: TextView = itemView.findViewById(R.id.tvMemoryConfidence)
        private val btnViewMemory: MaterialButton = itemView.findViewById(R.id.btnViewMemory)
        private val btnDeleteMemory: MaterialButton = itemView.findViewById(R.id.btnDeleteMemory)

        fun bind(
            memory: PlaceMemory,
            route: com.example.pathmind.model.Route?,
            onView: (PlaceMemory) -> Unit,
            onDelete: (PlaceMemory) -> Unit
        ) {
            tvMemoryName.text = memory.name

            if (!memory.description.isNullOrBlank()) {
                tvMemoryDescription.text = memory.description
                tvMemoryDescription.visibility = View.VISIBLE
            } else {
                tvMemoryDescription.visibility = View.GONE
            }

            val routeName = route?.name ?: "Unknown Route"
            tvMemoryRouteName.text = "Route: $routeName"

            val segIndex = route?.segments?.indexOfFirst { it.id == memory.segmentId } ?: -1
            val segLabel = if (segIndex >= 0) "Segment ${segIndex + 1}" else "Route position"
            tvMemoryPosition.text = "Position: $segLabel • ${memory.stepPosition} steps"
            tvMemoryConfidence.text = "${memory.confidence}%"

            btnViewMemory.setOnClickListener { onView(memory) }
            btnDeleteMemory.setOnClickListener { onDelete(memory) }
            itemView.setOnClickListener { onView(memory) }
        }
    }
}
