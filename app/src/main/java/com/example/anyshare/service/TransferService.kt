package com.example.anyshare.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.anyshare.R
import com.example.anyshare.storage.StorageManager
import com.example.anyshare.transfer.FileReceiver
import com.example.anyshare.transfer.FileSender
import com.example.anyshare.transfer.TransferProgress
import com.example.anyshare.transfer.TransferStatus
import com.example.anyshare.ui.TransferActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service ensuring continuous file transfers without OS throttling or background kills.
 */
class TransferService : Service() {

    companion object {
        private const val TAG = "TransferService"
        const val CHANNEL_ID = "anyshare_transfer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SEND = "ACTION_START_SEND"
        const val ACTION_START_RECEIVE = "ACTION_START_RECEIVE"
        const val ACTION_CANCEL = "ACTION_CANCEL"

        const val EXTRA_FILE_URI = "EXTRA_FILE_URI"
        const val EXTRA_FILE_URIS = "EXTRA_FILE_URIS"
        const val EXTRA_TARGET_HOST = "EXTRA_TARGET_HOST"
        const val EXTRA_TARGET_PORT = "EXTRA_TARGET_PORT"
        const val EXTRA_IS_GROUP_OWNER = "EXTRA_IS_GROUP_OWNER"

        // Observable StateFlow for UI screens
        private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
        val transferProgress: StateFlow<TransferProgress?> = _transferProgress.asStateFlow()

        fun startSending(
            context: Context,
            fileUri: Uri,
            host: String = "192.168.49.1",
            port: Int = 8888,
            isGroupOwner: Boolean = false
        ) {
            startSendingBatch(context, listOf(fileUri), host, port, isGroupOwner)
        }

        fun startSendingBatch(
            context: Context,
            fileUris: List<Uri>,
            host: String = "192.168.49.1",
            port: Int = 8888,
            isGroupOwner: Boolean = false
        ) {
            if (fileUris.isEmpty()) return

            val firstUri = fileUris.first()
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_SEND
                data = firstUri

                // Critical: Add ClipData containing all file URIs with read permission flag
                val clip = ClipData.newUri(context.contentResolver, "file", firstUri)
                for (i in 1 until fileUris.size) {
                    clip.addItem(ClipData.Item(fileUris[i]))
                }
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                putStringArrayListExtra(EXTRA_FILE_URIS, ArrayList(fileUris.map { it.toString() }))
                putExtra(EXTRA_FILE_URI, firstUri.toString())
                putExtra(EXTRA_TARGET_HOST, host)
                putExtra(EXTRA_TARGET_PORT, port)
                putExtra(EXTRA_IS_GROUP_OWNER, isGroupOwner)
            }

            Log.d(TAG, "startSendingBatch: ${fileUris.size} file(s) to $host:$port (GO=$isGroupOwner)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startReceiving(
            context: Context,
            port: Int = 8888,
            isGroupOwner: Boolean = true,
            host: String = "192.168.49.1"
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_RECEIVE
                putExtra(EXTRA_TARGET_PORT, port)
                putExtra(EXTRA_IS_GROUP_OWNER, isGroupOwner)
                putExtra(EXTRA_TARGET_HOST, host)
            }
            Log.d(TAG, "startReceiving: listening on $port (GO=$isGroupOwner, host=$host)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelTransfer(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var notificationManager: NotificationManager
    private lateinit var wakeLockManager: WakeLockManager

    private var activeSender: FileSender? = null
    private var activeReceiver: FileReceiver? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        wakeLockManager = WakeLockManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SEND -> {
                val uriStrings = intent.getStringArrayListExtra(EXTRA_FILE_URIS)
                    ?: intent.getStringExtra(EXTRA_FILE_URI)?.let { arrayListOf(it) }
                val host = intent.getStringExtra(EXTRA_TARGET_HOST) ?: "192.168.49.1"
                val port = intent.getIntExtra(EXTRA_TARGET_PORT, 8888)
                val isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, false)

                if (!uriStrings.isNullOrEmpty()) {
                    val fileUris = uriStrings.map { Uri.parse(it) }
                    handleStartSend(fileUris, host, port, isGroupOwner)
                }
            }

            ACTION_START_RECEIVE -> {
                val port = intent.getIntExtra(EXTRA_TARGET_PORT, 8888)
                val isGroupOwner = intent.getBooleanExtra(EXTRA_IS_GROUP_OWNER, true)
                val host = intent.getStringExtra(EXTRA_TARGET_HOST) ?: "192.168.49.1"
                handleStartReceive(port, isGroupOwner, host)
            }

            ACTION_CANCEL -> {
                handleCancel()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStartSend(fileUris: List<Uri>, host: String, port: Int, isGroupOwner: Boolean) {
        // Cancel any lingering receiver/sender to free the port
        activeReceiver?.cancel()
        activeSender?.cancel()

        wakeLockManager.acquireLocks()
        startForegroundWithNotification("Preparing transfer...", 0, "-- MB/s")

        activeSender = FileSender(this, host, port, isGroupOwner)

        serviceScope.launch {
            Log.d(TAG, "Launching activeSender.sendFiles for ${fileUris.size} files...")
            val result = activeSender?.sendFiles(fileUris) { progress ->
                _transferProgress.value = progress
                updateNotification(
                    title = "Sending ${progress.fileName} (${progress.progressPercentage}%)",
                    text = "${progress.speedFormatted} • ${progress.etaFormatted}",
                    progress = progress.progressPercentage
                )

                if (progress.status is TransferStatus.Completed) {
                    onTransferFinished("Transfer Complete! File(s) sent successfully.")
                } else if (progress.status is TransferStatus.Cancelled || progress.status is TransferStatus.Error) {
                    onTransferFinished("Transfer stopped.")
                }
            }
            if (result?.isFailure == true) {
                val err = result.exceptionOrNull()?.message ?: "Transfer failed"
                Log.e(TAG, "activeSender result failed: $err")
                onTransferFinished(err)
            }
        }
    }

    private fun handleStartReceive(port: Int, isGroupOwner: Boolean, host: String) {
        // Cancel any lingering receiver/sender to free the port
        activeSender?.cancel()
        activeReceiver?.cancel()

        wakeLockManager.acquireLocks()
        startForegroundWithNotification("Waiting for incoming files...", 0, "Connecting on port $port")

        activeReceiver = FileReceiver(this, port, isGroupOwner, host)

        serviceScope.launch {
            Log.d(TAG, "Launching activeReceiver.startServerAndReceive on port $port...")
            val result = activeReceiver?.startServerAndReceive(
                onHeaderReceived = { metadata ->
                    true
                },
                onProgress = { progress ->
                    _transferProgress.value = progress
                    updateNotification(
                        title = "Receiving ${progress.fileName} (${progress.progressPercentage}%)",
                        text = "${progress.speedFormatted} • ${progress.etaFormatted}",
                        progress = progress.progressPercentage
                    )

                    if (progress.status is TransferStatus.Completed) {
                        onTransferFinished("Files received & saved to Downloads/Anyshare!")
                    } else if (progress.status is TransferStatus.Cancelled || progress.status is TransferStatus.Error) {
                        onTransferFinished("Receive stopped.")
                    }
                }
            )
            if (result?.isFailure == true) {
                val err = result.exceptionOrNull()?.message ?: "Receive failed"
                Log.e(TAG, "activeReceiver result failed: $err")
                onTransferFinished(err)
            }
        }
    }

    private fun handleCancel() {
        activeSender?.cancel()
        activeReceiver?.cancel()
        onTransferFinished("Transfer cancelled by user.")
    }

    private fun onTransferFinished(message: String) {
        Log.d(TAG, "onTransferFinished: $message")
        wakeLockManager.releaseLocks()
        activeSender = null
        activeReceiver = null

        // Update notification to inform user before shutting down
        val finalNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anyshare")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, finalNotification)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(title: String, progress: Int, text: String) {
        val notification = buildNotification(title, text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String, progress: Int) {
        val notification = buildNotification(title, text, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setContentTitle(title)
            setContentText(text)
            setSmallIcon(R.mipmap.ic_launcher)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(100, progress, false)

            // Tap notification to open TransferActivity
            val contentIntent = PendingIntent.getActivity(
                this@TransferService,
                0,
                Intent(this@TransferService, TransferActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            setContentIntent(contentIntent)

            // Cancel Action button
            val cancelIntent = PendingIntent.getService(
                this@TransferService,
                1,
                Intent(this@TransferService, TransferService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_IMMUTABLE
            )
            addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
        }.build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfer Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress, speed and ETA for active file transfers"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLockManager.releaseLocks()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
