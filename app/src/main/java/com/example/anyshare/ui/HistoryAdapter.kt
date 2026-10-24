package com.example.anyshare.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.anyshare.databinding.ItemHistoryBinding
import com.example.anyshare.history.HistoryItem
import com.example.anyshare.transfer.TransferMetricsCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onItemClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    fun updateList(newList: List<HistoryItem>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryItem) {
            binding.tvItemFileName.text = item.fileName

            val dateStr = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                .format(Date(item.timestamp))
            val sizeStr = TransferMetricsCalculator.formatBytes(item.fileSize)

            binding.tvItemDirection.text = if (item.isSender) "Sent • " else "Received • "
            binding.tvItemDetails.text = "$sizeStr • $dateStr"
            binding.tvItemIcon.text = getFileEmoji(item.fileName)

            binding.root.setOnClickListener {
                onItemClick(item)
            }
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
}
