package com.ambient.tvclock.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ambient.tvclock.R

/**
 * Drives the 6-item Settings rail. The adapter stays dumb — the host activity
 * passes a list and a "selected" index, plus a callback fired when the user
 * activates a row (OK or click). D-pad RIGHT off the rail moves into the
 * content frame and is wired in `SettingsActivity` rather than here.
 */
class SettingsRailAdapter(
    private val groups: List<SettingsGroup>,
    private val onGroupSelected: (SettingsGroup) -> Unit,
) : RecyclerView.Adapter<SettingsRailAdapter.VH>() {

    private var selectedId: String = groups.first().id

    fun setSelected(id: String) {
        if (id == selectedId) return
        val prev = groups.indexOfFirst { it.id == selectedId }
        val next = groups.indexOfFirst { it.id == id }
        selectedId = id
        if (prev >= 0) notifyItemChanged(prev)
        if (next >= 0) notifyItemChanged(next)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_rail, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = groups[position]
        holder.bind(group, group.id == selectedId, onGroupSelected)
    }

    override fun getItemCount(): Int = groups.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val surface: LinearLayout = itemView.findViewById(R.id.railItemSurface)
        private val icon: ImageView = itemView.findViewById(R.id.railItemIcon)
        private val label: TextView = itemView.findViewById(R.id.railItemLabel)

        fun bind(
            group: SettingsGroup,
            isSelected: Boolean,
            onActivate: (SettingsGroup) -> Unit,
        ) {
            val ctx = itemView.context
            label.text = ctx.getString(group.labelRes)
            icon.setImageResource(group.iconRes)
            surface.isSelected = isSelected
            val labelColor = if (isSelected) {
                ContextCompat.getColor(ctx, R.color.dock_text_pri)
            } else {
                0x8CFFFFFF.toInt()
            }
            label.setTextColor(labelColor)
            icon.setColorFilter(labelColor)

            surface.setOnClickListener { onActivate(group) }
            // When focus lands on this row, switch the content fragment too —
            // matches the HTML behaviour where the rail and content scroll in
            // sync without an extra "OK" press.
            surface.setOnFocusChangeListener { _, focused ->
                if (focused) onActivate(group)
            }
        }
    }
}

/** Single entry in the rail. Mirrors the HTML `SETTINGS_GROUPS` shape. */
data class SettingsGroup(
    val id: String,
    val labelRes: Int,
    val iconRes: Int,
    val titleRes: Int,
    val eyebrowRes: Int,
)

object SettingsGroups {
    const val ID_CALENDAR = "calendar"
    const val ID_MUSIC = "music"
    const val ID_MIRRORING = "mirroring"
    const val ID_VPN = "vpn"
    const val ID_DISPLAY = "display"
    const val ID_ABOUT = "about"

    val ALL: List<SettingsGroup> = listOf(
        SettingsGroup(
            ID_CALENDAR,
            R.string.settings_rail_calendar,
            R.drawable.ic_settings_calendar,
            R.string.settings_calendar_title,
            R.string.settings_calendar_eyebrow,
        ),
        SettingsGroup(
            ID_MUSIC,
            R.string.settings_rail_music,
            R.drawable.ic_settings_music,
            R.string.settings_music_title,
            R.string.settings_music_eyebrow,
        ),
        SettingsGroup(
            ID_MIRRORING,
            R.string.settings_rail_mirroring,
            R.drawable.ic_settings_cast,
            R.string.settings_mirror_title,
            R.string.settings_mirror_eyebrow,
        ),
        SettingsGroup(
            ID_VPN,
            R.string.settings_rail_vpn,
            R.drawable.ic_settings_shield,
            R.string.settings_vpn_title,
            R.string.settings_vpn_eyebrow,
        ),
        SettingsGroup(
            ID_DISPLAY,
            R.string.settings_rail_display,
            R.drawable.ic_settings_screen,
            R.string.settings_display_title,
            R.string.settings_display_eyebrow,
        ),
        SettingsGroup(
            ID_ABOUT,
            R.string.settings_rail_about,
            R.drawable.ic_settings_info,
            R.string.settings_about_title,
            R.string.settings_about_eyebrow,
        ),
    )
}
