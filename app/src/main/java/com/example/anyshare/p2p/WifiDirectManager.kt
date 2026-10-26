package com.example.anyshare.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.random.Random

/**
 * WifiDirectManager coordinates P2P discovery, autonomous group creation,
 * pairing, and connection lifecycle using reactive StateFlows.
 */
class WifiDirectManager(private val context: Context) : WifiDirectBroadcastReceiver.WifiDirectEventListener {

    companion object {
        private const val TAG = "WifiDirectManager"
        const val DEFAULT_TRANSFER_PORT = 8888
        const val DEFAULT_GROUP_OWNER_IP = "192.168.49.1"
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val deviceConfigManager = com.example.anyshare.utils.DeviceConfigManager(context)

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: WifiDirectBroadcastReceiver? = null
    private var isReceiverRegistered = false

    // Reactive StateFlows
    private val _connectionState = MutableStateFlow<P2pConnectionState>(P2pConnectionState.Idle)
    val connectionState: StateFlow<P2pConnectionState> = _connectionState.asStateFlow()

    private val _peersList = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peersList: StateFlow<List<WifiP2pDevice>> = _peersList.asStateFlow()

    private val _thisDevice = MutableStateFlow<WifiP2pDevice?>(null)
    val thisDevice: StateFlow<WifiP2pDevice?> = _thisDevice.asStateFlow()

    private val _isP2pEnabled = MutableStateFlow(false)
    val isP2pEnabled: StateFlow<Boolean> = _isP2pEnabled.asStateFlow()

    private val _activeGroup = MutableStateFlow<WifiP2pGroup?>(null)
    val activeGroup: StateFlow<WifiP2pGroup?> = _activeGroup.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private var currentSecurityToken: String = ""
    private var currentPinCode: String = ""

    init {
        initializeChannel()
    }

    private fun initializeChannel() {
        channel = manager?.initialize(context, context.mainLooper) {
            Log.w(TAG, "Wi-Fi Direct channel disconnected. Re-initializing...")
            _connectionState.value = P2pConnectionState.Error("Wi-Fi Direct channel lost")
            initializeChannel()
        }
    }

    /**
     * Register broadcast receiver for Wi-Fi P2P system intents.
     */
    fun register() {
        if (isReceiverRegistered || manager == null || channel == null) return

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = WifiDirectBroadcastReceiver(manager, channel!!, this)
        context.registerReceiver(receiver, intentFilter)
        isReceiverRegistered = true
        Log.d(TAG, "WifiDirectBroadcastReceiver registered successfully.")
    }

    /**
     * Unregister broadcast receiver.
     */
    fun unregister() {
        if (!isReceiverRegistered || receiver == null) return
        try {
            context.unregisterReceiver(receiver)
            isReceiverRegistered = false
            Log.d(TAG, "WifiDirectBroadcastReceiver unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    /**
     * Phase 2 - Core: Create Autonomous Group as Group Owner (Host/Server for Sender).
     * The device becomes a virtual P2P Hotspot with fixed IP 192.168.49.1.
     */
    @SuppressLint("MissingPermission")
    fun createAutonomousGroup(
        port: Int = DEFAULT_TRANSFER_PORT,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        val p2p = manager ?: run {
            onResult(false, "WifiP2pManager unavailable")
            return
        }
        val ch = channel ?: run {
            onResult(false, "Channel uninitialized")
            return
        }

        // Stop discovery prior to creating group so the radio is not BUSY
        stopDiscovery()

        _connectionState.value = P2pConnectionState.CreatingGroup
        currentSecurityToken = UUID.randomUUID().toString().take(8)
        currentPinCode = String.format("%04d", Random.nextInt(1000, 9999))

        // Remove any prior existing groups before creating a new one
        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                proceedGroupCreation(p2p, ch, port, onResult)
            }

            override fun onFailure(reason: Int) {
                // If there was no group, it fails with reason BUSY or ERROR, proceed anyway
                proceedGroupCreation(p2p, ch, port, onResult)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun proceedGroupCreation(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        port: Int,
        onResult: (Boolean, String?) -> Unit
    ) {
        try {
            val groupListener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Autonomous P2P Group created successfully.")
                    p2p.requestGroupInfo(ch) { group ->
                        _activeGroup.value = group
                        p2p.requestConnectionInfo(ch) { info ->
                            _connectionInfo.value = info
                            val hostIp = info?.groupOwnerAddress?.hostAddress ?: DEFAULT_GROUP_OWNER_IP
                            val ssid = group?.networkName ?: "DIRECT-Anyshare"
                            val passphrase = try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    group?.passphrase ?: ""
                                } else {
                                    val field = group?.javaClass?.getDeclaredField("mPassphrase")
                                    field?.isAccessible = true
                                    (field?.get(group) as? String) ?: ""
                                }
                            } catch (_: Exception) {
                                ""
                            }

                            val readyState = P2pConnectionState.GroupOwnerReady(
                                groupInfo = group ?: WifiP2pGroup(),
                                connectionInfo = info ?: WifiP2pInfo(),
                                hostIp = hostIp,
                                port = port,
                                ssid = ssid,
                                passphrase = passphrase,
                                pinCode = currentPinCode,
                                securityToken = currentSecurityToken
                            )
                            _connectionState.value = readyState
                            onResult(true, null)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    val errorMsg = P2pErrorHelper.getErrorMessage(reason)
                    Log.e(TAG, "createGroup failed: $errorMsg (reason $reason)")
                    if (reason == WifiP2pManager.BUSY) {
                        // Retry group creation once after 600ms if framework was briefly busy
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                proceedGroupCreation(p2p, ch, port, onResult)
                            } catch (_: Exception) {}
                        }, 600)
                        return
                    }
                    _connectionState.value = P2pConnectionState.Error(errorMsg, reason)
                    onResult(false, errorMsg)
                }
            }

            // Dynamic band selection: AUTO allows Android to negotiate 5GHz if both phones support it, or 2.4GHz if either phone is older
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val prefBand = deviceConfigManager.getWifiBandPreference()
                if (prefBand == com.example.anyshare.utils.DeviceConfigManager.BAND_5GHZ ||
                    prefBand == com.example.anyshare.utils.DeviceConfigManager.BAND_2GHZ
                ) {
                    val targetBand = if (prefBand == com.example.anyshare.utils.DeviceConfigManager.BAND_5GHZ) {
                        WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                    } else {
                        WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                    }
                    try {
                        val rand = (1000..9999).random()
                        val config = WifiP2pConfig.Builder()
                            .setNetworkName("DIRECT-AS-$rand")
                            .setPassphrase("anyshare$rand")
                            .setGroupOperatingBand(targetBand)
                            .build()
                        Log.d(TAG, "Creating P2P Group with forced band $targetBand (SSID: DIRECT-AS-$rand)")
                        p2p.createGroup(ch, config, groupListener)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Forced band createGroup failed, falling back to standard createGroup: ${e.message}")
                    }
                }
            }

            // Standard autonomous group creation for AUTO mode and all fallbacks
            Log.d(TAG, "Creating standard autonomous P2P group (Auto Band)")
            p2p.createGroup(ch, groupListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in proceedGroupCreation: ${e.message}")
            _connectionState.value = P2pConnectionState.Error("Failed to create group: ${e.message}", -1)
            onResult(false, e.message)
        }
    }

