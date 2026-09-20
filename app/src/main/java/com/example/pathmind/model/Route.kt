package com.example.pathmind.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SegmentAction {
    START,
    WALK,
    TURN,
    PAUSE,
    DESTINATION
}

enum class SegmentDirection {
    STRAIGHT,
    LEFT,
    RIGHT,
    NONE,
    UNKNOWN
}

data class RouteSegment(
    val id: String = UUID.randomUUID().toString(),
    val action: SegmentAction = SegmentAction.WALK,
    val direction: SegmentDirection = SegmentDirection.STRAIGHT,
    val steps: Int = 0,
    val duration: Long = 0L, // in milliseconds
    val confidence: Int = 85
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("action", action.name)
            put("direction", direction.name)
            put("steps", steps)
            put("duration", duration)
            put("confidence", confidence)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): RouteSegment {
            return RouteSegment(
                id = json.optString("id", UUID.randomUUID().toString()),
                action = try { SegmentAction.valueOf(json.optString("action", "WALK")) } catch (_: Exception) { SegmentAction.WALK },
                direction = try { SegmentDirection.valueOf(json.optString("direction", "STRAIGHT")) } catch (_: Exception) { SegmentDirection.STRAIGHT },
                steps = json.optInt("steps", 0),
                duration = json.optLong("duration", 0L),
                confidence = json.optInt("confidence", 85)
            )
        }
    }
}

data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val totalSteps: Int,
    val totalSegments: Int,
    val segments: List<RouteSegment>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("createdAt", createdAt)
            put("totalSteps", totalSteps)
            put("totalSegments", totalSegments)
            val segmentsArray = JSONArray()
            segments.forEach { segmentsArray.put(it.toJson()) }
            put("segments", segmentsArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Route {
            val segmentsList = ArrayList<RouteSegment>()
            val segmentsArray = json.optJSONArray("segments")
            if (segmentsArray != null) {
                for (i in 0 until segmentsArray.length()) {
                    val segObj = segmentsArray.optJSONObject(i)
                    if (segObj != null) {
                        segmentsList.add(RouteSegment.fromJson(segObj))
                    }
                }
            }
            return Route(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Untitled Route"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                totalSteps = json.optInt("totalSteps", 0),
                totalSegments = json.optInt("totalSegments", segmentsList.size),
                segments = segmentsList
            )
        }
    }
}
