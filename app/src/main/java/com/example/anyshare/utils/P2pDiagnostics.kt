package com.example.anyshare.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.example.anyshare.transfer.TransferMetricsCalculator

data class DeviceDiagnosticReport(
    val deviceModel: String,
    val androidVersion: String,
    val apiLevel: Int,
    val isWifiDirectSupported: Boolean,
    val is5GHzSupported: Boolean,
    val freeStorageFormatted: String,
    val isScopedStorageActive: Boolean,
    val isPowerSavingExempted: Boolean
) {
    fun toFormattedString(): String = buildString {
        appendLine("=== Anyshare P2P Device Health Diagnostic ===")
        appendLine("• Device: $deviceModel")
        appendLine("• Android Version: $androidVersion (API $apiLevel)")
        appendLine("• Wi-Fi Direct (P2P) Chipset: ${if (isWifiDirectSupported) "✅ Supported" else "❌ Unsupported"}")
        appendLine("• 5 GHz High-Throughput Band: ${if (is5GHzSupported) "🚀 Supported (Fast)" else "⚠️ 2.4 GHz Only"}")
        appendLine("• Free Internal Storage: 💾 $freeStorageFormatted")
        appendLine("• Scoped Storage Mode: ${if (isScopedStorageActive) "Active (Android 10+)" else "Legacy"}")
        appendLine("• Battery Doze Protection: ${if (isPowerSavingExempted) "Exempted" else "Optimized"}")
    }
}

object P2pDiagnostics {

    fun generateReport(context: Context): DeviceDiagnosticReport {
        val packageManager = context.packageManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val hasP2p = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val has5GHz = wifiManager?.is5GHzBandSupported == true

        val freeBytes = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }

        val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }

        return DeviceDiagnosticReport(
            deviceModel = "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            isWifiDirectSupported = hasP2p,
            is5GHzSupported = has5GHz,
            freeStorageFormatted = TransferMetricsCalculator.formatBytes(freeBytes),
            isScopedStorageActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            isPowerSavingExempted = isIgnoringBatteryOptimizations
        )
    }
}
