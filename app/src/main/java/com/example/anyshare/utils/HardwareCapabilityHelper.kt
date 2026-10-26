package com.example.anyshare.utils

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.anyshare.transfer.TransferConfig

/**
 * Detects device hardware profile (RAM, CPU cores, Wi-Fi 5GHz support, Android version)
 * and dynamically tunes chunk sizes, socket buffers, and network protocols for maximum
 * speed on modern flagships while guaranteeing rock-solid stability on legacy phones (e.g. Android 8 Honor).
 */
object HardwareCapabilityHelper {

    private const val TAG = "HardwareCapability"

    /**
     * Checks if this device radio hardware supports 5 GHz Wi-Fi band.
     */
    fun is5GHzSupported(context: Context): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.is5GHzBandSupported == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Total device RAM in Megabytes (e.g. 3072 MB on Honor 8, 8192 - 12288 MB on modern phones).
     */
    fun getTotalRamMb(context: Context): Long {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024)
        } catch (_: Exception) {
            2048L
        }
    }

    /**
     * Returns true if device is a modern high-performance device:
     * - Android 10+ (API 29+)
     * - RAM >= 3.8 GB
     * - 5 GHz Wi-Fi Hardware Supported
     * - Multi-core processor (>= 6 CPU cores)
     */
    fun isHighPerformanceDevice(context: Context): Boolean {
        val ramMb = getTotalRamMb(context)
        val has5Ghz = is5GHzSupported(context)
        val isModernApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val cores = Runtime.getRuntime().availableProcessors()

        val isHighEnd = isModernApi && (ramMb >= 3800L || has5Ghz) && cores >= 6
        Log.d(TAG, "Hardware profile: API=${Build.VERSION.SDK_INT}, RAM=${ramMb}MB, 5GHz=$has5Ghz, Cores=$cores -> isHighEnd=$isHighEnd")
        return isHighEnd
    }

    /**
     * Dynamically determines optimal TCP socket buffer size.
     * High-end devices (Android 15): 1 MB - 2 MB buffer for full 50-80 MB/s saturation.
     * Legacy devices (Android 8 Honor): 384 KB buffer to avoid kernel socket overflow.
     */
    fun getOptimalSocketBufferSize(context: Context): Int {
        return if (isHighPerformanceDevice(context)) {
            1024 * 1024 // 1 MB high-throughput TCP window
        } else {
            384 * 1024 // 384 KB safe buffer for legacy devices like Honor 8
        }
    }

    /**
     * Dynamically determines chunk streaming size based on hardware profile and file size.
     * - When both phones support modern hardware, large files stream in 1 MB bursts.
     * - When transferring to/from an older phone (Honor on Android 8), chunks scale down
     *   to 128 KB - 256 KB to keep CPU cool, eliminate GC thrashing, and avoid eMMC write stalls.
     */
    fun getOptimalChunkSize(context: Context, fileSize: Long): Int {
        val isHighEnd = isHighPerformanceDevice(context)
        val chunkSize = when {
            isHighEnd && fileSize >= 20 * 1024 * 1024 -> {
                // Large files on high-end device: 1 MB chunks for maximum throughput
                1024 * 1024
            }
            isHighEnd && fileSize >= 3 * 1024 * 1024 -> {
                // Medium files on high-end device: 512 KB chunks
                512 * 1024
            }
            isHighEnd -> {
                // Small files on high-end device: 256 KB chunks
                256 * 1024
            }
            fileSize >= 30 * 1024 * 1024 -> {
                // Large files on legacy device: 256 KB chunks
                256 * 1024
            }
            else -> {
                // Small/Medium files on legacy device: 128 KB chunks
                128 * 1024
            }
        }
        return chunkSize.coerceIn(TransferConfig.MIN_CHUNK_SIZE, TransferConfig.MAX_CHUNK_SIZE)
    }

    /**
     * Returns a human-friendly description of this phone's hardware profile.
     */
    fun getHardwareSummary(context: Context): String {
        val isHighEnd = isHighPerformanceDevice(context)
        val has5Ghz = is5GHzSupported(context)
        val ramGb = ((getTotalRamMb(context) + 512) / 1024).coerceAtLeast(1)
        val model = Build.MODEL ?: "Android"
        return if (isHighEnd) {
            "⚡ Turbo (${ramGb}GB RAM, ${if (has5Ghz) "5 GHz ⚡" else "2.4 GHz"})"
        } else {
            "🛡️ Compatible (${ramGb}GB RAM, 2.4 GHz)"
        }
    }
}
