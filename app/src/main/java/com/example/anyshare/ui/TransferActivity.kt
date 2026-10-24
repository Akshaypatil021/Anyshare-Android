package com.example.anyshare.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import android.content.res.Configuration
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.anyshare.databinding.ActivityTransferBinding
import com.example.anyshare.history.HistoryItem
import com.example.anyshare.history.HistoryManager
import com.example.anyshare.service.TransferService
import com.example.anyshare.transfer.TransferMetricsCalculator
import com.example.anyshare.transfer.TransferStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Screen 4: Real-time File Transfer Screen showing live %, MB/s speed, and ETA.
 */
class TransferActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_SENDER = "EXTRA_IS_SENDER"
        const val EXTRA_FILE_NAME = "EXTRA_FILE_NAME"
    }

    private lateinit var binding: ActivityTransferBinding
    private lateinit var historyManager: HistoryManager
    private var isSender: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Keep screen on during heavy file transfer
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        ViewCompat.setOnApplyWindowInsetsListener(binding.transferRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        historyManager = HistoryManager(this)
        isSender = intent.getBooleanExtra(EXTRA_IS_SENDER, false)
        val initialFileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "File Transfer"

        binding.tvTransferHeader.text = if (isSender) "Sending File" else "Receiving File"
        binding.tvFileName.text = initialFileName

        setupListeners()
        observeTransferService()
    }

    private fun setupListeners() {
        binding.btnCancelTransfer.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Cancel Transfer?")
                .setMessage("Are you sure you want to stop this file transfer?")
                .setPositiveButton("Yes, Cancel") { _, _ ->
                    TransferService.cancelTransfer(this)
                    Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Continue Transfer", null)
                .show()
        }
    }

    private fun observeTransferService() {
        lifecycleScope.launch {
            TransferService.transferProgress.collectLatest { progress ->
                if (progress == null) return@collectLatest

                binding.tvFileName.text = progress.fileName
                binding.tvPercentage.text = "${progress.progressPercentage}%"
                binding.progressBar.setProgressCompat(progress.progressPercentage, true)

                val transferredFormatted = TransferMetricsCalculator.formatBytes(progress.bytesTransferred)
                val totalFormatted = TransferMetricsCalculator.formatBytes(progress.totalBytes)
                binding.tvBytesTransferred.text = "$transferredFormatted / $totalFormatted"

                binding.chipSpeed.text = "⚡ ${progress.speedFormatted}"
                binding.chipEta.text = "⏳ ${progress.etaFormatted}"

                // Change icon based on file extension
                binding.tvFileIcon.text = getFileEmoji(progress.fileName)

                val status = progress.status
                binding.tvTransferSubtitle.text = when (status) {
                    is TransferStatus.Connecting -> "Connecting via high-speed P2P link..."
                    is TransferStatus.Handshaking -> "Handshaking with partner..."
                    is TransferStatus.InProgress -> "Streaming file at high speed..."
                    is TransferStatus.Completed -> "Transfer completed successfully!"
                    is TransferStatus.Error -> "Failed: ${status.message}"
                    is TransferStatus.Cancelled -> "Transfer cancelled"
                    else -> "Transferring..."
                }

                when (status) {
                    is TransferStatus.Completed -> {
                        // Log to History
                        historyManager.addHistoryItem(
                            HistoryItem(
                                fileName = progress.fileName,
                                fileSize = progress.totalBytes,
                                mimeType = "*/*",
                                isSender = isSender,
                                fileUriString = status.fileSavedPath
                            )
                        )

                        // Transition to Screen 5: Completed / History Screen
                        val intent = Intent(this@TransferActivity, HistoryActivity::class.java).apply {
                            putExtra(HistoryActivity.EXTRA_JUST_COMPLETED, true)
                            putExtra(HistoryActivity.EXTRA_COMPLETED_FILE_NAME, progress.fileName)
                            putExtra(HistoryActivity.EXTRA_COMPLETED_FILE_SIZE, progress.totalBytes)
                            putExtra(HistoryActivity.EXTRA_COMPLETED_FILE_PATH, status.fileSavedPath)
                        }
                        com.example.anyshare.utils.ActivityTransitionHelper.startActivityWithSlide(this@TransferActivity, intent)
                        finish()
                    }

                    is TransferStatus.Cancelled -> {
                        Toast.makeText(this@TransferActivity, "Transfer cancelled", Toast.LENGTH_SHORT).show()
                        finish()
                    }

                    is TransferStatus.Error -> {
                        AlertDialog.Builder(this@TransferActivity)
                            .setTitle("Transfer Failed")
                            .setMessage(status.message)
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }

                    else -> {}
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        com.example.anyshare.utils.ActivityTransitionHelper.applySlideCloseTransition(this)
    }

    private fun getFileEmoji(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mkv", "avi", "mov" -> "🎬"
            "jpg", "jpeg", "png", "webp", "gif" -> "🖼️"
            "mp3", "wav", "m4a", "flac" -> "🎵"
            "apk" -> "📱"
            "pdf" -> "📕"
            "zip", "rar", "7z", "tar" -> "📦"
            "doc", "docx", "txt" -> "📝"
            else -> "📄"
        }
    }
}
