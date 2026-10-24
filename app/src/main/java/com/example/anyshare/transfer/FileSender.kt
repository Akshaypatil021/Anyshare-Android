package com.example.anyshare.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * FileSender streams one or more files over TCP Sockets using configurable 512KB to 1MB chunks.
 * Seamlessly functions in both Group Owner mode (ServerSocket.accept) and Client mode (Socket.connect).
 */
class FileSender(
    private val context: Context,
    private val targetHost: String,
    private val targetPort: Int = 8888,
    private val isGroupOwner: Boolean = false,
    private val preferredChunkSize: Int = TransferConfig.DEFAULT_CHUNK_SIZE
) {
    companion object {
        private const val TAG = "FileSender"
    }

    @Volatile
    private var isCancelled = false
    private var currentSocket: Socket? = null
    private var currentServerSocket: ServerSocket? = null

    /**
     * Cancel the active transfer immediately.
     */
    fun cancel() {
        isCancelled = true
        try {
            currentSocket?.close()
        } catch (_: Exception) {}
        try {
            currentServerSocket?.close()
        } catch (_: Exception) {}
    }

    /**
     * Single file convenience wrapper.
     */
    suspend fun sendFile(
        fileUri: Uri,
        onProgress: (TransferProgress) -> Unit
    ): Result<Unit> = sendFiles(listOf(fileUri), onProgress)

    /**
     * Streams [fileUris] to target receiver on [Dispatchers.IO].
     */
    suspend fun sendFiles(
        fileUris: List<Uri>,
        onProgress: (TransferProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (fileUris.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No files selected to send"))
        }

        isCancelled = false
        var socket: Socket? = null
        var serverSocket: ServerSocket? = null
        var metricsCalculator: TransferMetricsCalculator? = null

        try {
            Log.d(TAG, "Starting send session for ${fileUris.size} file(s). targetHost=$targetHost:$targetPort, isGroupOwner=$isGroupOwner")

            // 1. Resolve metadata for all files and compute total size
            val metadataList = fileUris.map { resolveFileMetadata(it) }
            val totalBytesAllFiles = metadataList.sumOf { it.fileSize }
            val firstMeta = metadataList.first()

            metricsCalculator = TransferMetricsCalculator(totalBytesAllFiles, firstMeta.fileName)
            onProgress(
                metricsCalculator.update(0L, TransferStatus.Connecting).copy(
                    etaFormatted = "Connecting to partner..."
                )
            )

            // 2. Establish High-Throughput TCP Socket
            if (isGroupOwner) {
                // Sender is Group Owner (192.168.49.1): open ServerSocket and accept Client
                Log.d(TAG, "Sender is Group Owner. Binding ServerSocket on port $targetPort...")
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 60_000 // 60s timeout for receiver to connect
                    receiveBufferSize = TransferConfig.SOCKET_BUFFER_SIZE
                    bind(InetSocketAddress(targetPort))
                }
                currentServerSocket = serverSocket
                Log.d(TAG, "ServerSocket bound on $targetPort. Waiting for receiver to connect...")

                socket = serverSocket.accept().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    sendBufferSize = TransferConfig.SOCKET_BUFFER_SIZE
                    receiveBufferSize = TransferConfig.SOCKET_BUFFER_SIZE
                    soTimeout = TransferConfig.SOCKET_TIMEOUT_MS
                }
                Log.d(TAG, "Receiver connected from ${socket.inetAddress}")
            } else {
                // Sender is Client: connect to GO at targetHost:targetPort with DHCP stabilization
                Log.d(TAG, "Sender is Client. Connecting to receiver at $targetHost:$targetPort...")

                // Step 2a: Poll for P2P local IP assignment (up to 4s) so DHCP IP is valid
                var localP2pIp = P2pSocketHelper.findLocalP2pAddress()
                var dhcpTries = 0
                while (localP2pIp == null && dhcpTries < 8 && !isCancelled) {
                    Thread.sleep(500)
                    localP2pIp = P2pSocketHelper.findLocalP2pAddress()
                    dhcpTries++
                }
                Log.d(TAG, "Local P2P IP determined: $localP2pIp")

                var connectedSocket: Socket? = null
                var lastErr: Exception? = null

                for (attempt in 1..25) {
                    if (isCancelled) break
                    var attemptSocket: Socket? = null
                    try {
                        onProgress(
                            metricsCalculator.update(0L, TransferStatus.Connecting).copy(
                                etaFormatted = "Attempt $attempt/25"
                            )
                        )

                        val s = Socket().apply {
                            tcpNoDelay = true
                            keepAlive = true
                            sendBufferSize = TransferConfig.SOCKET_BUFFER_SIZE
                            receiveBufferSize = TransferConfig.SOCKET_BUFFER_SIZE
                            soTimeout = TransferConfig.SOCKET_TIMEOUT_MS
                        }
                        attemptSocket = s

                        // Force traffic over Wi-Fi Direct network via ConnectivityManager
                        P2pSocketHelper.bindSocketToP2pNetwork(context, s)

                        // Bind socket local IP to P2P subnet to bypass Home Wi-Fi / Mobile Data routing
                        if (localP2pIp != null && !s.isBound) {
                            try {
                                s.bind(InetSocketAddress(localP2pIp, 0))
                            } catch (_: Exception) {}
                        }

                        Log.d(TAG, "Connection attempt $attempt/25 to $targetHost:$targetPort...")
                        s.connect(InetSocketAddress(targetHost, targetPort), 3_000)
                        connectedSocket = s
                        Log.d(TAG, "Successfully connected to $targetHost:$targetPort on attempt $attempt!")
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        try { attemptSocket?.close() } catch (_: Exception) {}
                        Log.w(TAG, "Attempt $attempt failed: ${e.message}. Retrying in 500ms...")
                        Thread.sleep(500)
                        // Refresh local P2P IP if it wasn't ready earlier
                        if (localP2pIp == null) {
                            localP2pIp = P2pSocketHelper.findLocalP2pAddress()
                        }
                    }
                }
                socket = connectedSocket ?: throw (lastErr ?: IOException("Failed to connect to receiver at $targetHost:$targetPort after 25 attempts"))
            }
            currentSocket = socket

            onProgress(metricsCalculator.update(0L, TransferStatus.Handshaking))

            val outStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), TransferConfig.DEFAULT_CHUNK_SIZE))
            val inStream = DataInputStream(BufferedInputStream(socket.getInputStream()))

            // 3. Send Protocol Header Packet (V2 Batch Protocol)
            Log.d(TAG, "Sending PROTOCOL_MAGIC_V2 header for ${fileUris.size} files...")
            outStream.writeUTF(TransferConfig.PROTOCOL_MAGIC_V2)
            outStream.writeInt(fileUris.size)
            outStream.flush()

            val sessionAck = inStream.readUTF()
            if (sessionAck != "ACK_SESSION_READY") {
                throw IllegalStateException("Receiver rejected session handshake: $sessionAck")
            }
            Log.d(TAG, "Receiver acknowledged session: $sessionAck")

            var totalCumulativeBytesTransferred = 0L

            // 4. Stream each file sequentially
            for ((index, metadata) in metadataList.withIndex()) {
                if (isCancelled) break
                val fileUri = fileUris[index]

                val effectiveChunkSize = if (metadata.fileSize > 100 * 1024 * 1024) {
                    TransferConfig.MAX_CHUNK_SIZE
                } else {
                    preferredChunkSize.coerceIn(TransferConfig.MIN_CHUNK_SIZE, TransferConfig.MAX_CHUNK_SIZE)
                }

                val displayName = if (fileUris.size > 1) {
                    "${metadata.fileName} (${index + 1}/${fileUris.size})"
                } else {
                    metadata.fileName
                }

                Log.d(TAG, "Sending file [$index]: ${metadata.fileName}, size=${metadata.fileSize}, mime=${metadata.mimeType}")

                // Send per-file metadata header
                outStream.writeUTF(metadata.fileName)
                outStream.writeLong(metadata.fileSize)
                outStream.writeUTF(metadata.mimeType)
                outStream.writeInt(effectiveChunkSize)
                outStream.flush()

                // Wait for Receiver readiness for this file
                val fileAck = inStream.readUTF()
                if (fileAck != "ACK_READY") {
                    throw IllegalStateException("Receiver declined file ${metadata.fileName} with response: $fileAck")
                }

                // Open input stream with security handling
                var fileInputStream: InputStream? = null
                try {
                    fileInputStream = context.contentResolver.openInputStream(fileUri)
                        ?: throw IllegalArgumentException("Cannot open file stream for URI: $fileUri")

                    val bufferedFileStream = BufferedInputStream(fileInputStream, effectiveChunkSize)
                    val buffer = ByteArray(effectiveChunkSize)
                    var fileBytesTransferred = 0L
                    var lastProgressEmitTime = System.currentTimeMillis()

                    onProgress(
                        metricsCalculator.update(
                            totalCumulativeBytesTransferred,
                            TransferStatus.InProgress
                        ).copy(fileName = displayName)
                    )

                    while (!isCancelled) {
                        val bytesRead = bufferedFileStream.read(buffer, 0, buffer.size)
                        if (bytesRead == -1) break // End of current file

                        outStream.write(buffer, 0, bytesRead)
                        fileBytesTransferred += bytesRead
                        totalCumulativeBytesTransferred += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmitTime >= 100 || fileBytesTransferred == metadata.fileSize) {
                            onProgress(
                                metricsCalculator.update(
                                    totalCumulativeBytesTransferred,
                                    TransferStatus.InProgress
                                ).copy(fileName = displayName)
                            )
                            lastProgressEmitTime = now
                        }
                    }

                    if (isCancelled) {
                        onProgress(metricsCalculator.update(totalCumulativeBytesTransferred, TransferStatus.Cancelled))
                        return@withContext Result.failure(Exception("Transfer cancelled by user"))
                    }

                    outStream.flush()

                    // Await per-file completion confirmation
                    val compAck = inStream.readUTF()
                    if (compAck != "ACK_COMPLETED") {
                        throw IllegalStateException("Unexpected completion response for ${metadata.fileName}: $compAck")
                    }
                    Log.d(TAG, "File ${metadata.fileName} transferred and ACK received!")

                } finally {
                    try { fileInputStream?.close() } catch (_: Exception) {}
                }
            }

            if (isCancelled) {
                onProgress(metricsCalculator.update(totalCumulativeBytesTransferred, TransferStatus.Cancelled))
                Result.failure(Exception("Transfer cancelled by user"))
            } else {
                Log.d(TAG, "All files transferred successfully! Total bytes: $totalCumulativeBytesTransferred")
                onProgress(metricsCalculator.update(totalCumulativeBytesTransferred, TransferStatus.Completed()))
                Result.success(Unit)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in sendFiles: ${e.message}", e)
            val msg = if (isCancelled) "Transfer cancelled by user" else (e.message ?: "Transfer error")
            try {
                val errProgress = metricsCalculator?.update(0L, TransferStatus.Error(msg))
                    ?: TransferProgress(
                        fileName = "File Transfer",
                        bytesTransferred = 0L,
                        totalBytes = 0L,
                        progressPercentage = 0,
                        speedBytesPerSec = 0.0,
                        speedFormatted = "-- MB/s",
                        etaSeconds = 0L,
                        etaFormatted = "Failed",
                        status = TransferStatus.Error(msg)
                    )
                onProgress(errProgress)
            } catch (_: Exception) {}
            if (isCancelled) {
                Result.failure(Exception("Transfer cancelled by user"))
            } else {
                Result.failure(e)
            }
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
            currentSocket = null
            currentServerSocket = null
        }
    }

    /**
     * Resolves file name, size, and MIME type from Android content URI with multi-tier fallbacks.
     */
    private fun resolveFileMetadata(uri: Uri): FileMetadata {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        // Tier 1: Query OpenableColumns
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 query metadata failed for $uri: ${e.message}")
        }

        // Tier 2: Open File Descriptor statSize if size is still 0
        if (size <= 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val statSize = pfd.statSize
                    if (statSize > 0) size = statSize
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 2 openFileDescriptor statSize failed: ${e.message}")
            }
        }

        // Tier 3: InputStream available fallback
        if (size <= 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val avail = stream.available().toLong()
                    if (avail > 0) size = avail
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 3 stream.available failed: ${e.message}")
            }
        }

        // Tier 4: Fallback for file-based URIs if applicable
        if (size <= 0L && uri.scheme == "file") {
            try {
                val f = java.io.File(uri.path ?: "")
                if (f.exists()) {
                    size = f.length()
                    name = f.name
                }
            } catch (_: Exception) {}
        }

        // Tier 5: Stream byte count fallback if content resolver doesn't provide length
        if (size <= 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    var count = 0L
                    val buf = ByteArray(65536)
                    var r: Int
                    while (stream.read(buf).also { r = it } != -1) {
                        count += r
                    }
                    if (count > 0L) size = count
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 5 stream drain size failed: ${e.message}")
            }
        }

        Log.d(TAG, "Resolved metadata for $uri -> name=$name, size=$size bytes, mime=$mimeType")

        return FileMetadata(
            fileName = name,
            fileSize = size,
            mimeType = mimeType,
            chunkSize = preferredChunkSize
        )
    }
}
