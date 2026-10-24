package com.example.anyshare.ui

import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.anyshare.databinding.ItemNearbyPeerBinding

class NearbyPeersAdapter(
    private var peers: List<WifiP2pDevice>,
    private val onPeerClick: (WifiP2pDevice) -> Unit
) : RecyclerView.Adapter<NearbyPeersAdapter.PeerViewHolder>() {

    fun updatePeers(newPeers: List<WifiP2pDevice>) {
        peers = newPeers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val binding = ItemNearbyPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(peers[position])
    }

    override fun getItemCount(): Int = peers.size

    inner class PeerViewHolder(private val binding: ItemNearbyPeerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: WifiP2pDevice) {
            val name = if (device.deviceName.isNullOrEmpty()) "Nearby Device" else device.deviceName
            binding.tvPeerName.text = name

            val statusText = when (device.status) {
                WifiP2pDevice.AVAILABLE -> "Available • Tap to Send"
                WifiP2pDevice.INVITED -> "Connecting..."
                WifiP2pDevice.CONNECTED -> "Connected"
                else -> "Ready to pair"
            }
            binding.tvPeerStatus.text = statusText

            binding.root.setOnClickListener {
                onPeerClick(device)
            }

            binding.btnConnectPeer.setOnClickListener {
                onPeerClick(device)
            }
        }
    }
}
