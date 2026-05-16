package com.ambient.tvclock

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QueueTrackAdapter(
    private val onTrackSelected: (SpotifyQueueTrack) -> Unit
) : RecyclerView.Adapter<QueueTrackAdapter.Holder>() {

    private var tracks: List<SpotifyQueueTrack> = emptyList()

    fun submit(tracks: List<SpotifyQueueTrack>) {
        this.tracks = tracks
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_track, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        holder.textTitle.text = track.title
        holder.textArtist.text = track.artist.ifEmpty { "—" }
        AlbumArtLoader.load(track.imageUrl, holder.imageArt)
        holder.imageArt.clipToOutline = true
        holder.imageArt.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 6f)
            }
        }

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
    }
}