    /**
     * Start discovering nearby Wi-Fi Direct peers.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(onResult: (success: Boolean, errorMessage: String?) -> Unit) {
        val p2p = manager ?: return onResult(false, "WifiP2pManager unavailable")
        val ch = channel ?: return onResult(false, "Channel uninitialized")

        try {
            _connectionState.value = P2pConnectionState.Discovering
            p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Peer discovery started successfully.")
                    onResult(true, null)
                }

                override fun onFailure(reason: Int) {
                    val msg = P2pErrorHelper.getErrorMessage(reason)
                    Log.w(TAG, "discoverPeers returned: $msg (reason $reason)")
                    if (reason == WifiP2pManager.BUSY) {
                        // BUSY during discovery is transient (e.g. scan in progress or radio settling).
                        // Silently retry after 1.2s instead of triggering a fatal error popup to user.
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                p2p.discoverPeers(ch, null)
                            } catch (_: Exception) {}
                        }, 1200)
                        return
                    }
                    _connectionState.value = P2pConnectionState.Error(msg, reason)
                    onResult(false, msg)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in discoverPeers: ${e.message}")
            _connectionState.value = P2pConnectionState.Error("Nearby permission required", -1)
            onResult(false, e.message)
        }
    }

    /**
     * Stop peer discovery. Protects active connections from being reset.
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (_connectionState.value is P2pConnectionState.ConnectedAsGroupOwner ||
            _connectionState.value is P2pConnectionState.ConnectedAsClient
        ) {
            Log.d(TAG, "Skipping stopDiscovery because P2P group is active.")
            return
        }
        manager?.stopPeerDiscovery(channel, null)
    }

    /**
     * Connect to a specific peer as a Client.
     * Stops peer discovery asynchronously and waits for the radio to enter idle state
     * before issuing connect, preventing framework ERROR (0) internal errors.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(
        deviceAddress: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        val p2p = manager ?: return onResult(false, "WifiP2pManager unavailable")
        val ch = channel ?: return onResult(false, "Channel uninitialized")

        _connectionState.value = P2pConnectionState.Connecting(deviceAddress)

        // Cancel any stale connection attempts first
        p2p.cancelConnect(ch, null)

        // Asynchronously stop peer discovery before connecting
        p2p.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery stopped cleanly before connecting.")
                // Give the Wi-Fi radio 200ms to settle into idle state before initiating connect
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    executeConnect(p2p, ch, deviceAddress, onResult)
                }, 200)
            }

            override fun onFailure(reason: Int) {
                Log.d(TAG, "stopPeerDiscovery returned reason $reason, proceeding to connect in 200ms.")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    executeConnect(p2p, ch, deviceAddress, onResult)
                }, 200)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun executeConnect(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        deviceAddress: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            // Default groupOwnerIntent (-1) lets Android negotiate GO/Client role based on device capabilities
            groupOwnerIntent = -1
        }

        try {
            p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connecting to $deviceAddress initiated.")
                    onResult(true, null)
                }

                override fun onFailure(reason: Int) {
                    val msg = P2pErrorHelper.getErrorMessage(reason)
                    Log.w(TAG, "Connect to $deviceAddress failed: $msg (reason $reason)")

                    // If framework is BUSY (2) or reports internal ERROR (0) on initial attempt due to radio settling,
                    // cancel connect and retry once after 600ms.
                    if (reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) {
                        Log.d(TAG, "Transient connect error ($reason), retrying once after 600ms...")
                        p2p.cancelConnect(ch, null)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        Log.d(TAG, "Connect to $deviceAddress succeeded on retry.")
                                        onResult(true, null)
                                    }

                                    override fun onFailure(r: Int) {
                                        val retryMsg = P2pErrorHelper.getErrorMessage(r)
                                        Log.e(TAG, "Connect retry failed: $retryMsg (reason $r)")
                                        _connectionState.value = P2pConnectionState.Error(retryMsg, r)
                                        onResult(false, retryMsg)
                                    }
                                })
                            } catch (e: Exception) {
                                onResult(false, e.message)
                            }
                        }, 600)
                        return
                    }

                    _connectionState.value = P2pConnectionState.Error(msg, reason)
                    onResult(false, msg)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception in executeConnect: ${e.message}")
            _connectionState.value = P2pConnectionState.Error("Connection error: ${e.message}", -1)
            onResult(false, e.message)
        }
    }

    /**
     * Disconnect and release the P2P group and channel.
     */
    @SuppressLint("MissingPermission")
    fun disconnectAndRemoveGroup(onComplete: (() -> Unit)? = null) {
        stopDiscovery()
        manager?.cancelConnect(channel, null)
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P Group removed cleanly.")
                _connectionState.value = P2pConnectionState.Idle
                _activeGroup.value = null
                _connectionInfo.value = null
                onComplete?.invoke()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "removeGroup failed or no active group: ${P2pErrorHelper.getErrorMessage(reason)}")
                _connectionState.value = P2pConnectionState.Idle
                _activeGroup.value = null
                _connectionInfo.value = null
                onComplete?.invoke()
            }
        })
    }

    // --- BroadcastReceiver Event Callbacks ---

    override fun onP2pStateChanged(enabled: Boolean) {
        Log.d(TAG, "P2P State changed: enabled=$enabled")
        _isP2pEnabled.value = enabled
        if (!enabled) {
            _connectionState.value = P2pConnectionState.Error("Wi-Fi Direct has been turned OFF")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onPeersChanged() {
        manager?.requestPeers(channel) { peers ->
            val list = peers.deviceList.toList()
            Log.d(TAG, "Peers list updated: count=${list.size}")
            _peersList.value = list
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionChanged(networkInfo: NetworkInfo?) {
        Log.d(TAG, "Connection changed: networkInfo=$networkInfo, isConnected=${networkInfo?.isConnected}")
        val p2p = manager ?: return
        val ch = channel ?: return

        // Always query requestConnectionInfo directly from framework as source of truth
        p2p.requestConnectionInfo(ch) { info ->
            if (info == null) return@requestConnectionInfo
            Log.d(TAG, "requestConnectionInfo: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}, ownerAddress=${info.groupOwnerAddress}")
            _connectionInfo.value = info

            if (info.groupFormed) {
                val hostIp = info.groupOwnerAddress?.hostAddress ?: DEFAULT_GROUP_OWNER_IP
                if (info.isGroupOwner) {
                    Log.d(TAG, "Connected as Group Owner (IP: $hostIp)")
                    _connectionState.value = P2pConnectionState.ConnectedAsGroupOwner(
                        hostAddress = hostIp,
                        port = DEFAULT_TRANSFER_PORT,
                        connectionInfo = info,
                        group = _activeGroup.value
                    )
                } else {
                    Log.d(TAG, "Connected as Client to Group Owner: $hostIp")
                    _connectionState.value = P2pConnectionState.ConnectedAsClient(
                        groupOwnerAddress = hostIp,
                        port = DEFAULT_TRANSFER_PORT,
                        connectionInfo = info
                    )
                }

                // Asynchronously query group info without blocking socket connection readiness
                try {
                    p2p.requestGroupInfo(ch) { group ->
                        _activeGroup.value = group
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "requestGroupInfo non-critical exception: ${e.message}")
                }
            } else {
                // If group is not formed and network reports disconnected, revert to Idle
                if (networkInfo?.isConnected == false) {
                    Log.d(TAG, "Group not formed and network disconnected -> Idle")
                    _connectionState.value = P2pConnectionState.Idle
                    _activeGroup.value = null
                    _connectionInfo.value = null
                }
            }
        }
    }

    override fun onThisDeviceChanged(device: WifiP2pDevice?) {
        Log.d(TAG, "This device updated: ${device?.deviceName} (${device?.deviceAddress})")
        _thisDevice.value = device
    }
}
