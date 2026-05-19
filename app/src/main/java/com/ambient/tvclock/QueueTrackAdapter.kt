package com.ambient.tvclock

import android.graphics.Outline
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ambient.tvclock.ui.CoverDrawable

/**
 * Generic queue row adapter (Stream A redesign). Renders into the restyled
 * `item_queue_track.xml` — translucent card, 64dp cover, 20sp Manrope title,
 * 16sp artist line. Kept after the redesign because the LegacyMusicScreen
 * binder used it, but the new MusicScreenBinder mostly routes through
 * [RecentCardAdapter] (Recently Played) and [PlaylistTracksAdapter]
 * (playlist drill-in) instead.
 */
class QueueTrackAdapter(
    private val onTrackSelected: (SpotifyQueueTrack) -> Unit
) : ListAdapter<SpotifyQueueTrack, QueueTrackAdapter.Holder>(DIFF) {

    private var loadingUri: String? = null
    private var attachedRecycler: RecyclerView? = null

    fun submit(tracks: List<SpotifyQueueTrack>, onCommit: (() -> Unit)? = null) {
        if (onCommit != null) submitList(tracks, onCommit) else submitList(tracks)
    }

    /**
     * Toggles the inline progress bar on the row whose `uri` matches [uri].
     * Pass `null` to clear loading on all rows.
     */
    fun setLoadingUri(uri: String?) {
        loadingUri = uri
        val rv = attachedRecycler ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child) as? Holder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            holder.applyLoading(getItem(pos), uri)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecycler === recyclerView) attachedRecycler = null
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

        // Spotify art preferred; CoverDrawable fallback.
        val coverSeed = (track.uri.ifBlank { "${track.title}|${track.artist}" })
        holder.imageArt.setImageDrawable(CoverDrawable(coverSeed))
        if (track.imageUrl.isNotBlank()) {
            AlbumArtLoader.load(track.imageUrl, holder.imageArt)
        }
        holder.applyLoading(track, loadingUri)

        val playTrack = {
            if (track.uri.isNotBlank()) onTrackSelected(track)
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
        val progressLoading: ProgressBar = view.findViewById(R.id.progressQueueLoading)

        init {
            imageArt.clipToOutline = true
            imageArt.outlineProvider = ROUND_8
        }

        fun applyLoading(track: SpotifyQueueTrack, loadingUri: String?) {
            val match = !loadingUri.isNullOrBlank() && track.uri == loadingUri
            progressLoading.visibility = if (match) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val ROUND_8 = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 8f)
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
