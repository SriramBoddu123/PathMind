package com.example.pathmind.model

import org.json.JSONObject
import java.util.UUID

/**
 * Stage 4 Place Memory model.
 * Anchors a meaningful place to a relative position within a learned route.
 * Strictly avoids GPS coordinates; uses personal spatial memory.
 */
data class PlaceMemory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val routeId: String,
    val segmentId: String,
    val stepPosition: Int = 0,
    val confidence: Int = 90,
    val description: String? = null,
    val photoPath: String? = null // Stage 9: Local relative or absolute path to landmark photo
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("createdAt", createdAt)
            put("routeId", routeId)
            put("segmentId", segmentId)
            put("stepPosition", stepPosition)
            put("confidence", confidence)
            if (description != null) {
                put("description", description)
            }
            if (photoPath != null) {
                put("photoPath", photoPath)
            }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PlaceMemory {
            return PlaceMemory(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Untitled Place"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                routeId = json.optString("routeId", ""),
                segmentId = json.optString("segmentId", ""),
                stepPosition = json.optInt("stepPosition", 0),
                confidence = json.optInt("confidence", 90),
                description = if (json.has("description") && !json.isNull("description")) json.optString("description") else null,
                photoPath = if (json.has("photoPath") && !json.isNull("photoPath")) json.optString("photoPath") else null
            )
        }
    }
}
