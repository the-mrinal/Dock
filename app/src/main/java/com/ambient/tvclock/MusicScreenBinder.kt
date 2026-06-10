package com.ambient.tvclock

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Music page = left browse panel + right now-playing panel.
 *
 * Left panel navigation model (D-pad):
 *   [Recently played] [Playlists]   <- tab chips, always visible, click to switch
 *   [< playlist name]               <- back row, only while inside a playlist
 *   [Up next card]                  <- only on the Recently played tab
 *   [list]                          <- recently played / playlists / tracks
 *
 * UP/DOWN walk this column top-to-bottom; RIGHT crosses to the playback
 * controls when they're visible; LEFT from the column falls through to
 * MainActivity's page navigation. BACK pops tracks -> playlists -> recent.
 * All activation relies on the native focusable+clickable DPAD_CENTER
 * handling — no custom OnKeyListeners.
 */
class MusicScreenBinder(
    private val root: View,
    private val onControlUsed: () -> Unit
) {

    private val panelNowPlaying: View = root.findViewById(R.id.panelNowPlaying)
    private val imageAlbumBackground: ImageView = root.findViewById(R.id.imageAlbumBackground)
    private val nowPlayingContent: View = root.findViewById(R.id.nowPlayingContent)
    private val albumArtContainer: View = root.findViewById(R.id.albumArtContainer)
    private val imageAlbumArt: ImageView = root.findViewById(R.id.imageAlbumArt)
    private val imagePlaceholder: ImageView = root.findViewById(R.id.imageAlbumPlaceholder)
    private val textTitle: TextView = root.findViewById(R.id.textTrackTitle)
    private val textArtist: TextView = root.findViewById(R.id.textTrackArtist)
    private val textAlbum: TextView = root.findViewById(R.id.textTrackAlbum)
    private val textEmpty: TextView = root.findViewById(R.id.textMusicEmpty)
    private val waveformPlayback: PlaybackProgressBar = root.findViewById(R.id.waveformPlayback)
    private val mediaControls: LinearLayout = root.findViewById(R.id.mediaControlsGroup)
    private val buttonPrev: ImageButton = root.findViewById(R.id.buttonSkipPrevious)
    private val buttonPlay: ImageButton = root.findViewById(R.id.buttonPlayPause)
    private val buttonNext: ImageButton = root.findViewById(R.id.buttonSkipNext)
    private val buttonDeviceCast: ImageButton = root.findViewById(R.id.buttonDeviceCast)

    private val chipRecent: TextView = root.findViewById(R.id.chipRecent)
    private val chipPlaylists: TextView = root.findViewById(R.id.chipPlaylists)
    private val rowPlaylistBack: View = root.findViewById(R.id.rowPlaylistBack)
    private val textPlaylistBack: TextView = root.findViewById(R.id.textPlaylistBack)
    private val sectionUpNext: View = root.findViewById(R.id.sectionUpNext)
    private val upNextContent: View = root.findViewById(R.id.upNextContent)
    private val imageUpNextArt: ImageView = root.findViewById(R.id.imageUpNextArt)
    private val textUpNextTitle: TextView = root.findViewById(R.id.textUpNextTitle)
    private val textUpNextArtist: TextView = root.findViewById(R.id.textUpNextArtist)
    private val progressUpNextLoading: ProgressBar = root.findViewById(R.id.progressUpNextLoading)
    private val textListHint: TextView = root.findViewById(R.id.textListHint)
    private val recyclerContent: RecyclerView = root.findViewById(R.id.recyclerContent)

    private var upNextTrack: SpotifyQueueTrack? = null
    private var lastQueueSnapshot: SpotifyQueueSnapshot? = null
    private val artworkState = NowPlayingArtwork.State()
    private var lastBackgroundKey: String? = null
    private var lastBackgroundBitmap: Bitmap? = null

    private enum class BrowseMode { RECENT, PLAYLISTS, TRACKS }

    private var browseMode: BrowseMode = BrowseMode.RECENT
    private var currentPlaylist: SpotifyPlaylist? = null
    private var lastPlaylistRowIndex: Int = 0

    /** Set when popping TRACKS -> PLAYLISTS so the playlist the user came
     *  from regains focus once the list re-renders. */
    private var pendingPlaylistFocusIndex: Int? = null

    private val recentAdapter = QueueTrackAdapter { track -> playSelectedTrack(track) }
    private val playlistAdapter = PlaylistAdapter { p -> enterPlaylist(p) }
    private val playlistTrackAdapter = QueueTrackAdapter { t -> playInPlaylistContext(t) }

    init {
        val cardRadius = root.resources.getDimension(R.dimen.now_playing_card_radius)
        albumArtContainer.outlineProvider = roundOutline(cardRadius)
        albumArtContainer.clipToOutline = true
        panelNowPlaying.outlineProvider = roundOutline(cardRadius)
        panelNowPlaying.clipToOutline = true
        imageAlbumBackground.outlineProvider = roundOutline(cardRadius)
        imageAlbumBackground.clipToOutline = true
        imageUpNextArt.outlineProvider = roundOutline(6f)
        imageUpNextArt.clipToOutline = true

        recyclerContent.layoutManager = LinearLayoutManager(root.context)
        recyclerContent.adapter = recentAdapter
        recyclerContent.itemAnimator = null

        wireTransportButton(buttonPrev) { MediaTransport.skipToPrevious(root.context) }
        wireTransportButton(buttonPlay) { MediaTransport.playPause(root.context) }
        wireTransportButton(buttonNext) { MediaTransport.skipToNext(root.context) }

        chipRecent.setOnClickListener {
            onControlUsed()
            selectRecent()
        }
        chipPlaylists.setOnClickListener {
            onControlUsed()
            selectPlaylists()
        }
        rowPlaylistBack.setOnClickListener {
            onControlUsed()
            onBackPressed()
        }
        upNextContent.setOnClickListener {
            upNextTrack?.let { playSelectedTrack(it) }
        }
        buttonDeviceCast.setOnClickListener {
            onControlUsed()
            findActivity()?.let { activity ->
                SpotifyDevicePicker.show(activity) { deviceId ->
                    // Cast button = transfer whatever is currently playing
                    // onto the picked device. No track context to re-send.
                    playbackExecutor.execute {
                        SpotifyPlaybackControl.transferToDevice(
                            activity.applicationContext, deviceId
                        )
                    }
                    onControlUsed()
                    refreshPlaybackSoon()
                }
            }
        }

        applyBrowseChrome()
    }

    /** Called by MainActivity whenever the Music page becomes the current page. */
    fun requestControlFocus() {
        val target = pickFocusTarget()
        target.post { target.requestFocus() }
    }

    /** The stable focus anchor for the current browse mode — always visible. */
    private fun pickFocusTarget(): View = when (browseMode) {
        BrowseMode.RECENT -> chipRecent
        BrowseMode.PLAYLISTS -> chipPlaylists
        BrowseMode.TRACKS -> rowPlaylistBack
    }

    /**
     * Re-arms focus after a bind that may have hidden the previously focused
     * view. Only acts when this page is actually on screen — data binds also
     * fire while the Music page sits attached-but-offscreen in the pager, and
     * grabbing focus from there would yank the D-pad away from the visible
     * page.
     */
    private fun ensureSomethingFocused() {
        root.post {
            if (!root.isShown || !root.getGlobalVisibleRect(Rect())) return@post
            if (root.findFocus() != null) return@post
            pickFocusTarget().requestFocus()
        }
    }

    /**
     * Forwards BACK presses while the music page is in a drill-down state.
     * Returns true when the press is consumed (drill-down popped a level).
     * MainActivity calls this before the default activity-level BACK.
     */
    fun onBackPressed(): Boolean = when (browseMode) {
        BrowseMode.TRACKS -> {
            pendingPlaylistFocusIndex = lastPlaylistRowIndex
            selectPlaylists()
            true
        }
        BrowseMode.PLAYLISTS -> {
            selectRecent()
            chipRecent.post { chipRecent.requestFocus() }
            true
        }
        BrowseMode.RECENT -> false
    }

    /** Chip selection, back row, and up-next visibility for the current mode. */
    private fun applyBrowseChrome() {
        chipRecent.isSelected = browseMode == BrowseMode.RECENT
        chipPlaylists.isSelected = browseMode != BrowseMode.RECENT
        if (browseMode == BrowseMode.TRACKS) {
            textPlaylistBack.text = root.context.getString(
                R.string.spotify_tracks_back, currentPlaylist?.name ?: ""
            )
            rowPlaylistBack.visibility = View.VISIBLE
        } else {
            rowPlaylistBack.visibility = View.GONE
        }
        updateUpNextVisibility()
    }

    private fun updateUpNextVisibility() {
        sectionUpNext.visibility =
            if (browseMode == BrowseMode.RECENT && upNextTrack != null) View.VISIBLE else View.GONE
    }

    private fun selectRecent() {
        browseMode = BrowseMode.RECENT
        currentPlaylist = null
        applyBrowseChrome()
        if (recyclerContent.adapter !== recentAdapter) {
            recyclerContent.adapter = recentAdapter
        }
        renderRecentList()
    }

    private fun selectPlaylists() {
        browseMode = BrowseMode.PLAYLISTS
        currentPlaylist = null
        applyBrowseChrome()
        if (recyclerContent.adapter !== playlistAdapter) {
            recyclerContent.adapter = playlistAdapter
        }
        if (playlistAdapter.itemCount == 0) {
            showListHint(root.context.getString(R.string.spotify_playlists_loading))
        }
        loadPlaylists()
    }

    private fun enterPlaylist(playlist: SpotifyPlaylist) {
        onControlUsed()
        lastPlaylistRowIndex = playlistAdapter.currentList.indexOf(playlist).coerceAtLeast(0)
        browseMode = BrowseMode.TRACKS
        currentPlaylist = playlist
        applyBrowseChrome()
        if (recyclerContent.adapter !== playlistTrackAdapter) {
            recyclerContent.adapter = playlistTrackAdapter
        }
        playlistTrackAdapter.submit(emptyList())
        showListHint(root.context.getString(R.string.spotify_playlists_tracks_loading))
        // The clicked playlist row just vanished with the adapter swap; park
        // focus on the back row so the D-pad never dead-ends. The tracks
        // snapshot promotes focus to the first track once rows exist.
        rowPlaylistBack.requestFocus()
        loadTracks(playlist)
    }

    private fun showListHint(message: CharSequence) {
        textListHint.text = message
        textListHint.visibility = View.VISIBLE
        textListHint.isClickable = false
        textListHint.isFocusable = false
        textListHint.setOnClickListener(null)
        recyclerContent.visibility = View.GONE
    }

    private fun showListContent() {
        textListHint.visibility = View.GONE
        recyclerContent.visibility = View.VISIBLE
    }

    private fun findActivity(): Activity? {
        var ctx = root.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun wireTransportButton(button: ImageButton, action: () -> Unit) {
        button.setOnClickListener {
            onControlUsed()
            action()
            refreshPlaybackSoon()
        }
    }

    private fun playSelectedTrack(track: SpotifyQueueTrack) {
        if (track.uri.isBlank()) return
        onControlUsed()
        beginLoading(track.uri)
        if (track.contextUri.isNotBlank()) {
            // We know the playlist/album this track was streamed from — use
            // the playlist drill-down path so Spotify plays through it.
            startPlay(track.contextUri, track.uri, deviceId = null)
        } else {
            // No context (Spotify often returns null for tracks streamed via
            // DJ / Smart Shuffle / search). Build a synthetic queue from the
            // clicked track plus everything below it in the visible Recently
            // Played list so playback keeps going.
            startPlayUris(buildUriQueueFrom(track), deviceId = null)
        }
        refreshPlaybackSoon()
    }

    /**
     * For Recently Played clicks without a context URI, replay the slice of
     * the current snapshot starting at the clicked track. Falls back to a
     * one-item list if the track isn't in the snapshot for some reason —
     * `playUris` will still play it (just without continuation).
     */
    private fun buildUriQueueFrom(start: SpotifyQueueTrack): List<String> {
        val recent = SpotifyQueueCenter.current.recentlyPlayed
        val startIdx = recent.indexOfFirst { it.uri == start.uri && start.uri.isNotBlank() }
        val slice = if (startIdx >= 0) recent.drop(startIdx) else listOf(start)
        return slice.map { it.uri }.filter { it.isNotBlank() }
    }

    private fun playInPlaylistContext(track: SpotifyQueueTrack) {
        val playlist = currentPlaylist ?: return
        if (track.uri.isBlank()) return
        onControlUsed()
        beginLoading(track.uri)
        if (playlist.isLikedSongs) {
            // Spotify's /v1/me/player/play won't accept Liked Songs as a
            // context_uri (no public context type exists for saved tracks),
            // so hand over the clicked track + the rest of the visible list
            // as an explicit `uris` array. Spotify caps that at 50 per call.
            startPlayUris(buildLikedSongsQueueFrom(track), deviceId = null)
        } else {
            startPlay(playlist.uri, track.uri, deviceId = null)
        }
        refreshPlaybackSoon()
    }

    private fun buildLikedSongsQueueFrom(start: SpotifyQueueTrack): List<String> {
        val tracks = playlistTrackAdapter.currentList
        val idx = tracks.indexOfFirst { it.uri == start.uri && start.uri.isNotBlank() }
        val slice = if (idx >= 0) tracks.drop(idx) else listOf(start)
        return slice.take(50).map { it.uri }.filter { it.isNotBlank() }
    }

    /**
     * Show the inline progress bar on whichever row matches [uri] across
     * Up Next, Recently Played, and the playlist tracks list. Schedules a
     * safety hide so a hung network call can never leave the bar stuck.
     */
    private fun beginLoading(uri: String) {
        if (uri.isBlank()) return
        recentAdapter.setLoadingUri(uri)
        playlistTrackAdapter.setLoadingUri(uri)
        progressUpNextLoading.visibility =
            if (upNextTrack?.uri == uri) View.VISIBLE else View.GONE
        root.removeCallbacks(loadingSafetyTimeout)
        root.postDelayed(loadingSafetyTimeout, LOADING_SAFETY_MS)
    }

    private fun endLoading() {
        recentAdapter.setLoadingUri(null)
        playlistTrackAdapter.setLoadingUri(null)
        progressUpNextLoading.visibility = View.GONE
        root.removeCallbacks(loadingSafetyTimeout)
    }

    private val loadingSafetyTimeout = Runnable { endLoading() }

    /**
     * Fires `playContext` on the background executor and routes the result
     * through [handlePlaybackResult]. Keeps [contextUri] + [offsetUri] in
     * the closure so the NO_DEVICE_404 path can retry on whichever device
     * the user picks from the device picker.
     */
    private fun startPlay(contextUri: String, offsetUri: String, deviceId: String?) {
        val activity = findActivity()
        val context = root.context.applicationContext
        playbackExecutor.execute {
            val result = SpotifyPlaybackControl.playContext(
                context = context,
                contextUri = contextUri,
                offsetUri = offsetUri,
                deviceId = deviceId
            )
            root.post {
                handlePlaybackResult(activity, result) { newDeviceId ->
                    startPlay(contextUri, offsetUri, newDeviceId)
                }
            }
        }
    }

    /**
     * Background-executor counterpart to [startPlay] for the no-context
     * fallback. Same NO_DEVICE_404 / 403 / 429 handling.
     */
    private fun startPlayUris(uris: List<String>, deviceId: String?) {
        if (uris.isEmpty()) return
        val activity = findActivity()
        val context = root.context.applicationContext
        playbackExecutor.execute {
            val result = SpotifyPlaybackControl.playUris(
                context = context,
                uris = uris,
                deviceId = deviceId
            )
            root.post {
                handlePlaybackResult(activity, result) { newDeviceId ->
                    startPlayUris(uris, newDeviceId)
                }
            }
        }
    }

    private fun handlePlaybackResult(
        activity: Activity?,
        result: SpotifyPlaybackControl.PlayResult,
        retryWithDevice: (String) -> Unit
    ) {
        val context = root.context
        // Whatever Spotify said, the in-flight request has resolved — the
        // inline loading bar should disappear. The retry path will re-arm it
        // via beginLoading() if the user picks a device.
        endLoading()
        when (result) {
            SpotifyPlaybackControl.PlayResult.OK -> Unit
            SpotifyPlaybackControl.PlayResult.NO_DEVICE_404 -> {
                if (activity != null) {
                    // Re-issue the SAME play call against the picked device.
                    // transferToDevice would just make the device active but
                    // leaves no context, so the user's chosen track wouldn't
                    // actually play.
                    SpotifyDevicePicker.show(activity) { deviceId ->
                        onControlUsed()
                        retryWithDevice(deviceId)
                        refreshPlaybackSoon()
                    }
                } else {
                    Toast.makeText(context, R.string.spotify_no_active_device, Toast.LENGTH_LONG).show()
                }
            }
            SpotifyPlaybackControl.PlayResult.PREMIUM_REQUIRED_403 -> {
                Toast.makeText(context, R.string.spotify_premium_required, Toast.LENGTH_LONG).show()
            }
            SpotifyPlaybackControl.PlayResult.RATE_LIMITED_429 -> {
                Toast.makeText(context, R.string.spotify_queue_rate_limited, Toast.LENGTH_SHORT).show()
            }
            SpotifyPlaybackControl.PlayResult.ERROR -> {
                Toast.makeText(context, R.string.spotify_playlists_api_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPlaybackSoon() {
        val ctx = root.context.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ NowPlayingSessionReader.publish(ctx) }, 400)
        handler.postDelayed({ NowPlayingSessionReader.publish(ctx) }, 1200)
    }

    private fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private fun loadPlaylists() {
        val activity = findActivity() ?: return
        SpotifyPlaylistRepository.loadPlaylists(activity, cb@{ snapshot ->
            if (browseMode != BrowseMode.PLAYLISTS) return@cb
            renderPlaylistsSnapshot(snapshot)
        })
    }

    private fun loadTracks(playlist: SpotifyPlaylist) {
        val activity = findActivity() ?: return
        SpotifyPlaylistRepository.loadTracks(activity, playlist.id, cb@{ snapshot ->
            if (browseMode != BrowseMode.TRACKS) return@cb
            if (currentPlaylist?.id != snapshot.playlistId) return@cb
            renderTracksSnapshot(snapshot)
        })
    }

    private fun renderPlaylistsSnapshot(snapshot: SpotifyPlaylistSnapshot) {
        val context = root.context
        when (snapshot.state) {
            SpotifyPlaylistBrowseState.OK -> {
                showListContent()
                playlistAdapter.submit(snapshot.playlists) {
                    if (browseMode != BrowseMode.PLAYLISTS) return@submit
                    recyclerContent.post {
                        if (browseMode != BrowseMode.PLAYLISTS) return@post
                        val pending = pendingPlaylistFocusIndex
                        if (pending != null) {
                            // Returning from a playlist drill-down: put focus
                            // back on the playlist the user came from.
                            pendingPlaylistFocusIndex = null
                            val index = pending
                                .coerceAtMost((snapshot.playlists.size - 1).coerceAtLeast(0))
                                .coerceAtLeast(0)
                            val holder = recyclerContent.findViewHolderForAdapterPosition(index)
                            if (holder?.itemView?.requestFocus() != true) {
                                chipPlaylists.requestFocus()
                            }
                        } else {
                            ensureSomethingFocused()
                        }
                    }
                }
            }
            SpotifyPlaylistBrowseState.LOADING -> {
                showListHint(context.getString(R.string.spotify_playlists_loading))
            }
            SpotifyPlaylistBrowseState.EMPTY -> {
                showListHint(context.getString(R.string.spotify_playlists_empty))
                playlistAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.NEEDS_REAUTH -> {
                showListHint(context.getString(R.string.spotify_playlists_needs_reauth))
                playlistAdapter.submit(emptyList())
                launchReauthOnHintTap()
            }
            SpotifyPlaylistBrowseState.NOT_LINKED -> {
                showListHint(context.getString(R.string.spotify_queue_not_linked))
                playlistAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.RATE_LIMITED -> {
                showListHint(context.getString(R.string.spotify_queue_rate_limited))
            }
            SpotifyPlaylistBrowseState.API_ERROR -> {
                showListHint(context.getString(R.string.spotify_playlists_api_error))
            }
        }
        ensureSomethingFocused()
    }

    private fun renderTracksSnapshot(snapshot: SpotifyPlaylistTracksSnapshot) {
        val context = root.context
        when (snapshot.state) {
            SpotifyPlaylistBrowseState.OK -> {
                showListContent()
                // submitList's commit callback fires once the new list is
                // applied, so by the time we ask for the first ViewHolder it
                // actually exists. A nested post gives the recycler one frame
                // to bind/layout that ViewHolder before we requestFocus on it.
                playlistTrackAdapter.submit(snapshot.tracks) {
                    if (browseMode != BrowseMode.TRACKS) return@submit
                    recyclerContent.post {
                        if (browseMode != BrowseMode.TRACKS) return@post
                        val focused = root.findFocus()
                        val shouldPromote = focused == null || focused === rowPlaylistBack
                        if (shouldPromote && root.getGlobalVisibleRect(Rect())) {
                            recyclerContent.findViewHolderForAdapterPosition(0)
                                ?.itemView?.requestFocus()
                        }
                    }
                }
            }
            SpotifyPlaylistBrowseState.LOADING -> {
                showListHint(context.getString(R.string.spotify_playlists_tracks_loading))
            }
            SpotifyPlaylistBrowseState.EMPTY -> {
                showListHint(context.getString(R.string.spotify_playlists_tracks_empty))
                playlistTrackAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.NEEDS_REAUTH -> {
                showListHint(context.getString(R.string.spotify_playlists_needs_reauth))
                playlistTrackAdapter.submit(emptyList())
                launchReauthOnHintTap()
            }
            SpotifyPlaylistBrowseState.NOT_LINKED -> {
                showListHint(context.getString(R.string.spotify_queue_not_linked))
                playlistTrackAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.RATE_LIMITED -> {
                showListHint(context.getString(R.string.spotify_queue_rate_limited))
            }
            SpotifyPlaylistBrowseState.API_ERROR -> {
                showListHint(context.getString(R.string.spotify_playlists_api_error))
            }
        }
        ensureSomethingFocused()
    }

    private fun launchReauthOnHintTap() {
        textListHint.isClickable = true
        textListHint.isFocusable = true
        textListHint.setOnClickListener {
            findActivity()?.let { activity ->
                activity.startActivity(Intent(activity, SpotifyAuthActivity::class.java))
            }
        }
    }

    fun bindNowPlaying(info: NowPlayingInfo?) {
        val context = root.context
        val show = NowPlayingPreferences.isEnabled(context) &&
            info != null &&
            info.hasActiveSession

        if (!show) {
            nowPlayingContent.visibility = View.GONE
            textEmpty.visibility = View.VISIBLE
            imageAlbumBackground.visibility = View.GONE
            imageAlbumBackground.setImageDrawable(null)
            waveformPlayback.visibility = View.GONE
            mediaControls.visibility = View.GONE
            NowPlayingArtwork.reset(artworkState)
            lastBackgroundKey = null
            lastBackgroundBitmap = null
            ensureSomethingFocused()
            return
        }

        val track = info!!
        nowPlayingContent.visibility = View.VISIBLE
        textEmpty.visibility = View.GONE

        textTitle.text = track.title
        textArtist.text = track.artist.ifEmpty { context.getString(R.string.unknown_artist) }

        if (track.album.isNotEmpty()) {
            textAlbum.visibility = View.VISIBLE
            textAlbum.text = track.album
        } else {
            textAlbum.visibility = View.GONE
        }

        val spotify = MediaSessionHelper.isSpotify(track.packageName)
        mediaControls.visibility = if (spotify) View.VISIBLE else View.GONE
        buttonDeviceCast.visibility = if (spotify && SpotifyTokenStore.isConnected(context)) {
            View.VISIBLE
        } else {
            View.GONE
        }

        buttonPlay.setImageResource(
            if (track.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        buttonPrev.isEnabled = true
        buttonPrev.alpha = 1f
        buttonNext.isEnabled = true
        buttonNext.alpha = 1f
        buttonPlay.isEnabled = true
        buttonPlay.alpha = 1f

        NowPlayingArtwork.bind(imageAlbumArt, imagePlaceholder, track, artworkState)
        bindBlurredBackground(track)
        bindProgress(track)
        ensureSomethingFocused()
    }

    private fun bindBlurredBackground(track: NowPlayingInfo) {
        // `track.artwork` is already filtered upstream by ArtworkClassifier
        // in MediaSessionHelper — Spotify placeholders (transparent icons,
        // flat fills, sub-192px bitmaps) arrive here as null. So a null
        // here means "no real artwork yet"; hold the previous blur until
        // a real bitmap shows up.
        val art = track.artwork
        if (art == null) {
            if (lastBackgroundBitmap == null) {
                imageAlbumBackground.visibility = View.GONE
            }
            return
        }
        val key = track.mediaUri.ifBlank { "${track.title}|${track.artist}" }

        // Re-blur when EITHER the track key changes OR Spotify swapped in a
        // higher-quality bitmap for the same track (the old code only watched
        // the key, so the real album art that arrived after the placeholder
        // never made it onto the background).
        val sameKey = key == lastBackgroundKey
        val sameBitmap = art === lastBackgroundBitmap
        if (sameKey && sameBitmap) return

        lastBackgroundKey = key
        lastBackgroundBitmap = art
        blurExecutor.execute {
            val blurred = try {
                AlbumArtBlur.blur(art!!)
            } catch (_: Exception) {
                null
            }
            root.post {
                // Stale: a newer bind has superseded us before our blur landed.
                if (lastBackgroundBitmap !== art) return@post
                if (blurred != null) {
                    imageAlbumBackground.setImageBitmap(blurred)
                    imageAlbumBackground.visibility = View.VISIBLE
                } else if (imageAlbumBackground.drawable == null) {
                    imageAlbumBackground.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private val blurExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "album-blur").apply { isDaemon = true }
        }
        private val playbackExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "spotify-playback").apply { isDaemon = true }
        }
        /** Upper bound for the inline loading bar — Spotify usually responds
         *  in well under a second, but the OkHttp read timeout is 20s. After
         *  this long, drop the bar even if the call hasn't returned. */
        private const val LOADING_SAFETY_MS = 8_000L
    }

    private fun bindProgress(info: NowPlayingInfo) {
        val controller = NowPlayingCenter.activeController
        val state = controller?.playbackState
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) {
            waveformPlayback.visibility = View.GONE
            return
        }

        // The system reports position as of `lastPositionUpdateTime` — extrapolate
        // to "right now" so we don't show the playhead lagging by a poll interval.
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val anchorPosition = state?.position ?: 0L
        val anchorTime = state?.lastPositionUpdateTime ?: nowElapsed
        val speed = state?.playbackSpeed ?: 1f
        val livePosition = if (info.isPlaying && speed > 0f) {
            anchorPosition + ((nowElapsed - anchorTime).coerceAtLeast(0L) * speed).toLong()
        } else {
            anchorPosition
        }.coerceIn(0L, duration)

        val isPlayingSession = info.isPlaying &&
            state?.state == PlaybackState.STATE_PLAYING
        waveformPlayback.setPlayback(
            positionMs = livePosition,
            durationMs = duration,
            playing = isPlayingSession,
            speed = if (speed > 0f) speed else 1f
        )
        waveformPlayback.visibility = View.VISIBLE
    }

    fun bindQueue(snapshot: SpotifyQueueSnapshot) {
        lastQueueSnapshot = snapshot
        bindUpNext(snapshot.state, snapshot.upNext)
        if (browseMode == BrowseMode.RECENT) {
            renderRecentList()
        }
        updateCastTint(snapshot.activeDeviceName)
        ensureSomethingFocused()
    }

    private fun updateCastTint(deviceName: String?) {
        // Cast icon stays Spotify green; we just keep the content description in sync so
        // TalkBack / focus hint reads the current device.
        val context = root.context
        val label = deviceName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.music_playing_on_tv)
        buttonDeviceCast.contentDescription =
            context.getString(R.string.music_playing_on_device, label)
    }

    private fun bindUpNext(state: SpotifyQueueState, track: SpotifyQueueTrack?) {
        upNextTrack = if (state == SpotifyQueueState.OK) track else null
        val upNext = upNextTrack
        if (upNext != null) {
            textUpNextTitle.text = upNext.title
            textUpNextArtist.text = upNext.artist.ifEmpty { "—" }
            AlbumArtLoader.load(upNext.imageUrl, imageUpNextArt)
        } else {
            progressUpNextLoading.visibility = View.GONE
            AlbumArtLoader.clear(imageUpNextArt)
        }
        updateUpNextVisibility()
    }

    /** Renders the Recently played tab from the latest queue snapshot. */
    private fun renderRecentList() {
        val snapshot = lastQueueSnapshot ?: return
        val context = root.context
        when (snapshot.recentState) {
            SpotifyQueueState.OK, SpotifyQueueState.NOT_PLAYING -> {
                if (snapshot.recentlyPlayed.isEmpty()) {
                    showListHint(context.getString(R.string.spotify_recent_empty))
                } else {
                    showListContent()
                }
                recentAdapter.submit(snapshot.recentlyPlayed)
            }
            SpotifyQueueState.NOT_LINKED -> {
                showListHint(context.getString(R.string.spotify_recent_not_linked))
                recentAdapter.submit(emptyList())
            }
            SpotifyQueueState.NO_QUEUE -> {
                showListHint(context.getString(R.string.spotify_recent_empty))
                recentAdapter.submit(emptyList())
            }
            SpotifyQueueState.API_ERROR -> {
                showListHint(context.getString(R.string.spotify_recent_api_error))
                recentAdapter.submit(emptyList())
            }
            SpotifyQueueState.RATE_LIMITED -> {
                showListHint(context.getString(R.string.spotify_recent_rate_limited))
                recentAdapter.submit(emptyList())
            }
        }
    }
}
