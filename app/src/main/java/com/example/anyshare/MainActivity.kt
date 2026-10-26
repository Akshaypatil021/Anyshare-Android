package com.example.anyshare

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.anyshare.databinding.ActivityMainBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.anyshare.history.HistoryManager
import com.example.anyshare.p2p.P2pConnectionState
import com.example.anyshare.p2p.WifiDirectManager
import com.example.anyshare.permission.PermissionManager
import com.example.anyshare.qr.QrConnectionPayload
import com.example.anyshare.qr.QrScannerActivity
import com.example.anyshare.service.TransferService
import com.example.anyshare.ui.HistoryActivity
import com.example.anyshare.ui.SendActivity
import com.example.anyshare.ui.SettingsActivity
import com.example.anyshare.ui.TransferActivity
import com.example.anyshare.utils.DeviceConfigManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Screen 1: Modern Material 3 Home Screen for Anyshare.
 * LocalSend-inspired minimalist aesthetic with automatic permission prompts on launch
 * and adaptive system bars (status bar clock & notification icons dynamically adapt to theme).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager
    private lateinit var wifiDirectManager: WifiDirectManager
    private lateinit var historyManager: HistoryManager
    private lateinit var deviceConfigManager: DeviceConfigManager
    private var outerRingAnimator: ObjectAnimator? = null

    // ActivityResult launcher for QR Scanner (Screen 3)
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(QrScannerActivity.EXTRA_PAYLOAD_JSON)
            val manualPin = result.data?.getStringExtra(QrScannerActivity.EXTRA_MANUAL_PIN)

            if (!json.isNullOrEmpty()) {
                val payload = QrConnectionPayload.fromJson(json)
                if (payload != null) {
                    handleReceiverPairing(payload)
                } else {
                    Toast.makeText(this, "Could not parse QR Code", Toast.LENGTH_SHORT).show()
                }
            } else if (!manualPin.isNullOrEmpty()) {
                Toast.makeText(this, "Pairing via PIN: $manualPin (Connecting...)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup dynamic status bar and navigation bar icon colors (light vs dark theme)
        setupSystemBarsTheme()

        // Handle edge-to-edge window insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                systemBars.top + 8,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )
            binding.bottomNavigation.setPadding(
                binding.bottomNavigation.paddingLeft,
                binding.bottomNavigation.paddingTop,
                binding.bottomNavigation.paddingRight,
                systemBars.bottom + 8
            )
            insets
        }

        permissionManager = PermissionManager(this)
        wifiDirectManager = WifiDirectManager(this)
        historyManager = HistoryManager(this)
        deviceConfigManager = DeviceConfigManager(this)

        initUI()
        setupListeners()
        startOuterRingRotation()
        observeP2pConnection()

        // Upfront permission request on app launch as requested
        checkAndAutoRequestPermissions()
    }

    private var discoveryJob: Job? = null

    private fun startPeriodicDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = lifecycleScope.launch {
            while (isActive) {
                if (PermissionManager.hasNearbyPermissions(this@MainActivity) && PermissionManager.isWifiEnabled(this@MainActivity)) {
                    wifiDirectManager.startDiscovery { _, _ -> }
                }
                delay(60_000L) // Refresh discovery every 60 seconds so receiver stays discoverable
            }
        }
    }

    private fun checkAndAutoRequestPermissions() {
        if (!permissionManager.hasAllRequiredPermissions(this)) {
            permissionManager.requestMissingPermissions { allGranted, denied ->
                if (allGranted) {
                    Toast.makeText(this, "Permissions granted! Device is discoverable.", Toast.LENGTH_SHORT).show()
                    startPeriodicDiscovery()
                } else {
                    Toast.makeText(
                        this,
                        "${denied.size} permission(s) denied. Please grant all permissions for P2P sharing.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            startPeriodicDiscovery()
        }
    }

    override fun onStart() {
        super.onStart()
        wifiDirectManager.register()
        if (PermissionManager.hasNearbyPermissions(this)) {
            startPeriodicDiscovery()
        }
    }

    override fun onStop() {
        super.onStop()
        discoveryJob?.cancel()
        discoveryJob = null
        wifiDirectManager.stopDiscovery()
        wifiDirectManager.unregister()
    }

    /**
     * Observes P2P connection events on the Receive screen.
     * When a sender connects (as GO or Client), immediately starts TransferService and TransferActivity.
     */
    private fun observeP2pConnection() {
        lifecycleScope.launch {
            wifiDirectManager.connectionState.collectLatest { state ->
                when (state) {
                    is P2pConnectionState.ConnectedAsGroupOwner -> {
                        TransferService.startReceiving(
                            context = this@MainActivity,
                            port = state.port,
                            isGroupOwner = true,
                            host = state.hostAddress
                        )
                        val intent = Intent(this@MainActivity, TransferActivity::class.java).apply {
                            putExtra(TransferActivity.EXTRA_IS_SENDER, false)
                            putExtra(TransferActivity.EXTRA_FILE_NAME, "Incoming File")
                        }
                        com.example.anyshare.utils.ActivityTransitionHelper.startActivityWithSlide(
                            this@MainActivity,
                            intent
                        )
                    }

                    is P2pConnectionState.ConnectedAsClient -> {
                        TransferService.startReceiving(
                            context = this@MainActivity,
                            port = state.port,
                            isGroupOwner = false,
                            host = state.groupOwnerAddress
                        )
                        val intent = Intent(this@MainActivity, TransferActivity::class.java).apply {
                            putExtra(TransferActivity.EXTRA_IS_SENDER, false)
                            putExtra(TransferActivity.EXTRA_FILE_NAME, "Incoming File")
                        }
                        com.example.anyshare.utils.ActivityTransitionHelper.startActivityWithSlide(
                            this@MainActivity,
                            intent
                        )
                    }

                    else -> {}
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupSystemBarsTheme()
        updateDeviceInfoUI()
        startOuterRingRotation()
        if (!PermissionManager.isWifiEnabled(this)) {
            Toast.makeText(this, "Please turn on Wi-Fi for direct transfer", Toast.LENGTH_SHORT).show()
        } else if (!PermissionManager.isLocationServiceEnabled(this)) {
            Toast.makeText(this, "Please turn on Location (GPS) for peer discovery on this Android version", Toast.LENGTH_LONG).show()
        }
        if (PermissionManager.hasNearbyPermissions(this) && PermissionManager.isWifiEnabled(this)) {
            startPeriodicDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        pauseOuterRingRotation()
    }

    override fun onDestroy() {
        super.onDestroy()
        outerRingAnimator?.cancel()
        outerRingAnimator = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupSystemBarsTheme()
    }

    /**
     * Requirement: Smooth, continuous slow rotation of the outer dashed/segmented ring around the central circle.
     * Controlled by the animations toggle in Settings.
     */
    private fun startOuterRingRotation() {
        if (!deviceConfigManager.isAnimationsEnabled()) {
            pauseOuterRingRotation()
            binding.imgAvatarOuterRing.rotation = 0f
            return
        }
        if (outerRingAnimator == null) {
            outerRingAnimator = ObjectAnimator.ofFloat(binding.imgAvatarOuterRing, View.ROTATION, 0f, 360f).apply {
                duration = 14000L // 14 seconds per full revolution for a serene, slow circular movement
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        if (outerRingAnimator?.isStarted != true) {
            outerRingAnimator?.start()
        } else if (outerRingAnimator?.isPaused == true) {
            outerRingAnimator?.resume()
        }
    }

    private fun pauseOuterRingRotation() {
        outerRingAnimator?.pause()
    }

    /**
     * Requirement: Ensure phone clock time, battery, and notification icons in status bar
     * automatically change color according to theme:
     * - White/Light background -> status bar icons/text are Dark/Black (clearly readable).
     * - Black/Dark background -> status bar icons/text are White/Light (clearly readable).
     */
    private fun setupSystemBarsTheme() {
        val isNightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        // When theme is LIGHT, isAppearanceLightStatusBars = true makes icons DARK
        // When theme is DARK, isAppearanceLightStatusBars = false makes icons LIGHT
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode
    }

    /**
     * Initializes device name and tag.
     */
    private fun initUI() {
        updateDeviceInfoUI()
    }

    private fun updateDeviceInfoUI() {
        binding.tvDeviceName.text = deviceConfigManager.getDeviceNickname()
    }

    private fun setupListeners() {
        // 1. Top Bar Actions
        binding.btnScanQr.setOnClickListener {
            val intent = Intent(this, QrScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        }

        binding.btnHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            com.example.anyshare.utils.ActivityTransitionHelper.startActivityWithSlide(this, intent)
        }

        binding.btnInfo.setOnClickListener {
            showAboutDialog()
        }

        // 2. Click Device Avatar / Name to Edit Device Name
        binding.centerDeviceLayout.setOnClickListener {
            showEditDeviceNameDialog()
        }

        // 3. Bottom Navigation Tabs
        binding.navReceive.setOnClickListener {
            // Already on Receive screen; offer options to scan QR or view receive status
            showReceiveOptionsDialog()
        }

        binding.navSend.setOnClickListener {
            com.example.anyshare.utils.ActivityTransitionHelper.navigateToTab(this, SendActivity::class.java)
        }

        binding.navSettings.setOnClickListener {
            com.example.anyshare.utils.ActivityTransitionHelper.navigateToTab(this, SettingsActivity::class.java)
        }
    }

    /**
     * Dialog to edit device nickname.
     */
    private fun showEditDeviceNameDialog() {
        val input = EditText(this).apply {
            setText(deviceConfigManager.getDeviceNickname())
            setSelection(text.length)
            setPadding(50, 40, 50, 40)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Device Name")
            .setMessage("Set a friendly nickname visible to other devices:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    deviceConfigManager.setDeviceNickname(newName)
                    updateDeviceInfoUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Receive Options Dialog when user taps Receive tab or Center Avatar.
     */
    private fun showReceiveOptionsDialog() {
        val options = arrayOf(
            "📷 Scan Sender's QR Code",
            "📶 Device Status & Info",
            "🕒 View Transfer History"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Receive Mode Active")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (PermissionManager.hasCameraPermission(this)) {
                            val intent = Intent(this, QrScannerActivity::class.java)
                            qrScannerLauncher.launch(intent)
                        } else {
                            permissionManager.requestCameraPermission { granted ->
                                if (granted) {
                                    val intent = Intent(this, QrScannerActivity::class.java)
                                    qrScannerLauncher.launch(intent)
                                } else {
                                    Toast.makeText(
                                        this,
                                        "Camera permission is required to scan QR codes",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }

                    1 -> {
                        if (!PermissionManager.hasNearbyPermissions(this)) {
                            permissionManager.requestNearbyPermissions { allGranted, _ ->
                                if (allGranted) {
                                    wifiDirectManager.startDiscovery { _, _ -> }
                                    Toast.makeText(
                                        this,
                                        "Device is now discoverable to nearby senders",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                showSettingsDialog()
                            }
                        } else {
                            showSettingsDialog()
                        }
                    }

                    2 -> startActivity(Intent(this, HistoryActivity::class.java))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Settings Dialog showing device details, Wi-Fi status, and storage folder.
     */
    private fun showSettingsDialog() {
        val isWifiOn = permissionManager.isWifiEnabled(this)
        val wifiText = if (isWifiOn) "Enabled (Ready)" else "Disabled (Tap to Enable)"

        val hwProfile = com.example.anyshare.utils.HardwareCapabilityHelper.getHardwareSummary(this)
        val bandModeText = when (deviceConfigManager.getWifiBandPreference()) {
            com.example.anyshare.utils.DeviceConfigManager.BAND_5GHZ -> "5 GHz Turbo"
            com.example.anyshare.utils.DeviceConfigManager.BAND_2GHZ -> "2.4 GHz Compatible"
            else -> "⚡ Dynamic Auto (5GHz / 2.4GHz)"
        }

        val message = """
            📱 Device Name: ${deviceConfigManager.getDeviceNickname()} (${deviceConfigManager.getDeviceTag()})
            
            ⚙️ Hardware Profile: $hwProfile
            
            📶 Wi-Fi Status: $wifiText
            
            ⚡ Band Mode: $bandModeText
            
            📂 Save Location: Downloads/Anyshare
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("Anyshare Settings")
            .setMessage(message)
            .setPositiveButton("Edit Name") { _, _ ->
                showEditDeviceNameDialog()
            }
            .setNegativeButton("Wi-Fi Settings") { _, _ ->
                permissionManager.openWifiSettings(this)
            }
            .setNeutralButton("Done", null)
            .show()
    }

    /**
     * Normalized Device & App Info Dialog:
     * Displays current Device Name, current Device IP, current Sharing Port, and storage details.
     */
    private fun showAboutDialog() {
        val dialogBinding = com.example.anyshare.databinding.DialogDeviceInfoBinding.inflate(layoutInflater)

        // 1. Current Device Name
        dialogBinding.tvInfoDeviceName.text =
            "${deviceConfigManager.getDeviceNickname()} (${deviceConfigManager.getDeviceTag()})"

        // 2. Current Device IP
        val currentIp = getLocalIpAddress()
        dialogBinding.tvInfoDeviceIp.text = currentIp

        // 3. Current Sharing Port
        dialogBinding.tvInfoDevicePort.text = "${WifiDirectManager.DEFAULT_TRANSFER_PORT} (TCP High-Speed)"

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Resolves the current local IP address across Wi-Fi Direct or active Wi-Fi interfaces.
     */
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            // First check p2p or wlan interfaces
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress
                        if (intf.name.contains("p2p", ignoreCase = true) || intf.name.contains(
                                "wlan",
                                ignoreCase = true
                            )
                        ) {
                            return host ?: "192.168.49.1"
                        }
                    }
                }
            }
            // Fallback: any non-loopback IPv4
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "192.168.49.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "192.168.49.1"
    }

    /**
     * Receiver Flow: Connects to sender and launches TransferActivity.
     */
    private fun handleReceiverPairing(payload: QrConnectionPayload) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Sender Found!")
            .setMessage("Connect to ${payload.deviceName} (${payload.ssid})?")
            .setPositiveButton("Connect") { _, _ ->
                // Start listening/connecting service for incoming file stream from QR sender
                TransferService.startReceiving(
                    context = this,
                    port = payload.port,
                    isGroupOwner = false,
                    host = payload.hostIp
                )

                // Launch Screen 4: TransferActivity
                val intent = Intent(this, TransferActivity::class.java).apply {
                    putExtra(TransferActivity.EXTRA_IS_SENDER, false)
                    putExtra(TransferActivity.EXTRA_FILE_NAME, "Receiving from ${payload.deviceName}")
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
