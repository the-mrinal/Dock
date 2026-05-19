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

class QueueTrackAdapter(
    private val onTrackSelected: (SpotifyQueueTrack) -> Unit
) : ListAdapter<SpotifyQueueTrack, QueueTrackAdapter.Holder>(DIFF) {

    fun submit(tracks: List<SpotifyQueueTrack>, onCommit: (() -> Unit)? = null) {
        // ListAdapter.submitList computes its diff on a background executor;
        // the commit callback fires after the new list is fully applied so
        // findViewHolderForAdapterPosition / focus logic can rely on it.
        if (onCommit != null) {
            submitList(tracks, onCommit)
        } else {
            submitList(tracks)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_track, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = getItem(position)
        holder.textTitle.text = track.title
        holder.textArtist.text = track.artist.ifEmpty { "—" }
        AlbumArtLoader.load(track.imageUrl, holder.imageArt)

        val playTrack = {
            if (track.uri.isNotBlank()) {
                onTrackSelected(track)
            }
        }
        holder.itemView.setOnClickListener { playTrack() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                playTrack()
                true
            } else {
                false
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val imageArt: ImageView = view.findViewById(R.id.imageQueueArt)
        val textTitle: TextView = view.findViewById(R.id.textQueueTitle)
        val textArtist: TextView = view.findViewById(R.id.textQueueArtist)

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

        private val DIFF = object : DiffUtil.ItemCallback<SpotifyQueueTrack>() {
            override fun areItemsTheSame(
                oldItem: SpotifyQueueTrack,
                newItem: SpotifyQueueTrack
            ): Boolean = oldItem.uri == newItem.uri && oldItem.title == newItem.title

            override fun areContentsTheSame(
                oldItem: SpotifyQueueTrack,
                newItem: SpotifyQueueTrack
            ): Boolean = oldItem == newItem
        }
    }
}
