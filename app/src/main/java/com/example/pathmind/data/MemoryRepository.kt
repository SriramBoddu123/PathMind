package com.example.pathmind.data

import android.content.Context
import com.example.pathmind.model.PlaceMemory
import org.json.JSONArray
import java.io.File

class MemoryRepository(private val context: Context) {

    private val memoriesFile: File
        get() = File(context.filesDir, "saved_memories.json")

    @Synchronized
    fun getAllMemories(): List<PlaceMemory> {
        val file = memoriesFile
        if (!file.exists()) return emptyList()

        return try {
            val jsonString = file.readText()
            if (jsonString.isBlank()) return emptyList()

            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<PlaceMemory>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i)
                if (obj != null) {
                    list.add(PlaceMemory.fromJson(obj))
                }
            }
            list.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun getMemoryById(id: String): PlaceMemory? {
        return getAllMemories().find { it.id == id }
    }

    @Synchronized
    fun getMemoriesForRoute(routeId: String): List<PlaceMemory> {
        return getAllMemories().filter { it.routeId == routeId }
    }

    @Synchronized
    fun saveMemory(memory: PlaceMemory): Boolean {
        return try {
            val current = getAllMemories().toMutableList()
            val index = current.indexOfFirst { it.id == memory.id }
            if (index >= 0) {
                current[index] = memory
            } else {
                current.add(0, memory)
            }

            val jsonArray = JSONArray()
            for (m in current) {
                jsonArray.put(m.toJson())
            }

            memoriesFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    fun updateMemory(memory: PlaceMemory): Boolean {
        return saveMemory(memory)
    }

    @Synchronized
    fun deleteMemory(id: String): Boolean {
        return try {
            val all = getAllMemories()
            val target = all.find { it.id == id }
            if (target?.photoPath != null) {
                com.example.pathmind.util.LandmarkImageManager.deleteLandmarkImage(target.photoPath)
            }

            val current = all.filter { it.id != id }
            val jsonArray = JSONArray()
            for (m in current) {
                jsonArray.put(m.toJson())
            }
            memoriesFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    fun deleteMemoriesForRoute(routeId: String): Boolean {
        return try {
            val all = getAllMemories()
            all.filter { it.routeId == routeId }.forEach { mem ->
                if (mem.photoPath != null) {
                    com.example.pathmind.util.LandmarkImageManager.deleteLandmarkImage(mem.photoPath)
                }
            }

            val current = all.filter { it.routeId != routeId }
            val jsonArray = JSONArray()
            for (m in current) {
                jsonArray.put(m.toJson())
            }
            memoriesFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
