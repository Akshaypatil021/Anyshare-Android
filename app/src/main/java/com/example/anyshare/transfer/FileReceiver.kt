package com.example.anyshare.transfer

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.anyshare.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.min

/**
 * High-speed FileReceiver supporting single and multi-file batch transfers,
 * storage pre-validation, public storage export via StorageManager,
 * and dual mode (Group Owner ServerSocket / Client connect) support.
 */
class FileReceiver(
    private val context: Context,
    private val listenPort: Int = 8888,
    private val isGroupOwner: Boolean = true,
    private val targetHost: String = "192.168.49.1"
) {
    companion object {
        private const val TAG = "FileReceiver"
    }

    @Volatile
    private var isCancelled = false
    private var currentServerSocket: ServerSocket? = null
    private var currentClientSocket: Socket? = null

    /**
     * Cancel the active receive operation immediately.
     */
    fun cancel() {
        isCancelled = true
        try { currentClientSocket?.close() } catch (_: Exception) {}
        try { currentServerSocket?.close() } catch (_: Exception) {}
    }

    /**
     * Starts listening for sender connection or connects to sender, and streams incoming files.
     */
    suspend fun startServerAndReceive(
        onHeaderReceived: (FileMetadata) -> Boolean = { true },
        onProgress: (TransferProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false
        var serverSocket: ServerSocket? = null
        var clientSocket: Socket? = null
        var lastCompletedFile: File? = null

        try {
            Log.d(TAG, "Starting receiver session: listenPort=$listenPort, isGroupOwner=$isGroupOwner, targetHost=$targetHost")
            onProgress(
                TransferProgress(
                    fileName = "Waiting for sender...",
                    bytesTransferred = 0L,
                    totalBytes = 0L,
                    progressPercentage = 0,
                    speedBytesPerSec = 0.0,
                    speedFormatted = "-- MB/s",
                    etaSeconds = 0L,
                    etaFormatted = if (isGroupOwner) "Listening on port $listenPort" else "Connecting...",
                    status = TransferStatus.Connecting
                )
            )

            val optimalSocketBuffer = com.example.anyshare.utils.HardwareCapabilityHelper.getOptimalSocketBufferSize(context)
            Log.d(TAG, "Dynamic hardware tuning: socketBuffer=$optimalSocketBuffer bytes, profile=${com.example.anyshare.utils.HardwareCapabilityHelper.getHardwareSummary(context)}")

            // 1. Establish High-Throughput TCP Socket
            if (isGroupOwner) {
                // Receiver is Group Owner (192.168.49.1): bind ServerSocket and accept Client
                Log.d(TAG, "Receiver is Group Owner. Binding ServerSocket on port $listenPort...")
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 90_000 // 90s wait for sender connection
                    receiveBufferSize = optimalSocketBuffer
                    bind(InetSocketAddress(listenPort))
                }
                currentServerSocket = serverSocket
                Log.d(TAG, "ServerSocket bound on $listenPort. Waiting for sender client...")

                clientSocket = serverSocket.accept().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    receiveBufferSize = optimalSocketBuffer
                    sendBufferSize = optimalSocketBuffer
                    soTimeout = TransferConfig.SOCKET_TIMEOUT_MS
                }
                Log.d(TAG, "Sender client connected from ${clientSocket.inetAddress}")
            } else {
                // Receiver is Client: connect to GO at targetHost:listenPort with up to 25 retries
                Log.d(TAG, "Receiver is Client. Connecting to sender GO at $targetHost:$listenPort...")

                // Poll for P2P local IP assignment
                var localP2pIp = P2pSocketHelper.findLocalP2pAddress()
                var dhcpTries = 0
                while (localP2pIp == null && dhcpTries < 8 && !isCancelled) {
                    Thread.sleep(500)
                    localP2pIp = P2pSocketHelper.findLocalP2pAddress()
                    dhcpTries++
                }

                var connectedSocket: Socket? = null
                var lastErr: Exception? = null

                for (attempt in 1..25) {
                    if (isCancelled) break
                    var attemptSocket: Socket? = null
                    try {
                        onProgress(
                            TransferProgress(
                                fileName = "Connecting to sender...",
                                bytesTransferred = 0L,
                                totalBytes = 0L,
                                progressPercentage = 0,
                                speedBytesPerSec = 0.0,
                                speedFormatted = "-- MB/s",
                                etaSeconds = 0L,
                                etaFormatted = "Attempt $attempt/25",
                                status = TransferStatus.Connecting
                            )
                        )

                        val s = Socket().apply {
                            tcpNoDelay = true
                            keepAlive = true
                            receiveBufferSize = optimalSocketBuffer
                            sendBufferSize = optimalSocketBuffer
                            soTimeout = TransferConfig.SOCKET_TIMEOUT_MS
                        }
                        attemptSocket = s

                        // Bind socket to P2P network
                        P2pSocketHelper.bindSocketToP2pNetwork(context, s)

                        if (localP2pIp != null && !s.isBound) {
                            try {
                                s.bind(InetSocketAddress(localP2pIp, 0))
                            } catch (_: Exception) {}
                        }

                        Log.d(TAG, "Connecting to sender (attempt $attempt/25)...")
                        s.connect(InetSocketAddress(targetHost, listenPort), 3_000)
                        connectedSocket = s
                        Log.d(TAG, "Connected to sender on attempt $attempt!")
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        try { attemptSocket?.close() } catch (_: Exception) {}
                        Log.w(TAG, "Attempt $attempt failed: ${e.message}. Retrying in 500ms...")
                        Thread.sleep(500)
                        if (localP2pIp == null) {
                            localP2pIp = P2pSocketHelper.findLocalP2pAddress()
                        }
                    }
                }
                clientSocket = connectedSocket ?: throw (lastErr ?: IOException("Failed to connect to sender at $targetHost:$listenPort after 25 attempts"))
            }
            currentClientSocket = clientSocket

            val inStream = DataInputStream(BufferedInputStream(clientSocket.getInputStream(), TransferConfig.SOCKET_BUFFER_SIZE))
            val outStream = DataOutputStream(BufferedOutputStream(clientSocket.getOutputStream()))

            // 2. Validate Protocol Header
            val magic = inStream.readUTF()
            Log.d(TAG, "Received protocol magic: $magic")

            val destinationDir = File(context.getExternalFilesDir(null), "Received").apply {
                if (!exists()) mkdirs()
            }

            if (magic == TransferConfig.PROTOCOL_MAGIC_V2) {
                val totalFiles = inStream.readInt()
                Log.d(TAG, "Protocol V2 session confirmed. Incoming files count: $totalFiles")

                outStream.writeUTF("ACK_SESSION_READY")
                outStream.flush()

                for (fileIndex in 0 until totalFiles) {
                    if (isCancelled) break

                    val fileName = inStream.readUTF()
                    val fileSize = inStream.readLong()
                    val mimeType = inStream.readUTF()
                    val chunkSize = inStream.readInt().coerceIn(TransferConfig.MIN_CHUNK_SIZE, TransferConfig.MAX_CHUNK_SIZE)

                    val metadata = FileMetadata(
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType,
                        chunkSize = chunkSize
                    )

                    val displayName = if (totalFiles > 1) {
                        "$fileName (${fileIndex + 1}/$totalFiles)"
                    } else {
                        fileName
                    }

                    Log.d(TAG, "Receiving file [$fileIndex]: $fileName ($fileSize bytes)")

                    // Storage check
                    val availableSpace = getAvailableStorageBytes(destinationDir)
                    if (fileSize > 0 && availableSpace < fileSize + (10 * 1024 * 1024)) {
                        outStream.writeUTF("ERR_STORAGE_FULL")
                        outStream.flush()
                        throw IllegalStateException("Insufficient storage on receiver! Required: $fileSize, Available: $availableSpace")
                    }

                    if (!onHeaderReceived(metadata)) {
                        outStream.writeUTF("DECLINE")
                        outStream.flush()
                        return@withContext Result.failure(Exception("File transfer declined by user"))
                    }

                    // Acknowledge file readiness to sender
                    outStream.writeUTF("ACK_READY")
                    outStream.flush()

                    val outputFile = File(destinationDir, sanitizeFileName(fileName))
                    var fileOutputStream: FileOutputStream? = null

                    try {
                        fileOutputStream = FileOutputStream(outputFile)
                        val bufferedOutput = BufferedOutputStream(fileOutputStream, chunkSize)
                        val metricsCalculator = TransferMetricsCalculator(fileSize, displayName)
                        val buffer = ByteArray(chunkSize)
                        var bytesReceived = 0L
                        var lastProgressEmitTime = System.currentTimeMillis()

                        onProgress(metricsCalculator.update(0L, TransferStatus.InProgress))

                        while (!isCancelled && bytesReceived < fileSize) {
                            val toRead = min(buffer.size.toLong(), fileSize - bytesReceived).toInt()
                            val read = inStream.read(buffer, 0, toRead)
                            if (read == -1) break

                            bufferedOutput.write(buffer, 0, read)
                            bytesReceived += read

                            val now = System.currentTimeMillis()
                            if (now - lastProgressEmitTime >= 100 || bytesReceived == fileSize) {
                                onProgress(metricsCalculator.update(bytesReceived, TransferStatus.InProgress))
                                lastProgressEmitTime = now
                            }
                        }

                        if (isCancelled) {
                            outputFile.delete()
                            onProgress(metricsCalculator.update(bytesReceived, TransferStatus.Cancelled))
                            return@withContext Result.failure(Exception("Transfer cancelled by user"))
                        }

                        bufferedOutput.flush()

                        if (fileSize > 0 && bytesReceived != fileSize) {
                            outputFile.delete()
                            throw IllegalStateException("Connection dropped: received $bytesReceived / $fileSize bytes")
                        }

                        // Send completion ACK to sender
                        outStream.writeUTF("ACK_COMPLETED")
                        outStream.flush()
                        Log.d(TAG, "File $fileName successfully received and verified.")

                        // Export received file to permanent public Downloads/Anyshare folder
                        val publicUri = StorageManager.saveFileToPublicDownloads(context, outputFile, mimeType)
                        Log.d(TAG, "File saved to public storage. Public Uri: $publicUri")

                        lastCompletedFile = outputFile
                        onProgress(metricsCalculator.update(bytesReceived, TransferStatus.Completed(outputFile.absolutePath)))

                    } finally {
                        try { fileOutputStream?.close() } catch (_: Exception) {}
                    }
                }

                if (isCancelled) {
                    Result.failure(Exception("Transfer cancelled by user"))
                } else {
                    Log.d(TAG, "All $totalFiles file(s) received successfully!")
                    Result.success(lastCompletedFile ?: File(destinationDir, "received_files"))
                }

            } else if (magic == TransferConfig.PROTOCOL_MAGIC) {
                // V1 Protocol fallback for backwards compatibility
                val fileName = inStream.readUTF()
                val fileSize = inStream.readLong()
                val mimeType = inStream.readUTF()
                val chunkSize = inStream.readInt().coerceIn(TransferConfig.MIN_CHUNK_SIZE, TransferConfig.MAX_CHUNK_SIZE)

                val metadata = FileMetadata(fileName = fileName, fileSize = fileSize, mimeType = mimeType, chunkSize = chunkSize)

                val availableSpace = getAvailableStorageBytes(destinationDir)
                if (fileSize > 0 && availableSpace < fileSize + (10 * 1024 * 1024)) {
                    outStream.writeUTF("ERR_STORAGE_FULL")
                    outStream.flush()
                    throw IllegalStateException("Insufficient storage on receiver")
                }

                outStream.writeUTF("ACK_READY")
                outStream.flush()

                val outputFile = File(destinationDir, sanitizeFileName(fileName))
                val fileOutputStream = FileOutputStream(outputFile)
                val bufferedOutput = BufferedOutputStream(fileOutputStream, chunkSize)
                val metricsCalculator = TransferMetricsCalculator(fileSize, fileName)
                val buffer = ByteArray(chunkSize)
                var bytesReceived = 0L
                var lastProgressEmitTime = System.currentTimeMillis()

                try {
                    while (!isCancelled && bytesReceived < fileSize) {
                        val toRead = min(buffer.size.toLong(), fileSize - bytesReceived).toInt()
                        val read = inStream.read(buffer, 0, toRead)
                        if (read == -1) break

                        bufferedOutput.write(buffer, 0, read)
                        bytesReceived += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmitTime >= 100 || bytesReceived == fileSize) {
                            onProgress(metricsCalculator.update(bytesReceived, TransferStatus.InProgress))
                            lastProgressEmitTime = now
                        }
                    }

                    bufferedOutput.flush()

                    if (fileSize > 0 && bytesReceived != fileSize) {
                        outputFile.delete()
                        throw IllegalStateException("Connection dropped: received $bytesReceived / $fileSize bytes")
                    }

                    outStream.writeUTF("ACK_COMPLETED")
                    outStream.flush()

                    StorageManager.saveFileToPublicDownloads(context, outputFile, mimeType)
                    onProgress(metricsCalculator.update(bytesReceived, TransferStatus.Completed(outputFile.absolutePath)))
                    Result.success(outputFile)
                } finally {
                    try { fileOutputStream.close() } catch (_: Exception) {}
                }

            } else {
                outStream.writeUTF("ERR_INVALID_PROTOCOL")
                outStream.flush()
                throw IllegalArgumentException("Invalid sender protocol: $magic")
            }

        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "SocketTimeoutException in startServerAndReceive: ${e.message}", e)
            val err = "Connection timed out. Partner device may have disconnected or walked out of range."
            try { onProgress(TransferProgress(fileName = "Error", bytesTransferred = 0, totalBytes = 0, progressPercentage = 0, speedBytesPerSec = 0.0, speedFormatted = "-- MB/s", etaSeconds = 0, etaFormatted = "Failed", status = TransferStatus.Error(err))) } catch (_: Exception) {}
            Result.failure(Exception(err))
        } catch (e: Exception) {
            Log.e(TAG, "Exception in startServerAndReceive: ${e.message}", e)
            val err = if (isCancelled) "Transfer cancelled by user" else (e.message ?: "Transfer error")
            try { onProgress(TransferProgress(fileName = "Error", bytesTransferred = 0, totalBytes = 0, progressPercentage = 0, speedBytesPerSec = 0.0, speedFormatted = "-- MB/s", etaSeconds = 0, etaFormatted = "Failed", status = TransferStatus.Error(err))) } catch (_: Exception) {}
            if (isCancelled) {
                Result.failure(Exception("Transfer cancelled by user"))
            } else {
                Result.failure(e)
            }
        } finally {
            try { clientSocket?.close() } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
            currentClientSocket = null
            currentServerSocket = null
        }
    }

    private fun getAvailableStorageBytes(directory: File): Long {
        return try {
            val statFs = StatFs(directory.path)
            statFs.availableBlocksLong * statFs.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }
}
