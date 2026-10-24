package com.example.anyshare.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Helper to ensure Wi-Fi Direct TCP Sockets are correctly routed over the local P2P interface,
 * bypassing default routing (Home Wi-Fi router / Mobile Data) on Android 8 to 15+.
 */
object P2pSocketHelper {

    private const val TAG = "P2pSocketHelper"

    /**
     * Scans active network interfaces to find the local IPv4 address assigned to the P2P interface (192.168.49.X).
     * Returns null if DHCP has not yet finished assigning an IP.
     */
    fun findLocalP2pAddress(): InetAddress? {
        try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces()) ?: return null
            // Pass 1: Look for standard 192.168.49.X address across all interfaces
            for (intf in interfaces) {
                val addrs = try { java.util.Collections.list(intf.inetAddresses) } catch (_: Exception) { emptyList() }
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith("192.168.49.")) {
                            Log.d(TAG, "Found local P2P IP: $ip on interface ${intf.name}")
                            return addr
                        }
                    }
                }
            }
            // Pass 2: Look for any interface named p2p / wlan
            for (intf in interfaces) {
                if (intf.name.contains("p2p", ignoreCase = true)) {
                    val addrs = try { java.util.Collections.list(intf.inetAddresses) } catch (_: Exception) { emptyList() }
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            Log.d(TAG, "Found local P2P fallback IP: ${addr.hostAddress} on interface ${intf.name}")
                            return addr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finding local P2P IP: ${e.message}")
        }
        return null
    }

    /**
     * Binds the socket to the Android Wi-Fi Direct network via ConnectivityManager.
     * This instructs Android OS netd daemon to route all packets on this socket
     * over the P2P radio instead of sending them to the Home Wi-Fi router or Mobile Data.
     */
    fun bindSocketToP2pNetwork(context: Context, socket: Socket) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                // Wi-Fi Direct networks have TRANSPORT_WIFI and no internet capability
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    network.bindSocket(socket)
                    Log.d(TAG, "Explicitly bound socket to Wi-Fi Direct network: $network")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bindSocketToP2pNetwork notice: ${e.message}")
        }
    }
}
