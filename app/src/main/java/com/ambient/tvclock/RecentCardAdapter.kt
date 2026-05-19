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
 * Recently Played card grid (Stream A, artboard 12). Renders each track as
 * a square cover + Manrope-500 title + secondary artist line + monospace
 * context strip. The cover height is forced to match its measured width so
 * the 5-up grid stays square regardless of the parent's width.
 *
 * Falls back to the procedural [CoverDrawable] when the track has no
 * Spotify art URL — keeps the visual language consistent on cold start.
 */
class RecentCardAdapter(
    private val onTrackSelected: (SpotifyQueueTrack) -> Unit
) : ListAdapter<SpotifyQueueTrack, RecentCardAdapter.Holder>(DIFF) {

    fun submit(tracks: List<SpotifyQueueTrack>) {
        submitList(tracks)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = getItem(position)
        holder.textTitle.text = track.title
        holder.textArtist.text = track.artist.ifEmpty { "—" }
        holder.textContext.text = track.contextLabel()

        // Spotify art preferred; deterministic CoverDrawable fallback when
        // the URL is empty. The seed combines title+artist so the same track
        // always renders the same fallback cover across screens.
        val coverSeed = (track.uri.ifBlank { "${track.title}|${track.artist}" })
        holder.imageCover.setImageDrawable(CoverDrawable(coverSeed))
        if (track.imageUrl.isNotBlank()) {
            AlbumArtLoader.load(track.imageUrl, holder.imageCover)
        }

        // Force the cover's height to match its measured width — keeps the
        // grid cells square. weightSum + layout_weight in LinearLayout can't
        // produce an aspect-ratio constraint without ConstraintLayout.
        val cover = holder.imageCover
        cover.post {
            val w = cover.width
            if (w > 0 && cover.layoutParams.height != w) {
                cover.layoutParams = cover.layoutParams.apply { height = w }
                cover.requestLayout()
            }
        }

        val play = {
            if (track.uri.isNotBlank()) onTrackSelected(track)
        }
        holder.itemView.setOnClickListener { play() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                play()
                true
            } else {
                false
            }
        }
    }

    private fun SpotifyQueueTrack.contextLabel(): String {
        // Recently-played items carry a context URI but not a human name. The
        // album case is free — the track itself ships album.name. Playlist
        // contexts get resolved against the user's owned-playlist cache
        // (already populated by Browse); on a miss we drop to a generic
        // label rather than fabricate something. Artist contexts read the
        // primary artist off the track.
        if (contextUri.isBlank()) {
            return if (artist.isNotBlank()) "by $artist" else "—"
        }
        return when {
            contextUri.contains(":album:") -> {
                if (albumName.isNotBlank()) "from $albumName · album"
                else if (artist.isNotBlank()) "from album · $artist"
                else "from album"
            }
            contextUri.contains(":playlist:") -> {
                val id = contextUri.substringAfterLast(':')
                val name = SpotifyPlaylistCache.getIndex()?.value
                    ?.firstOrNull { it.id == id }?.name
                if (!name.isNullOrBlank()) "from $name · playlist" else "from playlist"
            }
            contextUri.contains(":artist:") -> {
                if (artist.isNotBlank()) "by $artist" else "from artist"
            }
            else -> "from context"
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val imageCover: ImageView = view.findViewById(R.id.recentCover)
        val textTitle: TextView = view.findViewById(R.id.recentTitle)
        val textArtist: TextView = view.findViewById(R.id.recentArtist)
        val textContext: TextView = view.findViewById(R.id.recentContext)

        init {
            imageCover.clipToOutline = true
            imageCover.outlineProvider = ROUND_10
        }
    }

    companion object {
        private val ROUND_10 = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 10f)
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
