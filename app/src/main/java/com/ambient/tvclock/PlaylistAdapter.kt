package com.ambient.tvclock

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private val onPlaylistSelected: (SpotifyPlaylist) -> Unit
) : ListAdapter<SpotifyPlaylist, PlaylistAdapter.Holder>(DIFF) {

    fun submit(playlists: List<SpotifyPlaylist>, onCommit: (() -> Unit)? = null) {
        // ListAdapter.submitList computes its diff on a background executor;
        // the commit callback fires after the new list is fully applied so
        // findViewHolderForAdapterPosition / focus logic can rely on it.
        if (onCommit != null) {
            submitList(playlists, onCommit)
        } else {
            submitList(playlists)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = getItem(position)
        holder.textName.text = playlist.name
        val resources = holder.itemView.resources
        holder.textMeta.text = resources.getQuantityString(
            R.plurals.spotify_playlist_track_count,
            playlist.trackCount,
            playlist.trackCount
        )
        AlbumArtLoader.load(playlist.imageUrl, holder.imageArt)

        // Rows are focusable+clickable, so DPAD_CENTER/ENTER trigger this
        // click listener natively — no OnKeyListener needed.
        holder.itemView.setOnClickListener { onPlaylistSelected(playlist) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val imageArt: ImageView = view.findViewById(R.id.imagePlaylistArt)
        val textName: TextView = view.findViewById(R.id.textPlaylistName)
        val textMeta: TextView = view.findViewById(R.id.textPlaylistMeta)

        init {
            imageArt.clipToOutline = true
            imageArt.outlineProvider = ROUND_6
        }
    }

    companion object {
        private val ROUND_6 = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 6f)
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
