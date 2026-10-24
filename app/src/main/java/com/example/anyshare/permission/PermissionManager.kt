package com.example.anyshare.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * PermissionManager handles Android runtime permissions dynamically on-demand across
 * Android 8.0 (API 26) to Android 15+ (API 35+).
 * Permissions are requested just-in-time as specific features are used.
 */
class PermissionManager(private val activity: ComponentActivity) {

    private var onPermissionResult: ((allGranted: Boolean, deniedList: List<String>) -> Unit)? = null

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys.toList()
            onPermissionResult?.invoke(denied.isEmpty(), denied)
        }

    companion object {
        /**
         * Permissions needed for Nearby Wi-Fi Direct discovery & peer connection.
         */
        fun getNearbyPermissions(): List<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
        }

        fun hasNearbyPermissions(context: Context): Boolean {
            return getNearbyPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        /**
         * Permissions needed for selecting, reading, or saving files and media.
         */
        fun getStoragePermissions(): List<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                listOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }

        fun hasStoragePermissions(context: Context): Boolean {
            return getStoragePermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        /**
         * Permission needed only when scanning QR codes with camera.
         */
        val CAMERA_PERMISSIONS = listOf(Manifest.permission.CAMERA)

        fun hasCameraPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Notification permission for foreground transfer service on Android 13+.
         */
        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        /**
         * Check if Wi-Fi is enabled on the device.
         */
        fun isWifiEnabled(context: Context): Boolean {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            return wifiManager?.isWifiEnabled == true
        }

        /**
         * Check if Location service is enabled (crucial for P2P scan on Android <= 12).
         */
        fun isLocationServiceEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }
    }

    /**
     * Request a specific subset of permissions dynamically on-demand.
     */
    fun requestPermissions(
        permissions: List<String>,
        onResult: (allGranted: Boolean, deniedList: List<String>) -> Unit
    ) {
        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onResult(true, emptyList())
            return
        }
        this.onPermissionResult = onResult
        permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * Request Nearby Wi-Fi Direct permissions on-demand (e.g. for searching peers or creating P2P group).
     */
    fun requestNearbyPermissions(onResult: (allGranted: Boolean, deniedList: List<String>) -> Unit) {
        requestPermissions(getNearbyPermissions(), onResult)
    }

    /**
     * Request Storage / Media permissions on-demand (e.g. when picking files to send).
     */
    fun requestStoragePermissions(onResult: (allGranted: Boolean, deniedList: List<String>) -> Unit) {
        requestPermissions(getStoragePermissions(), onResult)
    }

    /**
     * Request Camera permission on-demand (e.g. when opening QR scanner).
     */
    fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
        requestPermissions(CAMERA_PERMISSIONS) { allGranted, _ ->
            onResult(allGranted)
        }
    }

    /**
     * Request Notification permission on-demand (Android 13+).
     */
    fun requestNotificationPermission(onResult: (granted: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) { allGranted, _ ->
                onResult(allGranted)
            }
        } else {
            onResult(true)
        }
    }

    /**
     * Legacy helper to check all permissions across app.
     */
    fun getRequiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        list.addAll(CAMERA_PERMISSIONS)
        list.addAll(getNearbyPermissions())
        list.addAll(getStoragePermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    fun hasAllRequiredPermissions(context: Context = activity): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    fun getMissingPermissions(context: Context = activity): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestMissingPermissions(onResult: (allGranted: Boolean, deniedList: List<String>) -> Unit) {
        requestPermissions(getRequiredPermissions(), onResult)
    }

    fun isWifiEnabled(context: Context = activity): Boolean = PermissionManager.isWifiEnabled(context)

    fun isLocationServiceEnabled(context: Context = activity): Boolean = PermissionManager.isLocationServiceEnabled(context)

    fun openAppSettings(context: Context = activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openWifiSettings(context: Context = activity) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openLocationSettings(context: Context = activity) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
