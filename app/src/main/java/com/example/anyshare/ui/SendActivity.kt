package com.example.anyshare.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.anyshare.R
import com.example.anyshare.databinding.ActivitySendBinding
import com.example.anyshare.p2p.P2pConnectionState
import com.example.anyshare.p2p.WifiDirectManager
import com.example.anyshare.permission.PermissionManager
import com.example.anyshare.qr.QrCodeGenerator
import com.example.anyshare.qr.QrConnectionPayload
import com.example.anyshare.service.TransferService
import com.example.anyshare.transfer.TransferMetricsCalculator
import androidx.activity.OnBackPressedCallback
import com.example.anyshare.MainActivity
import com.example.anyshare.utils.ActivityTransitionHelper
import com.example.anyshare.utils.DeviceConfigManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Screen 2: Categorized File Picker & Sender QR Creation Screen.
 * Redesigned to match the reference UI with [File, Media, Text, App] categories,
 * continuous nearby device discovery, and top-right QR generation for receiver pairing.
 */
class SendActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding
    private lateinit var fileAdapter: SelectedFileAdapter
    private lateinit var nearbyPeersAdapter: NearbyPeersAdapter
    private lateinit var wifiDirectManager: WifiDirectManager
    private lateinit var permissionManager: PermissionManager

    private var activeQrDialog: AlertDialog? = null
    private var loadingDialog: AlertDialog? = null

    // System file picker launcher for selecting multiple documents/media
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            val selectedItems = uris.map { uri -> resolveFileItem(uri) }
            fileAdapter.addFiles(selectedItems)
            updateSelectionUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup dynamic status bar and navigation bar icon colors (light vs dark theme)
        setupSystemBarsTheme()

        ViewCompat.setOnApplyWindowInsetsListener(binding.sendRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.scrollSendContent.setPadding(
                binding.scrollSendContent.paddingLeft,
                systemBars.top + 8,
                binding.scrollSendContent.paddingRight,
                binding.scrollSendContent.paddingBottom
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

        setupRecyclerViews()
        setupListeners()
        updateSelectionUI()
        observeP2pFlows()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                ActivityTransitionHelper.navigateToTab(this@SendActivity, MainActivity::class.java)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        wifiDirectManager.register()
        // Discover nearby devices on-demand: check or request nearby permission
        if (PermissionManager.hasNearbyPermissions(this)) {
            wifiDirectManager.startDiscovery { _, _ -> }
        } else {
            permissionManager.requestNearbyPermissions { allGranted, _ ->
                if (allGranted) {
                    wifiDirectManager.startDiscovery { _, _ -> }
                } else {
                    Toast.makeText(
                        this,
                        "Nearby devices permission is required to discover peer devices",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        wifiDirectManager.stopDiscovery()
        wifiDirectManager.unregister()
    }

    override fun onResume() {
        super.onResume()
        setupSystemBarsTheme()
        if (!PermissionManager.isWifiEnabled(this)) {
            Toast.makeText(this, "Please turn on Wi-Fi for direct transfer", Toast.LENGTH_SHORT).show()
        } else if (!PermissionManager.isLocationServiceEnabled(this)) {
            Toast.makeText(this, "Please turn on Location (GPS) for peer discovery on this Android version", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        wifiDirectManager.stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeQrDialog?.dismiss()
        loadingDialog?.dismiss()
    }

    private fun setupSystemBarsTheme() {
        val isNightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode
    }

    private fun setupRecyclerViews() {
        // 1. Selected Files List
        fileAdapter = SelectedFileAdapter(mutableListOf()) { itemToRemove ->
            fileAdapter.removeFile(itemToRemove)
            updateSelectionUI()
        }
        binding.recyclerSelectedFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerSelectedFiles.adapter = fileAdapter

        // 2. Nearby Discovered Peers List
        nearbyPeersAdapter = NearbyPeersAdapter(emptyList()) { selectedDevice ->
            val files = fileAdapter.getItems()
            if (files.isEmpty()) {
                Toast.makeText(this, "Please select files to send first", Toast.LENGTH_SHORT).show()
                return@NearbyPeersAdapter
            }

            showLoadingDialog("Connecting to ${selectedDevice.deviceName}...\nPlease accept connection on receiving phone.")
            wifiDirectManager.connectToDevice(selectedDevice.deviceAddress) { success, errorMsg ->
                if (!success) {
                    hideLoadingDialog()
                    Toast.makeText(this, "Failed to connect: $errorMsg", Toast.LENGTH_SHORT).show()
                }
                // Keep dialog open until P2pConnectionState changes to ConnectedAsClient/GroupOwner
            }
        }
        binding.recyclerNearbyPeers.layoutManager = LinearLayoutManager(this)
        binding.recyclerNearbyPeers.adapter = nearbyPeersAdapter
    }

    private fun launchFilePickerWithPermission(mimeTypes: Array<String>) {
        if (PermissionManager.hasStoragePermissions(this)) {
            filePickerLauncher.launch(mimeTypes)
        } else {
            permissionManager.requestStoragePermissions { allGranted, _ ->
                if (allGranted) {
                    filePickerLauncher.launch(mimeTypes)
                } else {
                    Toast.makeText(
                        this,
                        "Storage/Media permission is required to choose files",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun setupListeners() {
        // Selection Category 1: File
        binding.btnCatFile.setOnClickListener {
            launchFilePickerWithPermission(arrayOf("*/*"))
        }

        // Selection Category 2: Media (Images & Videos)
        binding.btnCatMedia.setOnClickListener {
            launchFilePickerWithPermission(arrayOf("image/*", "video/*"))
        }

        // Selection Category 3: Text (Note - no storage permission required)
        binding.btnCatText.setOnClickListener {
            showTextSenderDialog()
        }

        // Selection Category 4: App (APKs)
        binding.btnCatApp.setOnClickListener {
            launchFilePickerWithPermission(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream"
                )
            )
        }

        // Clear Selected Files
        binding.btnClearSelected.setOnClickListener {
            fileAdapter.clear()
            updateSelectionUI()
        }

        // Refresh Nearby Devices
        binding.btnRefreshPeers.setOnClickListener {
            it.animate().rotationBy(360f).setDuration(500).start()
            if (PermissionManager.hasNearbyPermissions(this)) {
                wifiDirectManager.startDiscovery { _, _ -> }
                Toast.makeText(this, "Searching for nearby devices...", Toast.LENGTH_SHORT).show()
            } else {
                permissionManager.requestNearbyPermissions { allGranted, _ ->
                    if (allGranted) {
                        wifiDirectManager.startDiscovery { _, _ -> }
                        Toast.makeText(this, "Searching for nearby devices...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Generate Sender QR Code (QR Icon)
        binding.btnShowQrSender.setOnClickListener {
            if (PermissionManager.hasNearbyPermissions(this)) {
                handleGenerateQr()
            } else {
                permissionManager.requestNearbyPermissions { allGranted, _ ->
                    if (allGranted) {
                        handleGenerateQr()
                    }
                }
            }
        }

        // Troubleshoot link click
        binding.tvTroubleshoot.setOnClickListener {
            showTroubleshootDialog()
        }

        // Bottom Navigation Tabs
        binding.navReceive.setOnClickListener {
            ActivityTransitionHelper.navigateToTab(this, MainActivity::class.java)
        }

        binding.navSend.setOnClickListener {
            binding.scrollSendContent.smoothScrollTo(0, 0)
        }

        binding.navSettings.setOnClickListener {
            ActivityTransitionHelper.navigateToTab(this, SettingsActivity::class.java)
        }
    }

    /**
     * Requirement: User clicks the QR icon to generate a unique QR code.
     * The receiver phone can scan this to pair and accept the incoming files.
     */
    private fun handleGenerateQr() {
        val files = fileAdapter.getItems()
        if (files.isEmpty()) {
            Toast.makeText(this, "Please select at least one file first", Toast.LENGTH_SHORT).show()
            return
        }

        showLoadingDialog("Creating Wi-Fi Direct Group & Generating QR...")
        wifiDirectManager.createAutonomousGroup { success, error ->
            hideLoadingDialog()
            if (!success) {
                Toast.makeText(this, "Failed to start P2P group: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Dialog for entering or pasting plain text to send as a text note.
     */
    private fun showTextSenderDialog() {
        val input = EditText(this).apply {
            hint = "Type or paste text message..."
            setPadding(50, 40, 50, 40)
            minLines = 3
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Send Text Note")
            .setView(input)
            .setPositiveButton("Add Note") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    try {
                        val file = File(cacheDir, "note_${System.currentTimeMillis()}.txt")
                        file.writeText(text)
                        val uri = Uri.fromFile(file)
                        val item = SelectedFileItem(uri, file.name, file.length(), "text/plain")
                        fileAdapter.addFiles(listOf(item))
                        updateSelectionUI()
                        Toast.makeText(this, "Text note added", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to create note", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTroubleshootDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Troubleshoot Nearby Sharing")
            .setMessage(
                "If devices are not appearing in Nearby devices:\n\n" +
                        "1. Ensure both devices have Wi-Fi turned on.\n\n" +
                        "2. Ensure Location/GPS is turned on (required for Android discovery).\n\n" +
                        "3. Both phones should be in close physical proximity.\n\n" +
                        "💡 Tip: You can also tap the QR icon at the top right to generate a QR Code. The receiver can scan it directly without waiting for discovery!"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSettingsDialog() {
        val deviceConfigManager = DeviceConfigManager(this)
        val hwProfile = com.example.anyshare.utils.HardwareCapabilityHelper.getHardwareSummary(this)
        val bandModeText = when (deviceConfigManager.getWifiBandPreference()) {
            com.example.anyshare.utils.DeviceConfigManager.BAND_5GHZ -> "5 GHz Turbo"
            com.example.anyshare.utils.DeviceConfigManager.BAND_2GHZ -> "2.4 GHz Compatible"
            else -> "⚡ Dynamic Auto (5GHz / 2.4GHz)"
        }

        val message = """
            📱 Device Name: ${deviceConfigManager.getDeviceNickname()} (${deviceConfigManager.getDeviceTag()})
            
            ⚙️ Hardware Profile: $hwProfile
            
            ⚡ Band Mode: $bandModeText
            
            📂 Save Location: Downloads/Anyshare
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("Anyshare Settings")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun observeP2pFlows() {
        // Observe Connection State (Group Creation & Connected Events)
        lifecycleScope.launch {
            wifiDirectManager.connectionState.collectLatest { state ->
                when (state) {
                    is P2pConnectionState.GroupOwnerReady -> {
                        hideLoadingDialog()
                        displayQrDialog(state)
                    }

                    is P2pConnectionState.ConnectedAsGroupOwner -> {
                        hideLoadingDialog()
                        activeQrDialog?.dismiss()
                        startTransferringFiles(state.hostAddress, isGroupOwner = true)
                    }

                    is P2pConnectionState.ConnectedAsClient -> {
                        hideLoadingDialog()
                        activeQrDialog?.dismiss()
                        startTransferringFiles(state.groupOwnerAddress, isGroupOwner = false)
                    }

                    is P2pConnectionState.Error -> {
                        hideLoadingDialog()
                        Toast.makeText(this@SendActivity, state.message, Toast.LENGTH_SHORT).show()
                    }

                    else -> {}
                }
            }
        }

        // Continuously observe discovered peers and update list
        lifecycleScope.launch {
            wifiDirectManager.peersList.collectLatest { peers ->
                if (peers.isNotEmpty()) {
                    binding.layoutNearbyEmpty.visibility = View.GONE
                    binding.recyclerNearbyPeers.visibility = View.VISIBLE
                    nearbyPeersAdapter.updatePeers(peers)
                } else {
                    binding.layoutNearbyEmpty.visibility = View.VISIBLE
                    binding.recyclerNearbyPeers.visibility = View.GONE
                }
            }
        }
    }

    private fun displayQrDialog(state: P2pConnectionState.GroupOwnerReady) {
        if (isFinishing || isDestroyed) return

        try {
            val payload = QrConnectionPayload(
                ssid = state.ssid,
                passphrase = state.passphrase,
                hostIp = state.hostIp,
                port = state.port,
                token = state.securityToken,
                pinCode = state.pinCode,
                deviceName = wifiDirectManager.thisDevice.value?.deviceName ?: "Anyshare Sender"
            )

            val qrBitmap = QrCodeGenerator.generateQrBitmap(payload.toJson(), 600)

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qr_display, null)
            val ivQr = dialogView.findViewById<ImageView>(R.id.ivQrCode)
            val chipPin = dialogView.findViewById<Chip>(R.id.chipPinCode)
            val chipSsid = dialogView.findViewById<Chip>(R.id.chipSsid)
            val btnClose = dialogView.findViewById<View>(R.id.btnCloseQr)

            ivQr.setImageBitmap(qrBitmap)
            chipPin.text = "PIN: ${state.pinCode}"
            chipSsid.text = state.ssid

            activeQrDialog?.dismiss()
            activeQrDialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            btnClose.setOnClickListener {
                activeQrDialog?.dismiss()
                wifiDirectManager.disconnectAndRemoveGroup()
            }

            if (!isFinishing && !isDestroyed) {
                activeQrDialog?.show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show QR code: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTransferringFiles(targetHost: String, isGroupOwner: Boolean) {
        val selected = fileAdapter.getItems()
        if (selected.isEmpty()) return

        val fileUris = selected.map { it.uri }
        TransferService.startSendingBatch(
            context = this,
            fileUris = fileUris,
            host = targetHost,
            port = 8888,
            isGroupOwner = isGroupOwner
        )

        val intent = Intent(this, TransferActivity::class.java).apply {
            putExtra(TransferActivity.EXTRA_IS_SENDER, true)
            putExtra(
                TransferActivity.EXTRA_FILE_NAME,
                if (selected.size == 1) selected.first().name else "${selected.size} files"
            )
        }
        ActivityTransitionHelper.startActivityWithSlide(this, intent)
        finish()
    }

    private fun updateSelectionUI() {
        val items = fileAdapter.getItems()
        val totalSize = fileAdapter.getTotalSize()

        if (items.isEmpty()) {
            binding.recyclerSelectedFiles.visibility = View.GONE
            binding.btnClearSelected.visibility = View.GONE
            binding.tvSelectedSummary.text = "0 files selected (0 B)"
        } else {
            binding.recyclerSelectedFiles.visibility = View.VISIBLE
            binding.btnClearSelected.visibility = View.VISIBLE
            val sizeFormatted = TransferMetricsCalculator.formatBytes(totalSize)
            binding.tvSelectedSummary.text = "${items.size} file(s) selected ($sizeFormatted)"
        }
    }

    private fun resolveFileItem(uri: Uri): SelectedFileItem {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        val mimeType = contentResolver.getType(uri) ?: "*/*"

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}

        if (size <= 0L) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val statSize = pfd.statSize
                    if (statSize > 0) size = statSize
                }
            } catch (_: Exception) {}
        }

        if (size <= 0L) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val avail = stream.available().toLong()
                    if (avail > 0) size = avail
                }
            } catch (_: Exception) {}
        }

        return SelectedFileItem(uri, name, size, mimeType)
    }

    private fun showLoadingDialog(message: String) {
        loadingDialog?.dismiss()
        loadingDialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .create()
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }
}
