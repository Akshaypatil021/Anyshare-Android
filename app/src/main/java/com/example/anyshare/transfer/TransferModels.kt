package com.example.anyshare.transfer

import android.net.Uri

/**
 * Socket and Chunk buffer configuration tailored for maximum throughput on Wi-Fi Direct.
 */
object TransferConfig {
    /** 64 KB Minimum Sending Block Size - highly resilient for low-RAM / legacy phones */
    const val MIN_CHUNK_SIZE = 64 * 1024 // 64 KB

    /** 1 MB Maximum Sending Block Size for high-throughput streaming on modern phones */
    const val MAX_CHUNK_SIZE = 1024 * 1024 // 1 MB

    /** 256 KB Default Chunk Size */
    const val DEFAULT_CHUNK_SIZE = 256 * 1024 // 256 KB

    /** 1 MB Max TCP Socket buffer size */
    const val SOCKET_BUFFER_SIZE = 1024 * 1024 // 1 MB

    /** Socket read timeout in milliseconds (60s) - tolerates slow flash storage & GC pauses on older phones */
    const val SOCKET_TIMEOUT_MS = 60_000

    /** Magic Header identifier for protocol validation */
    const val PROTOCOL_MAGIC = "ANYSHARE_P2P_V1"
    const val PROTOCOL_MAGIC_V2 = "ANYSHARE_P2P_V2"
}

/**
 * Metadata sent in the Header packet prior to streaming raw byte chunks.
 */
data class FileMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val checksum: String = "",
    val chunkSize: Int = TransferConfig.DEFAULT_CHUNK_SIZE,
    val uriString: String? = null
)

/**
 * Real-time progress snapshot emitted continuously during chunk streaming.
 */
data class TransferProgress(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val progressPercentage: Int, // 0 - 100
    val speedBytesPerSec: Double,
    val speedFormatted: String, // e.g. "48.2 MB/s"
    val etaSeconds: Long,
    val etaFormatted: String, // e.g. "01m 24s"
    val status: TransferStatus
)

/**
 * Lifecycle states of an active file transfer operation.
 */
sealed class TransferStatus {
    object Idle : TransferStatus()
    object Connecting : TransferStatus()
    object Handshaking : TransferStatus()
    object InProgress : TransferStatus()
    data class Completed(val fileSavedPath: String? = null) : TransferStatus()
    object Cancelled : TransferStatus()
    data class Error(val message: String, val throwable: Throwable? = null) : TransferStatus()
}
