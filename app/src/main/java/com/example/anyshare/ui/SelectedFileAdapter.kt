package com.example.anyshare.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.anyshare.databinding.ItemSelectedFileBinding
import com.example.anyshare.transfer.TransferMetricsCalculator

data class SelectedFileItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
)

class SelectedFileAdapter(
    private val items: MutableList<SelectedFileItem>,
    private val onRemoveClick: (SelectedFileItem) -> Unit
) : RecyclerView.Adapter<SelectedFileAdapter.FileViewHolder>() {

    fun addFiles(newFiles: List<SelectedFileItem>) {
        // Prevent duplicate URIs
        val existingUris = items.map { it.uri }.toSet()
        val toAdd = newFiles.filter { it.uri !in existingUris }
        items.addAll(toAdd)
        notifyDataSetChanged()
    }

    fun removeFile(item: SelectedFileItem) {
        val index = items.indexOf(item)
        if (index != -1) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun getItems(): List<SelectedFileItem> = items

    fun getTotalSize(): Long = items.sumOf { it.size }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemSelectedFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FileViewHolder(private val binding: ItemSelectedFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SelectedFileItem) {
            binding.tvSelectedFileName.text = item.name
            binding.tvSelectedFileSize.text = TransferMetricsCalculator.formatBytes(item.size)
            binding.tvSelectedIcon.text = getFileEmoji(item.name)

            binding.btnRemoveFile.setOnClickListener {
                onRemoveClick(item)
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
