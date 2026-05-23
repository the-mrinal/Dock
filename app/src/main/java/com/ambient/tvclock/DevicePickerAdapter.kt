package com.ambient.tvclock

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for the Spotify device picker dialog (Stream A, artboard 16).
 *
 * Two row kinds:
 *   • [Row.LaunchLocal] — pinned to the top when a local Spotify app is
 *     installed; tapping launches the host app so it registers itself as
 *     a Connect playback target.
 *   • [Row.Device]      — one row per Spotify Connect device. The active
 *     device shows a tinted 56dp icon tile + "Playing" + VuBars; inactive
 *     devices show a translucent tile + chevron-right.
 */
class DevicePickerAdapter(
    private val onLaunchLocal: (packageName: String) -> Unit,
    private val onDeviceSelected: (device: SpotifyDevice) -> Unit
) : ListAdapter<DevicePickerAdapter.Row, DevicePickerAdapter.Holder>(DIFF) {

    sealed class Row {
        data class LaunchLocal(val packageName: String) : Row()
        data class Device(val device: SpotifyDevice) : Row()

        val rowId: String get() = when (this) {
            is LaunchLocal -> "__launch__:$packageName"
            is Device -> "dev:${device.id}"
        }
    }

    fun submit(devices: List<SpotifyDevice>, localPackage: String?) {
        val rows = buildList {
            if (localPackage != null) add(Row.LaunchLocal(localPackage))
            devices.forEach { add(Row.Device(it)) }
        }
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        when (row) {
            is Row.LaunchLocal -> bindLaunch(holder, row)
            is Row.Device -> bindDevice(holder, row.device)
        }

        val activate = {
            when (row) {
                is Row.LaunchLocal -> onLaunchLocal(row.packageName)
                is Row.Device -> onDeviceSelected(row.device)
            }
        }
        holder.itemView.setOnClickListener { activate() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                activate()
                true
            } else {
                false
            }
        }
    }

    private fun bindLaunch(holder: Holder, row: Row.LaunchLocal) {
        holder.iconTile.setBackgroundResource(R.drawable.bg_device_icon)
        holder.icon.setImageResource(R.drawable.ic_music_note)
        holder.icon.setColorFilter(
            holder.itemView.resources.getColor(R.color.dock_text_sec, null)
        )
        holder.name.text = holder.itemView.resources.getString(R.string.spotify_launch_on_this_tv)
        holder.sub.text = row.packageName
        holder.chevron.visibility = View.VISIBLE
        holder.activeLabel.visibility = View.GONE
    }

    private fun bindDevice(holder: Holder, device: SpotifyDevice) {
        if (device.isActive) {
            holder.iconTile.setBackgroundResource(R.drawable.bg_device_icon_active)
            holder.icon.setColorFilter(
                holder.itemView.resources.getColor(R.color.dock_accent_chartreuse, null)
            )
        } else {
            holder.iconTile.setBackgroundResource(R.drawable.bg_device_icon)
            holder.icon.setColorFilter(
                holder.itemView.resources.getColor(R.color.dock_text_sec, null)
            )
        }
        holder.icon.setImageResource(deviceIconRes(device.type))
        holder.name.text = device.name
        holder.sub.text = device.type.replaceFirstChar { it.uppercase() }
        if (device.isActive) {
            holder.chevron.visibility = View.GONE
            holder.activeLabel.visibility = View.VISIBLE
        } else {
            holder.chevron.visibility = View.VISIBLE
            holder.activeLabel.visibility = View.GONE
        }
    }

    private fun deviceIconRes(type: String): Int = when (type.lowercase()) {
        "tv" -> R.drawable.ic_tv
        "speaker", "audio_dongle", "stb" -> R.drawable.ic_speaker
        "smartphone", "phone" -> R.drawable.ic_phone
        "computer" -> R.drawable.ic_devices
        else -> R.drawable.ic_devices
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val iconTile: View = view.findViewById(R.id.deviceIconTile)
        val icon: ImageView = view.findViewById(R.id.deviceIcon)
        val name: TextView = view.findViewById(R.id.deviceName)
        val sub: TextView = view.findViewById(R.id.deviceSub)
        val chevron: View = view.findViewById(R.id.deviceChevron)
        val activeLabel: View = view.findViewById(R.id.deviceActiveLabel)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.rowId == newItem.rowId

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }
}
