package com.example.anyshare.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * P2pNetworkConnector automatically connects the Receiver phone to the Sender phone's
 * Wi-Fi Direct SoftAP group (SSID & Passphrase obtained from the QR Code).
 *
 * - Android 10+ (API 29-35): Uses ConnectivityManager with WifiNetworkSpecifier.
 * - Android 8 & 9 (API 26-28): Uses legacy WifiManager.addNetwork / enableNetwork.
 */
class P2pNetworkConnector(private val context: Context) {

    companion object {
        private const val TAG = "P2pNetworkConnector"
        private const val CONNECT_TIMEOUT_MS = 35_000L
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetworkId: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var isConnected = false

    /**
     * Connects to the sender's Wi-Fi Direct network.
     *
     * @param ssid The SSID of the sender's P2P group (e.g. "DIRECT-AS-1234").
     * @param passphrase The WPA2 passphrase of the sender's P2P group.
     * @param onResult Callback returning success status, bound Network (if API 29+), or error message.
     */
    @SuppressLint("MissingPermission")
    fun connectToGroup(
        ssid: String,
        passphrase: String,
        onResult: (success: Boolean, network: Network?, errorMessage: String?) -> Unit
    ) {
        disconnect() // Clean up any lingering callbacks first

        Log.d(TAG, "Initiating connection to SSID: $ssid (passphrase length: ${passphrase.length})")
        isConnected = false

        // Ensure Wi-Fi is enabled
        if (!wifiManager.isWifiEnabled) {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = true
        }

        // Set safety timeout
        timeoutRunnable = Runnable {
            if (!isConnected) {
                Log.e(TAG, "Connection to $ssid timed out after ${CONNECT_TIMEOUT_MS}ms")
                disconnect()
                onResult(false, null, "Connection timed out. Please check if sender is still ready.")
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, CONNECT_TIMEOUT_MS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectViaNetworkSpecifier(ssid, passphrase, onResult)
        } else {
            connectViaLegacyWifiManager(ssid, passphrase, onResult)
        }
    }

    /**
     * Modern Android 10+ (API 29+) implementation using WifiNetworkSpecifier.
     */
    @SuppressLint("MissingPermission")
    private fun connectViaNetworkSpecifier(
        ssid: String,
        passphrase: String,
        onResult: (Boolean, Network?, String?) -> Unit
    ) {
        try {
            val specifier = if (passphrase.isNotEmpty()) {
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(passphrase)
                    .build()
            } else {
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .build()
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Successfully connected to $ssid via NetworkSpecifier: $network")
                    isConnected = true
                    timeoutRunnable?.let { mainHandler.removeCallbacks(it) }

                    // Bind this process so all raw TCP socket traffic routes over this Wi-Fi link
                    try {
                        connectivityManager.bindProcessToNetwork(network)
                        Log.d(TAG, "Process bound to Wi-Fi Direct network: $network")
                    } catch (e: Exception) {
                        Log.w(TAG, "bindProcessToNetwork warning: ${e.message}")
                    }

                    mainHandler.post {
                        onResult(true, network, null)
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.e(TAG, "NetworkSpecifier onUnavailable for $ssid")
                    timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    mainHandler.post {
                        onResult(false, null, "Failed to connect to sender Wi-Fi group")
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.w(TAG, "Connection lost to network: $network")
                }
            }

            activeCallback = callback
            connectivityManager.requestNetwork(request, callback)

        } catch (e: Exception) {
            Log.e(TAG, "Exception in connectViaNetworkSpecifier: ${e.message}", e)
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            onResult(false, null, e.message)
        }
    }

    /**
     * Legacy Android 8 & 9 (API 26-28, Honor 8) implementation using WifiConfiguration.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectViaLegacyWifiManager(
        ssid: String,
        passphrase: String,
        onResult: (Boolean, Network?, String?) -> Unit
    ) {
        try {
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (passphrase.isNotEmpty()) {
                    preSharedKey = "\"$passphrase\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                } else {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                status = WifiConfiguration.Status.ENABLED
            }

            // Remove any old config with same SSID to avoid conflict
            val configured = wifiManager.configuredNetworks ?: emptyList()
            for (conf in configured) {
                if (conf.SSID != null && conf.SSID == "\"$ssid\"") {
                    wifiManager.removeNetwork(conf.networkId)
                    break
                }
            }

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId == -1) {
                Log.e(TAG, "Failed to add legacy Wi-Fi network for SSID: $ssid")
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                onResult(false, null, "Failed to configure Wi-Fi network profile on this device")
                return
            }

            legacyNetworkId = netId
            wifiManager.disconnect()
            val enabled = wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
            Log.d(TAG, "enableNetwork returned $enabled for netId=$netId, reconnecting...")

            // Poll for connection to reach COMPLETED state and valid IP
            val pollRunnable = object : Runnable {
                var attempts = 0
                override fun run() {
                    if (isConnected) return
                    attempts++

                    val info = wifiManager.connectionInfo
                    val currentSsid = info?.ssid?.replace("\"", "") ?: ""
                    val ipAddress = info?.ipAddress ?: 0

                    Log.d(TAG, "Legacy poll #$attempts: currentSsid=$currentSsid, ip=$ipAddress")

                    if ((currentSsid == ssid || currentSsid.contains("DIRECT-")) && ipAddress != 0) {
                        Log.d(TAG, "Legacy connection successful to $ssid with IP: $ipAddress")
                        isConnected = true
                        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        onResult(true, null, null)
                        return
                    }

                    if (attempts < 20) {
                        mainHandler.postDelayed(this, 1000)
                    }
                }
            }
            mainHandler.postDelayed(pollRunnable, 1500)

        } catch (e: Exception) {
            Log.e(TAG, "Exception in connectViaLegacyWifiManager: ${e.message}", e)
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            onResult(false, null, e.message)
        }
    }

    /**
     * Disconnects and releases any active Wi-Fi request.
     */
    fun disconnect() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                connectivityManager.bindProcessToNetwork(null)
            } catch (_: Exception) {}

            activeCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }
            activeCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyNetworkId?.let {
                try {
                    wifiManager.disableNetwork(it)
                    wifiManager.removeNetwork(it)
                } catch (_: Exception) {}
            }
            legacyNetworkId = null
        }
        isConnected = false
    }
}
