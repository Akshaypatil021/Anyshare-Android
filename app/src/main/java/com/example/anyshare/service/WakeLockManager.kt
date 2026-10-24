package com.example.anyshare.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Manages CPU WakeLock and Wi-Fi High-Performance Lock during active transfers
 * to prevent OS CPU sleep and Wi-Fi bandwidth throttling.
 */
class WakeLockManager(private val context: Context) {

    companion object {
        private const val TAG = "WakeLockManager"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Acquire CPU partial wake lock and Wi-Fi high performance lock.
     *
     * @param maxTimeoutMinutes Safety timeout after which locks release automatically.
     */
    fun acquireLocks(maxTimeoutMinutes: Long = 60) {
        val timeoutMs = maxTimeoutMinutes * 60 * 1000

        // 1. CPU WakeLock
        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Anyshare::TransferCpuLock"
            )?.apply {
                setReferenceCounted(false)
            }
        }

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeoutMs)
                Log.d(TAG, "CPU WakeLock acquired successfully.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}")
        }

        // 2. Wi-Fi High-Performance Lock
        if (wifiLock == null) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Anyshare::TransferWifiLock"
            )?.apply {
                setReferenceCounted(false)
            }
        }

        try {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d(TAG, "Wi-Fi High-Perf Lock acquired.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WifiLock: ${e.message}")
        }
    }

    /**
     * Release all held locks cleanly.
     */
    fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "CPU WakeLock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}")
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "Wi-Fi Lock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WifiLock: ${e.message}")
        }
    }
}
