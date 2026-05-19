package com.ambient.tvclock

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Outline
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

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
    private val textQueueHint: TextView = root.findViewById(R.id.textQueueHint)
    private val textRecentHint: TextView = root.findViewById(R.id.textRecentHint)
    private val upNextContent: View = root.findViewById(R.id.upNextContent)
    private val imageUpNextArt: ImageView = root.findViewById(R.id.imageUpNextArt)
    private val textUpNextTitle: TextView = root.findViewById(R.id.textUpNextTitle)
    private val textUpNextArtist: TextView = root.findViewById(R.id.textUpNextArtist)
    private val recyclerRecentlyPlayed: RecyclerView = root.findViewById(R.id.recyclerRecentlyPlayed)
    private val mediaControls: LinearLayout = root.findViewById(R.id.mediaControlsGroup)
    private val buttonPrev: ImageButton = root.findViewById(R.id.buttonSkipPrevious)
    private val buttonPlay: ImageButton = root.findViewById(R.id.buttonPlayPause)
    private val buttonNext: ImageButton = root.findViewById(R.id.buttonSkipNext)
    private val buttonDeviceCast: ImageButton = root.findViewById(R.id.buttonDeviceCast)
    private val rowBrowseEntry: View = root.findViewById(R.id.rowBrowseEntry)
    private val viewDefaultPanel: View = root.findViewById(R.id.viewDefaultPanel)
    private val viewBrowsePanel: View = root.findViewById(R.id.viewBrowsePanel)
    private val rowBackToList: View = viewBrowsePanel.findViewById(R.id.rowBackToList)
    private val textBrowseHeader: TextView = viewBrowsePanel.findViewById(R.id.textBrowseHeader)
    private val textBrowseHint: TextView = viewBrowsePanel.findViewById(R.id.textBrowseHint)
    private val recyclerBrowse: RecyclerView = viewBrowsePanel.findViewById(R.id.recyclerBrowse)

    private var upNextTrack: SpotifyQueueTrack? = null
    private val artworkState = NowPlayingArtwork.State()
    private var lastBackgroundKey: String? = null
    private val recentAdapter = QueueTrackAdapter { track -> playSelectedTrack(track) }

    private enum class BrowseMode { DEFAULT, PLAYLISTS, TRACKS }

    private var browseMode: BrowseMode = BrowseMode.DEFAULT
    private var currentPlaylist: SpotifyPlaylist? = null
    private var lastPlaylistRowIndex: Int = 0
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

        recyclerRecentlyPlayed.layoutManager = LinearLayoutManager(root.context)
        recyclerRecentlyPlayed.adapter = recentAdapter
        recyclerRecentlyPlayed.itemAnimator = null
        recyclerRecentlyPlayed.setHasFixedSize(true)

        recyclerBrowse.layoutManager = LinearLayoutManager(root.context)
        recyclerBrowse.itemAnimator = null
        recyclerBrowse.setHasFixedSize(false)

        wireTransportButton(buttonPrev) { MediaTransport.skipToPrevious(root.context) }
        wireTransportButton(buttonPlay) { MediaTransport.playPause(root.context) }
        wireTransportButton(buttonNext) { MediaTransport.skipToNext(root.context) }

        upNextContent.setOnClickListener {
            upNextTrack?.let { playSelectedTrack(it) }
        }
        upNextContent.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                upNextTrack?.let { playSelectedTrack(it) }
                true
            } else {
                false
            }
        }

        rowBrowseEntry.setOnClickListener {
            onControlUsed()
            enterPlaylists()
        }
        rowBrowseEntry.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onControlUsed()
                enterPlaylists()
                true
            } else {
                false
            }
        }

        rowBackToList.setOnClickListener {
            onControlUsed()
            onBackPressed()
        }
        rowBackToList.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onControlUsed()
                onBackPressed()
                true
            } else {
                false
            }
        }

        val openDevices: () -> Unit = {
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
        buttonDeviceCast.setOnClickListener { openDevices() }
        buttonDeviceCast.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                openDevices()
                true
            } else {
                false
            }
        }

        // The right-panel transport starts hidden until bindNowPlaying flips
        // it visible; clear the dangling nextFocusRight from XML so RIGHT
        // page-navs cleanly instead of trapping focus on a GONE button.
        updateLeftPanelFocusChain()
    }

    fun requestControlFocus() {
        // When no Spotify session is active, mediaControlsGroup is View.GONE so
        // buttonPlay.requestFocus() silently no-ops and the Music page would
        // land with NO focused view — every D-pad key then has nowhere to
        // start from. Fall back to a guaranteed-visible focusable.
        val target = pickFocusTarget()
        target.post { target.requestFocus() }
    }

    private fun pickFocusTarget(): View = when {
        browseMode == BrowseMode.DEFAULT && mediaControls.visibility == View.VISIBLE -> buttonPlay
        browseMode == BrowseMode.DEFAULT -> rowBrowseEntry
        else -> rowBackToList
    }

    /**
     * Re-arms focus after a bind that may have hidden the previously focused
     * view. Cheap if focus is already on something visible.
     */
    private fun ensureSomethingFocused() {
        root.post {
            if (root.findFocus() != null) return@post
            pickFocusTarget().requestFocus()
        }
    }

    /**
     * Keeps the left panel's `nextFocusRight` pointing at a *visible* target.
     * When the right-panel transport is GONE (no Spotify session) the chain
     * must be cleared, otherwise pressing RIGHT from any left-panel row tries
     * to focus a hidden button and silently fails — UI feels frozen. Calling
     * this after every `mediaControls.visibility` change keeps the chain
     * coherent.
     */
    private fun updateLeftPanelFocusChain() {
        val rightTargetId = if (mediaControls.visibility == View.VISIBLE) {
            R.id.buttonPlayPause
        } else {
            View.NO_ID
        }
        rowBrowseEntry.nextFocusRightId = rightTargetId
        upNextContent.nextFocusRightId = rightTargetId
        rowBackToList.nextFocusRightId = rightTargetId
    }

    /**
     * Forwards BACK presses while the music page is in a drill-down state.
     * Returns true when the press is consumed (drill-down popped a level).
     * MainActivity calls this before the default activity-level BACK.
     */
    fun onBackPressed(): Boolean = when (browseMode) {
        BrowseMode.TRACKS -> {
            showPlaylists()
            true
        }
        BrowseMode.PLAYLISTS -> {
            showDefault()
            true
        }
        BrowseMode.DEFAULT -> false
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
        val run: () -> Unit = {
            onControlUsed()
            action()
            refreshPlaybackSoon()
        }
        button.setOnClickListener { run() }
        button.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                run()
                true
            } else {
                false
            }
        }
    }

    private fun playSelectedTrack(track: SpotifyQueueTrack) {
        if (track.uri.isBlank()) return
        onControlUsed()
        MediaTransport.playTrack(root.context, track.uri)
        refreshPlaybackSoon()
    }

    private fun playInPlaylistContext(track: SpotifyQueueTrack) {
        val playlist = currentPlaylist ?: return
        if (track.uri.isBlank()) return
        onControlUsed()
        startPlay(playlist.uri, track.uri, deviceId = null)
        refreshPlaybackSoon()
    }

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
            root.post { handlePlaybackResult(activity, result, contextUri, offsetUri) }
        }
    }

    private fun handlePlaybackResult(
        activity: Activity?,
        result: SpotifyPlaybackControl.PlayResult,
        contextUri: String,
        offsetUri: String
    ) {
        val context = root.context
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
                        startPlay(contextUri, offsetUri, deviceId)
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

    private fun showDefault() {
        browseMode = BrowseMode.DEFAULT
        currentPlaylist = null
        viewBrowsePanel.visibility = View.GONE
        viewDefaultPanel.visibility = View.VISIBLE
        updateLeftPanelFocusChain()
        rowBrowseEntry.post { rowBrowseEntry.requestFocus() }
    }

    private fun showPlaylists() {
        browseMode = BrowseMode.PLAYLISTS
        currentPlaylist = null
        viewDefaultPanel.visibility = View.GONE
        viewBrowsePanel.visibility = View.VISIBLE
        textBrowseHeader.text = root.context.getString(R.string.spotify_browse_playlists_header)
        if (recyclerBrowse.adapter !== playlistAdapter) {
            recyclerBrowse.adapter = playlistAdapter
        }
        loadPlaylists()
        recyclerBrowse.post {
            val index = lastPlaylistRowIndex.coerceAtLeast(0)
            val holder = recyclerBrowse.findViewHolderForAdapterPosition(index)
            if (holder?.itemView?.requestFocus() != true) {
                rowBackToList.requestFocus()
            }
        }
    }

    private fun enterPlaylists() {
        lastPlaylistRowIndex = 0
        showPlaylists()
    }

    private fun enterPlaylist(playlist: SpotifyPlaylist) {
        lastPlaylistRowIndex = playlistAdapter.currentList.indexOf(playlist).coerceAtLeast(0)
        browseMode = BrowseMode.TRACKS
        currentPlaylist = playlist
        viewDefaultPanel.visibility = View.GONE
        viewBrowsePanel.visibility = View.VISIBLE
        textBrowseHeader.text = playlist.name
        if (recyclerBrowse.adapter !== playlistTrackAdapter) {
            recyclerBrowse.adapter = playlistTrackAdapter
        }
        playlistTrackAdapter.submit(emptyList())
        loadTracks(playlist)
        recyclerBrowse.post {
            val holder = recyclerBrowse.findViewHolderForAdapterPosition(0)
            if (holder?.itemView?.requestFocus() != true) {
                rowBackToList.requestFocus()
            }
        }
    }

    private fun loadPlaylists() {
        val activity = findActivity() ?: return
        textBrowseHint.text = root.context.getString(R.string.spotify_playlists_loading)
        SpotifyPlaylistRepository.loadPlaylists(activity, cb@{ snapshot ->
            if (browseMode != BrowseMode.PLAYLISTS) return@cb
            renderPlaylistsSnapshot(snapshot)
        })
    }

    private fun loadTracks(playlist: SpotifyPlaylist) {
        val activity = findActivity() ?: return
        textBrowseHint.text = root.context.getString(R.string.spotify_playlists_tracks_loading)
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
                textBrowseHint.visibility = View.GONE
                recyclerBrowse.visibility = View.VISIBLE
                playlistAdapter.submit(snapshot.playlists)
                recyclerBrowse.post {
                    if (browseMode != BrowseMode.PLAYLISTS) return@post
                    if (recyclerBrowse.findFocus() == null) {
                        val index = lastPlaylistRowIndex.coerceAtMost((snapshot.playlists.size - 1).coerceAtLeast(0))
                        recyclerBrowse.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                    }
                }
            }
            SpotifyPlaylistBrowseState.LOADING -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_loading)
                recyclerBrowse.visibility = View.GONE
            }
            SpotifyPlaylistBrowseState.EMPTY -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_empty)
                recyclerBrowse.visibility = View.GONE
                playlistAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.NEEDS_REAUTH -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_needs_reauth)
                recyclerBrowse.visibility = View.GONE
                playlistAdapter.submit(emptyList())
                launchReauthOnHintTap()
            }
            SpotifyPlaylistBrowseState.NOT_LINKED -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_queue_not_linked)
                recyclerBrowse.visibility = View.GONE
                playlistAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.RATE_LIMITED -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_queue_rate_limited)
                recyclerBrowse.visibility = View.GONE
            }
            SpotifyPlaylistBrowseState.API_ERROR -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_api_error)
                recyclerBrowse.visibility = View.GONE
            }
        }
    }

    private fun renderTracksSnapshot(snapshot: SpotifyPlaylistTracksSnapshot) {
        val context = root.context
        when (snapshot.state) {
            SpotifyPlaylistBrowseState.OK -> {
                textBrowseHint.visibility = View.GONE
                recyclerBrowse.visibility = View.VISIBLE
                // submitList's commit callback fires once the new list is
                // applied, so by the time we ask for the first ViewHolder it
                // actually exists. A nested post gives the recycler one frame
                // to bind/layout that ViewHolder before we requestFocus on it.
                playlistTrackAdapter.submit(snapshot.tracks) {
                    if (browseMode != BrowseMode.TRACKS) return@submit
                    recyclerBrowse.post {
                        if (browseMode != BrowseMode.TRACKS) return@post
                        val focused = root.findFocus()
                        val shouldPromote = focused == null || focused === rowBackToList
                        if (shouldPromote) {
                            recyclerBrowse.findViewHolderForAdapterPosition(0)
                                ?.itemView?.requestFocus()
                        }
                    }
                }
            }
            SpotifyPlaylistBrowseState.LOADING -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_tracks_loading)
                recyclerBrowse.visibility = View.GONE
            }
            SpotifyPlaylistBrowseState.EMPTY -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_tracks_empty)
                recyclerBrowse.visibility = View.GONE
                playlistTrackAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.NEEDS_REAUTH -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_needs_reauth)
                recyclerBrowse.visibility = View.GONE
                playlistTrackAdapter.submit(emptyList())
                launchReauthOnHintTap()
            }
            SpotifyPlaylistBrowseState.NOT_LINKED -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_queue_not_linked)
                recyclerBrowse.visibility = View.GONE
                playlistTrackAdapter.submit(emptyList())
            }
            SpotifyPlaylistBrowseState.RATE_LIMITED -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_queue_rate_limited)
                recyclerBrowse.visibility = View.GONE
            }
            SpotifyPlaylistBrowseState.API_ERROR -> {
                textBrowseHint.visibility = View.VISIBLE
                textBrowseHint.text = context.getString(R.string.spotify_playlists_api_error)
                recyclerBrowse.visibility = View.GONE
            }
        }
    }

    private fun launchReauthOnHintTap() {
        textBrowseHint.isClickable = true
        textBrowseHint.isFocusable = true
        textBrowseHint.setOnClickListener {
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
            waveformPlayback.visibility = View.GONE
            mediaControls.visibility = View.GONE
            NowPlayingArtwork.reset(artworkState)
            lastBackgroundKey = null
            updateLeftPanelFocusChain()
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
        updateLeftPanelFocusChain()

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
        val art = track.artwork ?: run {
            imageAlbumBackground.visibility = View.GONE
            lastBackgroundKey = null
            return
        }
        val key = track.mediaUri.ifBlank { "${track.title}|${track.artist}" }
        if (key == lastBackgroundKey) return
        lastBackgroundKey = key
        blurExecutor.execute {
            val blurred = try {
                AlbumArtBlur.blur(art)
            } catch (_: Exception) {
                null
            }
            root.post {
                if (lastBackgroundKey != key) return@post
                if (blurred != null) {
                    imageAlbumBackground.setImageBitmap(blurred)
                    imageAlbumBackground.visibility = View.VISIBLE
                } else {
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
        val context = root.context
        bindUpNextPanel(context, snapshot.state, snapshot.upNext)
        bindRecentPanel(context, snapshot.recentState, snapshot.recentlyPlayed)
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

    private fun bindUpNextPanel(
        context: android.content.Context,
        state: SpotifyQueueState,
        track: SpotifyQueueTrack?
    ) {
        upNextTrack = null
        when (state) {
            SpotifyQueueState.OK -> {
                if (track == null) {
                    showUpNextHint(context.getString(R.string.spotify_queue_empty))
                    return
                }
                textQueueHint.visibility = View.GONE
                upNextContent.visibility = View.VISIBLE
                upNextTrack = track
                textUpNextTitle.text = track.title
                textUpNextArtist.text = track.artist.ifEmpty { "—" }
                AlbumArtLoader.load(track.imageUrl, imageUpNextArt)
            }
            SpotifyQueueState.NOT_LINKED -> {
                showUpNextHint(context.getString(R.string.spotify_queue_not_linked))
            }
            SpotifyQueueState.NOT_PLAYING -> {
                showUpNextHint(context.getString(R.string.spotify_queue_not_playing))
            }
            SpotifyQueueState.NO_QUEUE -> {
                showUpNextHint(context.getString(R.string.spotify_queue_empty))
            }
            SpotifyQueueState.API_ERROR -> {
                showUpNextHint(context.getString(R.string.spotify_queue_api_error))
            }
            SpotifyQueueState.RATE_LIMITED -> {
                showUpNextHint(context.getString(R.string.spotify_queue_rate_limited))
            }
        }
    }

    private fun showUpNextHint(message: String) {
        textQueueHint.visibility = View.VISIBLE
        textQueueHint.text = message
        upNextContent.visibility = View.GONE
        AlbumArtLoader.clear(imageUpNextArt)
    }

    private fun bindRecentPanel(
        context: android.content.Context,
        state: SpotifyQueueState,
        tracks: List<SpotifyQueueTrack>
    ) {
        when (state) {
            SpotifyQueueState.OK -> {
                textRecentHint.visibility = View.GONE
                recyclerRecentlyPlayed.visibility = View.VISIBLE
                recentAdapter.submit(tracks)
            }
            SpotifyQueueState.NOT_LINKED -> {
                textRecentHint.visibility = View.VISIBLE
                textRecentHint.text = context.getString(R.string.spotify_recent_not_linked)
                recyclerRecentlyPlayed.visibility = View.GONE
                recentAdapter.submit(emptyList())
            }
            SpotifyQueueState.NO_QUEUE -> {
                textRecentHint.visibility = View.VISIBLE
                textRecentHint.text = context.getString(R.string.spotify_recent_empty)
                recyclerRecentlyPlayed.visibility = View.GONE
                recentAdapter.submit(emptyList())
            }
            SpotifyQueueState.API_ERROR -> {
                textRecentHint.visibility = View.VISIBLE
                textRecentHint.text = context.getString(R.string.spotify_recent_api_error)
                recyclerRecentlyPlayed.visibility = View.GONE
                recentAdapter.submit(emptyList())
            }
            SpotifyQueueState.RATE_LIMITED -> {
                textRecentHint.visibility = View.VISIBLE
                textRecentHint.text = context.getString(R.string.spotify_recent_rate_limited)
                recyclerRecentlyPlayed.visibility = View.GONE
                recentAdapter.submit(emptyList())
            }
            SpotifyQueueState.NOT_PLAYING -> {
                textRecentHint.visibility = View.GONE
                recyclerRecentlyPlayed.visibility = View.VISIBLE
                recentAdapter.submit(tracks)
            }
        }
    }
}
