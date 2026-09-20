package com.example.pathmind.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pathmind.R
import com.example.pathmind.model.Route
import com.google.android.material.button.MaterialButton
import java.util.Date

class RouteListAdapter(
    private var routes: List<Route> = emptyList(),
    private val onViewClick: (Route) -> Unit,
    private val onDeleteClick: (Route) -> Unit
) : RecyclerView.Adapter<RouteListAdapter.RouteViewHolder>() {

    fun updateRoutes(newRoutes: List<Route>) {
        routes = newRoutes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_route, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val route = routes[position]
        holder.bind(route, onViewClick, onDeleteClick)
    }

    override fun getItemCount(): Int = routes.size

    class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRouteName: TextView = itemView.findViewById(R.id.tvRouteName)
        private val tvRouteDate: TextView = itemView.findViewById(R.id.tvRouteDate)
        private val tvRouteSegments: TextView = itemView.findViewById(R.id.tvRouteSegments)
        private val tvRouteSteps: TextView = itemView.findViewById(R.id.tvRouteSteps)
        private val btnViewRoute: MaterialButton = itemView.findViewById(R.id.btnViewRoute)
        private val btnDeleteRoute: MaterialButton = itemView.findViewById(R.id.btnDeleteRoute)

        fun bind(route: Route, onView: (Route) -> Unit, onDelete: (Route) -> Unit) {
            tvRouteName.text = route.name
            tvRouteSegments.text = "${route.totalSegments} segments"
            tvRouteSteps.text = "${route.totalSteps} steps"

            val dateStr = DateFormat.format("MMM dd, yyyy • HH:mm", Date(route.createdAt)).toString()
            tvRouteDate.text = dateStr

            btnViewRoute.setOnClickListener { onView(route) }
            btnDeleteRoute.setOnClickListener { onDelete(route) }
            itemView.setOnClickListener { onView(route) }
        }
    }
}
