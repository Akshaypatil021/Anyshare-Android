package com.example.anyshare.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.anyshare.MainActivity
import com.example.anyshare.databinding.ActivitySettingsBinding
import com.example.anyshare.utils.ActivityTransitionHelper
import com.example.anyshare.utils.DeviceConfigManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * SettingsActivity: Displays the settings page matching the user's reference UI.
 *
 * Provides configurations for:
 * 1. General:
 *    - Theme: System default, Dark (OLED), White (Light)
 *    - Color: System, Teal Accent, Indigo Accent
 *    - Language: System, English, Hindi
 *    - Animations: Toggle switch (On/Off)
 * 2. Receive:
 *    - Quick Save: Toggle switch
 *    - Quick Save for "Favourites": Toggle switch
 *    - Require PIN: Toggle switch
 *    - Device Name: Edit dialog & update across app
 *    - Destination: SAF Folder chooser or default Downloads/Anyshare
 *    - Save media to gallery: Toggle switch
 *    - Auto Finish: Toggle switch
 * 3. Bottom Navigation:
 *    - Receive -> MainActivity
 *    - Send -> SendActivity
 *    - Settings -> Active
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var deviceConfigManager: DeviceConfigManager

    // SAF directory picker launcher for choosing custom destination folder
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val rawSegment = uri.lastPathSegment ?: "Custom Folder"
            val folderName = if (rawSegment.contains(":")) rawSegment.substringAfter(":") else rawSegment
            val displayName = if (folderName.isNotEmpty()) "($folderName)" else "(Custom Folder)"

            deviceConfigManager.setDestination(displayName, uri.toString())
            binding.tvDestinationValue.text = displayName
            Toast.makeText(this, "Save destination set to $displayName", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceConfigManager = DeviceConfigManager(this)

        setupSystemBarsTheme()

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.tvSettingsTitle.updatePadding(top = systemBars.top + 8)
            binding.scrollSettingsContent.setPadding(
                binding.scrollSettingsContent.paddingLeft,
                binding.scrollSettingsContent.paddingTop,
                binding.scrollSettingsContent.paddingRight,
                systemBars.bottom + 12
            )
            binding.bottomNavigation.setPadding(
                binding.bottomNavigation.paddingLeft,
                binding.bottomNavigation.paddingTop,
                binding.bottomNavigation.paddingRight,
                systemBars.bottom + 8
            )
            insets
        }

        initPreferences()
        setupListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                ActivityTransitionHelper.navigateToTab(this@SettingsActivity, MainActivity::class.java)
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupSystemBarsTheme()
    }

    private fun setupSystemBarsTheme() {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode
    }

    private fun initPreferences() {
        // 1. Theme display
        updateThemeLabel(deviceConfigManager.getThemePreference())

        // 2. Language display
        updateLanguageLabel(deviceConfigManager.getLanguagePreference())

        // 3. Animations toggle
        binding.switchAnimations.isChecked = deviceConfigManager.isAnimationsEnabled()

        // 4. Quick Save switches
        binding.switchQuickSave.isChecked = deviceConfigManager.isQuickSaveEnabled()
        binding.switchQuickSaveFav.isChecked = deviceConfigManager.isQuickSaveFavouritesEnabled()
        binding.switchRequirePin.isChecked = deviceConfigManager.isRequirePinEnabled()

        // 5. Device Name
        updateDeviceNameLabel()

        // 6. Destination
        binding.tvDestinationValue.text = deviceConfigManager.getDestinationName()

        // 7. Save media to gallery & Auto finish
        binding.switchSaveMedia.isChecked = deviceConfigManager.isSaveMediaToGalleryEnabled()
        binding.switchAutoFinish.isChecked = deviceConfigManager.isAutoFinishEnabled()
    }

    private fun updateDeviceNameLabel() {
        binding.tvDeviceNameValue.text = "(${deviceConfigManager.getDeviceNickname()})"
    }

    private fun updateThemeLabel(mode: Int) {
        binding.tvThemeValue.text = when (mode) {
            DeviceConfigManager.THEME_DARK -> "Dark (OLED)"
            DeviceConfigManager.THEME_LIGHT -> "White"
            else -> "System"
        }
    }

    private fun updateLanguageLabel(code: String) {
        binding.tvLanguageValue.text = when (code) {
            "en" -> "English"
            "hi" -> "Hindi"
            else -> "System"
        }
    }

    private fun setupListeners() {
        // Theme selection dialog
        binding.btnTheme.setOnClickListener {
            showThemeSelectionDialog()
        }

        // Color selection dialog
        binding.btnColor.setOnClickListener {
            showColorSelectionDialog()
        }

        // Language selection dialog
        binding.btnLanguage.setOnClickListener {
            showLanguageSelectionDialog()
        }

        // Animations switch
        binding.switchAnimations.setOnCheckedChangeListener { _, isChecked ->
            deviceConfigManager.setAnimationsEnabled(isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Animations enabled" else "Animations disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Quick Save switches
        binding.switchQuickSave.setOnCheckedChangeListener { _, isChecked ->
            deviceConfigManager.setQuickSaveEnabled(isChecked)
        }

        binding.switchQuickSaveFav.setOnCheckedChangeListener { _, isChecked ->
            deviceConfigManager.setQuickSaveFavouritesEnabled(isChecked)
        }

        binding.switchRequirePin.setOnCheckedChangeListener { _, isChecked ->
            deviceConfigManager.setRequirePinEnabled(isChecked)
        }

        // Device Name click (Edit dialog)
        binding.btnDeviceName.setOnClickListener {
            showEditDeviceNameDialog()
        }

        // Destination Folder click
        binding.btnDestination.setOnClickListener {
            showDestinationOptionsDialog()
        }

        // Media & Auto Finish switches
        binding.switchSaveMedia.setOnCheckedChangeListener { _, isChecked ->
            deviceConfigManager.setSaveMediaToGalleryEnabled(isChecked)
        }

        binding.switchAutoFinish.setOnCheckedChangeListener { _, isChecked ->
            deviceConfigManager.setAutoFinishEnabled(isChecked)
        }

        // Bottom Navigation Bar tabs
        binding.navReceive.setOnClickListener {
            ActivityTransitionHelper.navigateToTab(this, MainActivity::class.java)
        }

        binding.navSend.setOnClickListener {
            ActivityTransitionHelper.navigateToTab(this, SendActivity::class.java)
        }

        binding.navSettings.setOnClickListener {
            // Already on settings; scroll to top smoothly
            binding.scrollSettingsContent.smoothScrollTo(0, 0)
        }
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf("System default", "Dark (OLED)", "White (Light)")
        val currentSelection = when (deviceConfigManager.getThemePreference()) {
            DeviceConfigManager.THEME_DARK -> 1
            DeviceConfigManager.THEME_LIGHT -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Theme")
            .setSingleChoiceItems(themes, currentSelection) { dialog, which ->
                val selectedMode = when (which) {
                    1 -> DeviceConfigManager.THEME_DARK
                    2 -> DeviceConfigManager.THEME_LIGHT
                    else -> DeviceConfigManager.THEME_SYSTEM
                }
                deviceConfigManager.setThemePreference(selectedMode)
                updateThemeLabel(selectedMode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showColorSelectionDialog() {
        val colorOptions = arrayOf("System", "Teal Accent (Default)", "Indigo Accent")
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme Color")
            .setItems(colorOptions) { dialog, which ->
                binding.tvColorValue.text = colorOptions[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLanguageSelectionDialog() {
        val languages = arrayOf("System default", "English", "Hindi (हिंदी)")
        val currentSelection = when (deviceConfigManager.getLanguagePreference()) {
            "en" -> 1
            "hi" -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Language")
            .setSingleChoiceItems(languages, currentSelection) { dialog, which ->
                val langCode = when (which) {
                    1 -> "en"
                    2 -> "hi"
                    else -> ""
                }
                deviceConfigManager.setLanguagePreference(langCode)
                updateLanguageLabel(langCode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Requirement: User can change device name from Receive menu in Settings.
     * When updated, saves to DeviceConfigManager and updates across the app.
     */
    private fun showEditDeviceNameDialog() {
        val input = EditText(this).apply {
            setText(deviceConfigManager.getDeviceNickname())
            setSelection(text.length)
            setHint("Enter new device name")
            setPadding(48, 36, 48, 36)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Change Device Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    deviceConfigManager.setDeviceNickname(newName)
                    updateDeviceNameLabel()
                    Toast.makeText(this, "Device name updated to: $newName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Requirement: Option to choose destination folder where receiver saves files,
     * or reset to default Downloads/Anyshare.
     */
    private fun showDestinationOptionsDialog() {
        val options = arrayOf("Select Custom Folder", "Reset to Default (Downloads/Anyshare)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Save Destination Folder")
            .setItems(options) { _, which ->
                if (which == 0) {
                    folderPickerLauncher.launch(null)
                } else {
                    deviceConfigManager.setDestination("(Downloads)", null)
                    binding.tvDestinationValue.text = "(Downloads)"
                    Toast.makeText(this, "Destination reset to Downloads/Anyshare", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
