package com.example.anyshare.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SQLite Entity representing a single file transfer record in the Room database.
 */
@Entity(
    tableName = "transfer_history",
    indices = [Index(value = ["category"]), Index(value = ["timestamp"])]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isSender: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SUCCESS",
    val fileUriString: String? = null,
    val category: String = CATEGORY_OTHER
) {
    companion object {
        const val CATEGORY_ALL = "ALL"
        const val CATEGORY_MEDIA = "MEDIA"
        const val CATEGORY_DOCUMENT = "DOCUMENT"
        const val CATEGORY_APP = "APP"
        const val CATEGORY_NOTE = "NOTE"
        const val CATEGORY_OTHER = "OTHER"

        /**
         * Automatically categorizes a file based on its filename extension and MIME type.
         */
        fun resolveCategory(fileName: String, mimeType: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mime = mimeType.lowercase()

            // 1. Text Note
            if (fileName.startsWith("note_") && (ext == "txt" || mime == "text/plain")) {
                return CATEGORY_NOTE
            }

            // 2. Apps (APKs)
            if (ext == "apk" || mime.contains("android.package-archive")) {
                return CATEGORY_APP
            }

            // 3. Media (Images, Videos, Music)
            if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ||
                ext in listOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "mkv", "avi", "mov", "mp3", "wav", "m4a", "flac")
            ) {
                return CATEGORY_MEDIA
            }

            // 4. Documents & Archives
            if (mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") ||
                mime.contains("sheet") || mime.contains("presentation") || mime.contains("zip") ||
                mime.contains("tar") || mime.contains("compressed") ||
                ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z", "tar", "gz")
            ) {
                return CATEGORY_DOCUMENT
            }

            return CATEGORY_OTHER
        }
    }
}
