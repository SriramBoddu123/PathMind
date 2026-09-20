package com.example.pathmind.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.pathmind.R
import com.example.pathmind.model.RouteSegment
import com.example.pathmind.model.SegmentAction
import com.example.pathmind.model.SegmentDirection

class RouteSegmentAdapter(
    private var segments: List<RouteSegment> = emptyList(),
    private val onSegmentClick: ((RouteSegment, Int) -> Unit)? = null
) : RecyclerView.Adapter<RouteSegmentAdapter.SegmentViewHolder>() {

    fun updateSegments(newSegments: List<RouteSegment>) {
        segments = newSegments
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SegmentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_segment, parent, false)
        return SegmentViewHolder(view)
    }

    override fun onBindViewHolder(holder: SegmentViewHolder, position: Int) {
        val segment = segments[position]
        holder.bind(segment, isFirst = (position == 0))
        holder.itemView.setOnClickListener {
            onSegmentClick?.invoke(segment, position)
        }
    }

    override fun getItemCount(): Int = segments.size

    class SegmentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivConnectorArrow: ImageView = itemView.findViewById(R.id.ivConnectorArrow)
        private val ivSegmentIcon: ImageView = itemView.findViewById(R.id.ivSegmentIcon)
        private val tvSegmentTitle: TextView = itemView.findViewById(R.id.tvSegmentTitle)
        private val tvSegmentSubtitle: TextView = itemView.findViewById(R.id.tvSegmentSubtitle)
        private val tvSegmentConfidence: TextView = itemView.findViewById(R.id.tvSegmentConfidence)

        fun bind(segment: RouteSegment, isFirst: Boolean) {
            val context = itemView.context
            ivConnectorArrow.visibility = if (isFirst) View.GONE else View.VISIBLE

            val durationSec = segment.duration / 1000

            when (segment.action) {
                SegmentAction.START -> {
                    ivSegmentIcon.setImageResource(R.drawable.ic_start_flag)
                    tvSegmentTitle.text = "START"
                    tvSegmentTitle.setTextColor(ContextCompat.getColor(context, R.color.indicator_ready))
                    tvSegmentSubtitle.text = "Origin anchor point"
                }

                SegmentAction.WALK -> {
                    ivSegmentIcon.setImageResource(R.drawable.ic_walk_steps)
                    tvSegmentTitle.text = "WALK — ${segment.steps} steps"
                    tvSegmentTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    val durStr = if (durationSec > 0) " • ${durationSec}s" else ""
                    tvSegmentSubtitle.text = "Straight path$durStr"
                }

                SegmentAction.TURN -> {
                    val isLeft = segment.direction == SegmentDirection.LEFT
                    ivSegmentIcon.setImageResource(if (isLeft) R.drawable.ic_turn_left else R.drawable.ic_turn_right)
                    val turnName = if (isLeft) "LEFT TURN" else "RIGHT TURN"
                    tvSegmentTitle.text = turnName
                    tvSegmentTitle.setTextColor(ContextCompat.getColor(context, R.color.state_turning))
                    val stepsStr = if (segment.steps > 0) "${segment.steps} steps • " else ""
                    tvSegmentSubtitle.text = "${stepsStr}Gyroscope yaw • ${durationSec}s"
                }

                SegmentAction.PAUSE -> {
                    ivSegmentIcon.setImageResource(R.drawable.ic_dot_active)
                    tvSegmentTitle.text = "PAUSE / STOP"
                    tvSegmentTitle.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                    tvSegmentSubtitle.text = "Stationary • ${durationSec}s"
                }

                SegmentAction.DESTINATION -> {
                    ivSegmentIcon.setImageResource(R.drawable.ic_destination_flag)
                    tvSegmentTitle.text = "DESTINATION"
                    tvSegmentTitle.setTextColor(ContextCompat.getColor(context, R.color.indicator_ready))
                    tvSegmentSubtitle.text = "Target destination reached"
                }
            }

            tvSegmentConfidence.text = "${segment.confidence}%"
        }
    }
}
