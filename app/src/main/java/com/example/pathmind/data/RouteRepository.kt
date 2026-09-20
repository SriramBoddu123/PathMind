package com.example.pathmind.data

import android.content.Context
import com.example.pathmind.model.Route
import org.json.JSONArray
import java.io.File

class RouteRepository(private val context: Context) {

    private val routesFile: File
        get() = File(context.filesDir, "saved_routes.json")

    @Synchronized
    fun getRoutes(): List<Route> {
        val file = routesFile
        if (!file.exists()) return emptyList()

        return try {
            val jsonString = file.readText()
            if (jsonString.isBlank()) return emptyList()

            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<Route>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i)
                if (obj != null) {
                    list.add(Route.fromJson(obj))
                }
            }
            // Sort by most recently created
            list.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun getRouteById(id: String): Route? {
        return getRoutes().find { it.id == id }
    }

    @Synchronized
    fun saveRoute(route: Route): Boolean {
        return try {
            val currentRoutes = getRoutes().toMutableList()
            // Replace if exists, or append
            val index = currentRoutes.indexOfFirst { it.id == route.id }
            if (index >= 0) {
                currentRoutes[index] = route
            } else {
                currentRoutes.add(0, route)
            }

            val jsonArray = JSONArray()
            for (r in currentRoutes) {
                jsonArray.put(r.toJson())
            }

            routesFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    fun deleteRoute(id: String): Boolean {
        return try {
            val currentRoutes = getRoutes().filter { it.id != id }
            val jsonArray = JSONArray()
            for (r in currentRoutes) {
                jsonArray.put(r.toJson())
            }
            routesFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
