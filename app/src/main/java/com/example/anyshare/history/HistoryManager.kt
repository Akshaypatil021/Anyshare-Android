package com.example.anyshare.history

import android.content.Context
import android.content.SharedPreferences
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
    val fileUriString: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("mimeType", mimeType)
        put("isSender", isSender)
        put("timestamp", timestamp)
        put("status", status)
        put("fileUriString", fileUriString ?: "")
    }

    companion object {
        fun fromJson(json: JSONObject): HistoryItem = HistoryItem(
            id = json.optString("id", ""),
            fileName = json.optString("fileName", "Unknown File"),
            fileSize = json.optLong("fileSize", 0L),
            mimeType = json.optString("mimeType", "*/*"),
            isSender = json.optBoolean("isSender", false),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            status = json.optString("status", "SUCCESS"),
            fileUriString = json.optString("fileUriString").takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * Manages persistent storage of file transfer history using SharedPreferences (JSON).
 */
class HistoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("anyshare_transfer_history", Context.MODE_PRIVATE)

    fun addHistoryItem(item: HistoryItem) {
        val list = getHistory().toMutableList()
        list.add(0, item) // Add newest at top
        saveHistory(list)
    }

    fun getHistory(): List<HistoryItem> {
        val jsonString = prefs.getString("history_items", "[]") ?: "[]"
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until jsonArray.length()) {
                list.add(HistoryItem.fromJson(jsonArray.getJSONObject(i)))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearHistory() {
        prefs.edit().remove("history_items").apply()
    }

    private fun saveHistory(list: List<HistoryItem>) {
        val jsonArray = JSONArray()
        list.take(100).forEach { jsonArray.put(it.toJson()) } // Keep up to 100 recent transfers
        prefs.edit().putString("history_items", jsonArray.toString()).apply()
    }
}
