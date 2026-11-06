package com.example.anyshare.history

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.anyshare.data.local.AppDatabase
import com.example.anyshare.data.local.HistoryDao
import com.example.anyshare.data.local.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single file transfer record in the history log.
 */
data class HistoryItem(
    val id: String = System.currentTimeMillis().toString(),
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isSender: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SUCCESS",
    val fileUriString: String? = null,
    val category: String = HistoryEntity.resolveCategory(fileName, mimeType),
    val dbId: Long = 0L
) {
    fun toEntity(): HistoryEntity = HistoryEntity(
        id = dbId,
        fileName = fileName,
        fileSize = fileSize,
        mimeType = mimeType,
        isSender = isSender,
        timestamp = timestamp,
        status = status,
        fileUriString = fileUriString,
        category = category
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("mimeType", mimeType)
        put("isSender", isSender)
        put("timestamp", timestamp)
        put("status", status)
        put("fileUriString", fileUriString ?: "")
        put("category", category)
    }

    companion object {
        fun fromEntity(entity: HistoryEntity): HistoryItem = HistoryItem(
            id = entity.id.toString(),
            fileName = entity.fileName,
            fileSize = entity.fileSize,
            mimeType = entity.mimeType,
            isSender = entity.isSender,
            timestamp = entity.timestamp,
            status = entity.status,
            fileUriString = entity.fileUriString,
            category = entity.category,
            dbId = entity.id
        )

        fun fromJson(json: JSONObject): HistoryItem {
            val name = json.optString("fileName", "Unknown File")
            val mime = json.optString("mimeType", "*/*")
            val cat = json.optString("category").takeIf { it.isNotEmpty() }
                ?: HistoryEntity.resolveCategory(name, mime)
            return HistoryItem(
                id = json.optString("id", ""),
                fileName = name,
                fileSize = json.optLong("fileSize", 0L),
                mimeType = mime,
                isSender = json.optBoolean("isSender", false),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                status = json.optString("status", "SUCCESS"),
                fileUriString = json.optString("fileUriString").takeIf { it.isNotEmpty() },
                category = cat
            )
        }
    }
}

/**
 * Manages persistent storage of file transfer history using Google Room Database
 * with fallback & seamless one-time migration from legacy SharedPreferences.
 */
class HistoryManager(context: Context) {

    private val dao: HistoryDao = AppDatabase.getDatabase(context).historyDao()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("anyshare_transfer_history", Context.MODE_PRIVATE)

    init {
        migrateLegacySharedPreferencesIfNeeded()
    }

    private fun migrateLegacySharedPreferencesIfNeeded() {
        val isMigrated = prefs.getBoolean("is_migrated_to_room", false)
        if (!isMigrated) {
            val jsonString = prefs.getString("history_items", null)
            if (!jsonString.isNullOrEmpty()) {
                scope.launch {
                    try {
                        val jsonArray = JSONArray(jsonString)
                        val entities = mutableListOf<HistoryEntity>()
                        for (i in 0 until jsonArray.length()) {
                            val item = HistoryItem.fromJson(jsonArray.getJSONObject(i))
                            entities.add(item.toEntity())
                        }
                        if (entities.isNotEmpty()) {
                            dao.insertAll(entities)
                            Log.d("HistoryManager", "Successfully migrated ${entities.size} items to Room DB")
                        }
                        prefs.edit().putBoolean("is_migrated_to_room", true).apply()
                    } catch (e: Exception) {
                        Log.e("HistoryManager", "Migration error: ${e.message}")
                    }
                }
            } else {
                prefs.edit().putBoolean("is_migrated_to_room", true).apply()
            }
        }
    }

    /**
     * Add a completed file transfer to Room Database asynchronously.
     */
    fun addHistoryItem(item: HistoryItem) {
        scope.launch {
            try {
                dao.insert(item.toEntity())
            } catch (e: Exception) {
                Log.e("HistoryManager", "Error inserting history item: ${e.message}")
            }
        }
    }

    /**
     * Reactive Flow of history items supporting category filtering and text search.
     */
    fun getHistoryFlow(category: String = HistoryEntity.CATEGORY_ALL, searchQuery: String = ""): Flow<List<HistoryItem>> {
        val flow = when {
            searchQuery.isNotBlank() && category != HistoryEntity.CATEGORY_ALL ->
                dao.searchByCategory(category, searchQuery.trim())
            searchQuery.isNotBlank() ->
                dao.searchAll(searchQuery.trim())
            category != HistoryEntity.CATEGORY_ALL ->
                dao.getHistoryByCategory(category)
            else ->
                dao.getAllHistory()
        }
        return flow.map { list -> list.map { HistoryItem.fromEntity(it) } }
    }

    /**
     * Clear all history records from Room Database.
     */
    fun clearHistory(onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                dao.clearAll()
                prefs.edit().remove("history_items").apply()
            } catch (e: Exception) {
                Log.e("HistoryManager", "Error clearing history: ${e.message}")
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Delete a single history record by its Room Primary Key.
     */
    fun deleteItem(dbId: Long) {
        scope.launch {
            try {
                dao.deleteById(dbId)
            } catch (e: Exception) {
                Log.e("HistoryManager", "Error deleting item $dbId: ${e.message}")
            }
        }
    }

    /**
     * Synchronous retrieval for backwards compatibility.
     */
    fun getHistory(): List<HistoryItem> = runBlocking(Dispatchers.IO) {
        try {
            dao.getAllHistoryList().map { HistoryItem.fromEntity(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Live counts of total files and total transferred bytes.
     */
    fun getSummaryStats(): Flow<Pair<Int, Long>> {
        return dao.getTotalCount().combine(dao.getTotalBytes()) { count, bytes ->
            Pair(count, bytes ?: 0L)
        }
    }
}
