package com.ambient.tvclock

import android.graphics.Outline
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ambient.tvclock.ui.CoverDrawable

/**
 * Playlist adapter (Stream A redesign, artboard 14).
 *
 * Renders into the 4-col Browse grid. Each item is a square cover + 22sp
 * title + secondary owner / updated-at line + mono tabular track count
 * (right-justified). Spotify art preferred; deterministic [CoverDrawable]
 * fallback when the playlist has no image.
 */
class PlaylistAdapter(
    private val onPlaylistSelected: (SpotifyPlaylist) -> Unit
) : ListAdapter<SpotifyPlaylist, PlaylistAdapter.Holder>(DIFF) {

    fun submit(playlists: List<SpotifyPlaylist>) {
        submitList(playlists)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = getItem(position)
        val resources = holder.itemView.resources

        holder.textName.text = playlist.name
        // Secondary line — owner where available, otherwise empty (the
        // count occupies the right side, so this line can be blank).
        holder.textMeta.text = playlist.ownerName.ifEmpty { "Playlist" }
        holder.textCount.text = resources.getQuantityString(
            R.plurals.spotify_playlist_track_count,
            playlist.trackCount,
            playlist.trackCount
        )

        // Cover — fallback drawable seeded by playlist id; replace if URL exists.
        holder.imageArt.setImageDrawable(CoverDrawable(playlist.id))
        if (playlist.imageUrl.isNotBlank()) {
            AlbumArtLoader.load(playlist.imageUrl, holder.imageArt)
        }

        // Force cover square (same trick as RecentCardAdapter).
        val cover = holder.imageArt
        cover.post {
            val w = cover.width
            if (w > 0 && cover.layoutParams.height != w) {
                cover.layoutParams = cover.layoutParams.apply { height = w }
                cover.requestLayout()
            }
        }

        val open = { onPlaylistSelected(playlist) }
        holder.itemView.setOnClickListener { open() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                open()
                true
            } else {
                false
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val imageArt: ImageView = view.findViewById(R.id.imagePlaylistArt)
        val textName: TextView = view.findViewById(R.id.textPlaylistName)
        val textMeta: TextView = view.findViewById(R.id.textPlaylistMeta)
        val textCount: TextView = view.findViewById(R.id.textPlaylistCount)

        init {
            imageArt.clipToOutline = true
            imageArt.outlineProvider = ROUND_10
        }
    }

    companion object {
        private val ROUND_10 = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 10f)
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<SpotifyPlaylist>() {
            override fun areItemsTheSame(
                oldItem: SpotifyPlaylist,
                newItem: SpotifyPlaylist
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: SpotifyPlaylist,
                newItem: SpotifyPlaylist
            ): Boolean = oldItem == newItem
        }
    }
}
