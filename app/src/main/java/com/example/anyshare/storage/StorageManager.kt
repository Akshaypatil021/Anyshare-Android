package com.example.anyshare.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * StorageManager handles Scoped Storage and MediaStore operations across Android 10 to 14/15+.
 * Saves incoming received files directly into the public Downloads/Anyshare folder.
 */
object StorageManager {

    private const val ANYSHARE_SUBFOLDER = "Anyshare"

    /**
     * Moves a temporarily received file into the user's permanent Downloads/Anyshare directory.
     *
     * @param context Application context.
     * @param sourceFile The temp file written by FileReceiver.
     * @param mimeType MIME type of the file.
     * @return Uri pointing to the permanent public file, or null on error.
     */
    fun saveFileToPublicDownloads(
        context: Context,
        sourceFile: File,
        mimeType: String
    ): Uri? {
        val fileName = sourceFile.name

        // Check if user specified a custom destination folder in Settings
        val customUriString = com.example.anyshare.utils.DeviceConfigManager(context).getDestinationUri()
        if (customUriString != null) {
            try {
                val treeUri = Uri.parse(customUriString)
                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val targetDirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val newDocUri = android.provider.DocumentsContract.createDocument(
                    context.contentResolver,
                    targetDirUri,
                    mimeType,
                    fileName
                )
                if (newDocUri != null) {
                    context.contentResolver.openOutputStream(newDocUri)?.use { outStream ->
                        FileInputStream(sourceFile).use { inStream ->
                            inStream.copyTo(outStream, bufferSize = 512 * 1024)
                        }
                    }
                    sourceFile.delete()
                    return newDocUri
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+): Scoped Storage via MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + ANYSHARE_SUBFOLDER
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1) // Hidden while writing
            }

            val resolver = context.contentResolver
            val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null

            try {
                resolver.openOutputStream(targetUri)?.use { outStream ->
                    FileInputStream(sourceFile).use { inStream ->
                        val buffer = ByteArray(512 * 1024) // 512 KB copy buffer
                        var bytesRead: Int
                        while (inStream.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                        }
                        outStream.flush()
                    }
                }

                // Mark file as complete and visible to system gallery/file manager
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(targetUri, contentValues, null, null)

                // Remove temp file
                sourceFile.delete()
                targetUri
            } catch (e: Exception) {
                resolver.delete(targetUri, null, null)
                e.printStackTrace()
                null
            }
        } else {
            // Android 9 and below: Legacy file system
            try {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    ANYSHARE_SUBFOLDER
                ).apply { if (!exists()) mkdirs() }

                val destinationFile = File(publicDir, fileName)
                FileInputStream(sourceFile).use { inStream ->
                    FileOutputStream(destinationFile).use { outStream ->
                        inStream.copyTo(outStream, bufferSize = 512 * 1024)
                    }
                }
                sourceFile.delete()

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    destinationFile
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Launch intent to open and view the saved file in an external viewer.
     */
    fun openFile(context: Context, fileUri: Uri, mimeType: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open file with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Launch intent to open Downloads folder in the system file manager.
     */
    fun openDownloadsFolder(context: Context): Boolean {
        return try {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(downloadsDir.path), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (ex: Exception) {
                ex.printStackTrace()
                false
            }
        }
    }
}
