package com.example.anyshare.transfer

import java.util.Locale
import kotlin.math.max

/**
 * Calculates smooth transfer speed, ETA, and progress percentage using a sliding window.
 */
class TransferMetricsCalculator(
    private val totalBytes: Long,
    private val fileName: String
) {
    private var startTimeMillis: Long = System.currentTimeMillis()
    private var lastCalculationTime: Long = System.currentTimeMillis()
    private var lastCalculationBytes: Long = 0L

    private var currentSpeedBytesPerSec: Double = 0.0

    /**
     * Updates transferred byte count and returns a fresh snapshot of TransferProgress.
     */
    fun update(bytesTransferred: Long, status: TransferStatus = TransferStatus.InProgress): TransferProgress {
        val now = System.currentTimeMillis()
        val timeDeltaMs = now - lastCalculationTime

        // Calculate sliding window speed every 500ms for smooth UI updates
        if (timeDeltaMs >= 500) {
            val bytesDelta = bytesTransferred - lastCalculationBytes
            if (timeDeltaMs > 0 && bytesDelta >= 0) {
                val instantSpeed = (bytesDelta * 1000.0) / timeDeltaMs
                // Low-pass filter (exponential moving average) for non-jittery speed display
                currentSpeedBytesPerSec = if (currentSpeedBytesPerSec == 0.0) {
                    instantSpeed
                } else {
                    (currentSpeedBytesPerSec * 0.4) + (instantSpeed * 0.6)
                }
            }
            lastCalculationTime = now
            lastCalculationBytes = bytesTransferred
        }

        val percentage = if (totalBytes > 0) {
            ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val remainingBytes = max(0L, totalBytes - bytesTransferred)
        val etaSeconds = if (currentSpeedBytesPerSec > 1024) {
            (remainingBytes / currentSpeedBytesPerSec).toLong()
        } else {
            0L
        }

        return TransferProgress(
            fileName = fileName,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            progressPercentage = percentage,
            speedBytesPerSec = currentSpeedBytesPerSec,
            speedFormatted = formatSpeed(currentSpeedBytesPerSec),
            etaSeconds = etaSeconds,
            etaFormatted = formatEta(etaSeconds),
            status = status
        )
    }

    companion object {
        /**
         * Format byte size into human readable string (KB, MB, GB).
         */
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
        }

        /**
         * Format speed in Bytes/sec to MB/s or KB/s.
         */
        fun formatSpeed(bytesPerSec: Double): String {
            return when {
                bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024))
                bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024)
                bytesPerSec > 0 -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
                else -> "-- MB/s"
            }
        }

        /**
         * Format remaining ETA seconds into mm:ss or hh:mm:ss.
         */
        fun formatEta(seconds: Long): String {
            if (seconds <= 0) return "Calculating..."
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60

            return if (hours > 0) {
                String.format(Locale.US, "%02dh %02dm left", hours, minutes)
            } else {
                String.format(Locale.US, "%02dm %02ds left", minutes, secs)
            }
        }
    }
}
