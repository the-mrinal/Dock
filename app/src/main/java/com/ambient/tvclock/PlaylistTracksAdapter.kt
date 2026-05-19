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
import com.ambient.tvclock.ui.VuBarsView

/**
 * Tracks adapter for the playlist drill-in (Stream A, artboard 15).
 *
 * Uses the new `item_playlist_track.xml` 5-column grid (#/cover/title/album/
 * duration). The "currently playing" track shows animated [VuBarsView] in
 * the index slot.
 *
 * Spotify playlists can be 1000+ tracks. To avoid retaining every cover
 * Bitmap in memory the adapter only loads art for visible rows; rows that
 * recycle out drop their image reference. The actual `ListAdapter` diff
 * runs on a background executor (DiffUtil's default behaviour) so submitting
 * thousands of rows stays cheap.
 */
class PlaylistTracksAdapter(
    private val onTrackSelected: (SpotifyQueueTrack) -> Unit
) : ListAdapter<SpotifyQueueTrack, PlaylistTracksAdapter.Holder>(DIFF) {

    /** uri of the track currently being loaded/played — shown with VuBars + accent. */
    private var loadingUri: String? = null
    private var playingUri: String? = null
    private var attachedRecycler: RecyclerView? = null

    fun submit(tracks: List<SpotifyQueueTrack>, onCommit: (() -> Unit)? = null) {
        if (onCommit != null) submitList(tracks, onCommit) else submitList(tracks)
    }

    fun setLoadingUri(uri: String?) {
        loadingUri = uri
        // Update bound holders in place; recycling would drop DPAD focus.
        val rv = attachedRecycler ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child) as? Holder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            holder.applyLoading(getItem(pos), uri)
        }
    }

    /**
     * Mark a track as the currently-playing one. Visually swaps the index
     * column for animated VuBars + accent-coloured title.
     */
    fun setPlayingUri(uri: String?) {
        if (playingUri == uri) return
        playingUri = uri
        notifyDataSetChanged() // 0-N visible rows, swapping bars is cheap
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
            .inflate(R.layout.item_playlist_track, parent, false)
        return Holder(view)
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        // Drop cover ref so the bitmap cache can evict.
        holder.imageCover.setImageDrawable(null)
        holder.imageCover.tag = null
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = getItem(position)
        val isPlaying = playingUri != null && track.uri == playingUri

        // Index OR animated bars.
        if (isPlaying) {
            holder.textIndex.visibility = View.GONE
            holder.bars.visibility = View.VISIBLE
            holder.bars.start()
        } else {
            holder.textIndex.visibility = View.VISIBLE
            holder.textIndex.text = (position + 1).toString()
            holder.bars.visibility = View.GONE
            holder.bars.stop()
        }

        // Title / artist: accent the playing track.
        holder.textTitle.text = track.title
        holder.textArtist.text = track.artist.ifEmpty { "—" }
        val accent = holder.itemView.resources.getColor(R.color.dock_accent_chartreuse, null)
        val primary = holder.itemView.resources.getColor(R.color.dock_text_pri, null)
        holder.textTitle.setTextColor(if (isPlaying) accent else primary)

        holder.textAlbum.text = albumLabel(track)
        holder.textDuration.text = formatDuration(track.durationMs)

        // Cover — real art if available, generated cover otherwise.
        val coverSeed = (track.uri.ifBlank { "${track.title}|${track.artist}" })
        holder.imageCover.setImageDrawable(CoverDrawable(coverSeed))
        if (track.imageUrl.isNotBlank()) {
            AlbumArtLoader.load(track.imageUrl, holder.imageCover)
        }

        holder.applyLoading(track, loadingUri)

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

    private fun albumLabel(track: SpotifyQueueTrack): String =
        track.albumName.ifBlank { track.artist.ifBlank { "—" } }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalSec = (ms / 1000L).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val textIndex: TextView = view.findViewById(R.id.trackIndex)
        val bars: VuBarsView = view.findViewById(R.id.trackBars)
        val imageCover: ImageView = view.findViewById(R.id.trackCover)
        val textTitle: TextView = view.findViewById(R.id.trackTitle)
        val textArtist: TextView = view.findViewById(R.id.trackArtist)
        val textAlbum: TextView = view.findViewById(R.id.trackAlbum)
        val textDuration: TextView = view.findViewById(R.id.trackDuration)
        private val progress: ProgressBar = view.findViewById(R.id.trackLoading)

        init {
            imageCover.clipToOutline = true
            imageCover.outlineProvider = ROUND_7
        }

        fun applyLoading(track: SpotifyQueueTrack, loadingUri: String?) {
            val match = !loadingUri.isNullOrBlank() && track.uri == loadingUri
            progress.visibility = if (match) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val ROUND_7 = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 7f)
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
