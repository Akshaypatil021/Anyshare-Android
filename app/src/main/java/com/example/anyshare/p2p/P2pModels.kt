package com.example.anyshare.p2p

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo

/**
 * Represents the distinct states of the Wi-Fi Direct (P2P) connection lifecycle.
 */
sealed class P2pConnectionState {
    /** P2P is idle or not currently in an active group/discovery */
    object Idle : P2pConnectionState()

    /** Actively searching for nearby Wi-Fi Direct devices */
    object Discovering : P2pConnectionState()

    /** Autonomous Group Owner creation in progress (Sender/Host mode) */
    object CreatingGroup : P2pConnectionState()

    /**
     * Group Owner successfully created.
     * Ready for receiver to connect via QR code or direct P2P.
     */
    data class GroupOwnerReady(
        val groupInfo: WifiP2pGroup,
        val connectionInfo: WifiP2pInfo,
        val hostIp: String,
        val port: Int = 8888,
        val ssid: String,
        val passphrase: String,
        val pinCode: String,
        val securityToken: String
    ) : P2pConnectionState()

    /** Client is connecting to a specific Group Owner */
    data class Connecting(val targetDeviceAddress: String) : P2pConnectionState()

    /**
     * Successfully connected to a Group Owner as a Client.
     */
    data class ConnectedAsClient(
        val groupOwnerAddress: String,
        val port: Int = 8888,
        val connectionInfo: WifiP2pInfo
    ) : P2pConnectionState()

    /**
     * Successfully established connection as a Group Owner.
     */
    data class ConnectedAsGroupOwner(
        val hostAddress: String,
        val port: Int = 8888,
        val connectionInfo: WifiP2pInfo,
        val group: WifiP2pGroup? = null
    ) : P2pConnectionState()

    /** Connection or operation error */
    data class Error(val message: String, val errorCode: Int? = null) : P2pConnectionState()
}

/**
 * Helper to describe P2P failure codes returned by WifiP2pManager.
 */
object P2pErrorHelper {
    fun getErrorMessage(reasonCode: Int): String {
        return when (reasonCode) {
            0 -> "Internal error (ERROR)"
            1 -> "P2P is unsupported on this device (P2P_UNSUPPORTED)"
            2 -> "Framework is busy, retry shortly (BUSY)"
            3 -> "No service requests available (NO_SERVICE_REQUESTS)"
            else -> "Unknown P2P error ($reasonCode)"
        }
    }
}
