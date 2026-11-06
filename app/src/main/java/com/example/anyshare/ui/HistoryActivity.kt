package com.example.anyshare.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.anyshare.R
import com.example.anyshare.data.local.HistoryEntity
import com.example.anyshare.databinding.ActivityHistoryBinding
import com.example.anyshare.history.HistoryItem
import com.example.anyshare.history.HistoryManager
import com.example.anyshare.storage.StorageManager
import com.example.anyshare.transfer.TransferMetricsCalculator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Screen 5: Completed / Transfer History Screen with Room Database,
 * Category Filtering (All, Media, Docs, Apps, Notes), and Real-time Search.
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

    private var currentCategory = HistoryEntity.CATEGORY_ALL
    private var currentSearchQuery = ""
    private var historyObservationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isNightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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
        setupSearch()
        setupCategories()
        observeSummaryStats()
        observeHistory()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            items = emptyList(),
            onItemClick = { item ->
                if (!item.fileUriString.isNullOrEmpty()) {
                    val uri = if (item.fileUriString.startsWith("content://")) {
                        Uri.parse(item.fileUriString)
                    } else {
                        Uri.fromFile(File(item.fileUriString))
                    }
                    val opened = StorageManager.openFile(this, uri, item.mimeType)
                    if (!opened) {
                        Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "File: ${item.fileName}", Toast.LENGTH_SHORT).show()
                }
            },
            onItemDelete = { item ->
                AlertDialog.Builder(this)
                    .setTitle("Delete Record")
                    .setMessage("Remove '${item.fileName}' from transfer history?")
                    .setPositiveButton("Delete") { _, _ ->
                        historyManager.deleteItem(item.dbId)
                        Toast.makeText(this, "Record removed", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
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
            AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to clear all transfer history logs?")
                .setPositiveButton("Clear All") { _, _ ->
                    historyManager.clearHistory {
                        runOnUiThread {
                            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupSearch() {
        binding.etHistorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                currentSearchQuery = query
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                observeHistory()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etHistorySearch.text.clear()
        }
    }

    private fun setupCategories() {
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCategory = when {
                checkedIds.contains(R.id.chipCatMedia) -> HistoryEntity.CATEGORY_MEDIA
                checkedIds.contains(R.id.chipCatDocs) -> HistoryEntity.CATEGORY_DOCUMENT
                checkedIds.contains(R.id.chipCatApps) -> HistoryEntity.CATEGORY_APP
                checkedIds.contains(R.id.chipCatNotes) -> HistoryEntity.CATEGORY_NOTE
                else -> HistoryEntity.CATEGORY_ALL
            }
            observeHistory()
        }
    }

    private fun observeHistory() {
        historyObservationJob?.cancel()
        historyObservationJob = lifecycleScope.launch {
            historyManager.getHistoryFlow(currentCategory, currentSearchQuery).collectLatest { items ->
                if (items.isEmpty()) {
                    binding.layoutEmptyHistory.visibility = View.VISIBLE
                    binding.recyclerHistory.visibility = View.GONE
                    updateEmptyMessage()
                } else {
                    binding.layoutEmptyHistory.visibility = View.GONE
                    binding.recyclerHistory.visibility = View.VISIBLE
                    adapter.updateList(items)
                }
            }
        }
    }

    private fun updateEmptyMessage() {
        if (currentSearchQuery.isNotEmpty()) {
            binding.tvEmptyHistory.text = "No results found"
            binding.tvEmptyHistorySubtitle.text = "No files match \"$currentSearchQuery\""
        } else {
            val catName = when (currentCategory) {
                HistoryEntity.CATEGORY_MEDIA -> "Media"
                HistoryEntity.CATEGORY_DOCUMENT -> "Document"
                HistoryEntity.CATEGORY_APP -> "App"
                HistoryEntity.CATEGORY_NOTE -> "Note"
                else -> "Transfer"
            }
            binding.tvEmptyHistory.text = "No $catName files"
            binding.tvEmptyHistorySubtitle.text = "Shared $catName files will appear here"
        }
    }

    private fun observeSummaryStats() {
        lifecycleScope.launch {
            historyManager.getSummaryStats().collectLatest { (count, totalBytes) ->
                val sizeFormatted = TransferMetricsCalculator.formatBytes(totalBytes)
                binding.tvHistorySummary.text = if (count > 0) {
                    "$count file(s) transferred • $sizeFormatted"
                } else {
                    "0 files transferred"
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        com.example.anyshare.utils.ActivityTransitionHelper.applySlideCloseTransition(this)
    }
}
