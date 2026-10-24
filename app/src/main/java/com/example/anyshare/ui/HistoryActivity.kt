package com.example.anyshare.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import android.content.res.Configuration
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.anyshare.MainActivity
import com.example.anyshare.databinding.ActivityHistoryBinding
import com.example.anyshare.history.HistoryManager
import com.example.anyshare.storage.StorageManager
import com.example.anyshare.transfer.TransferMetricsCalculator

/**
 * Screen 5: Completed / Transfer History Screen.
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_JUST_COMPLETED = "EXTRA_JUST_COMPLETED"
        const val EXTRA_COMPLETED_FILE_NAME = "EXTRA_COMPLETED_FILE_NAME"
        const val EXTRA_COMPLETED_FILE_SIZE = "EXTRA_COMPLETED_FILE_SIZE"
        const val EXTRA_COMPLETED_FILE_PATH = "EXTRA_COMPLETED_FILE_PATH"
    }

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyManager: HistoryManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        ViewCompat.setOnApplyWindowInsetsListener(binding.historyRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        historyManager = HistoryManager(this)

        setupRecyclerView()
        setupListeners()
        loadHistoryList()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(emptyList()) { item ->
            if (!item.fileUriString.isNullOrEmpty()) {
                val uri = if (item.fileUriString.startsWith("content://")) {
                    Uri.parse(item.fileUriString)
                } else {
                    Uri.fromFile(java.io.File(item.fileUriString))
                }
                val opened = StorageManager.openFile(this, uri, item.mimeType)
                if (!opened) {
                    Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "File: ${item.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnOpenFolder.setOnClickListener {
            val opened = StorageManager.openDownloadsFolder(this)
            if (!opened) {
                Toast.makeText(this, "Saved in Downloads/Anyshare", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDeleteHistory.setOnClickListener {
            val items = historyManager.getHistory()
            if (items.isEmpty()) {
                Toast.makeText(this, "History is already empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Delete History")
                .setMessage("Are you sure you want to clear all transfer history logs?")
                .setPositiveButton("Delete") { _, _ ->
                    historyManager.clearHistory()
                    loadHistoryList()
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadHistoryList() {
        val items = historyManager.getHistory()
        if (items.isEmpty()) {
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
        } else {
            binding.tvEmptyHistory.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
            adapter.updateList(items)
        }
    }

    override fun finish() {
        super.finish()
        com.example.anyshare.utils.ActivityTransitionHelper.applySlideCloseTransition(this)
    }
}
