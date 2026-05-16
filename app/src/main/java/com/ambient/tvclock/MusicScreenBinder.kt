package com.ambient.tvclock

import android.app.Activity
import android.content.ContextWrapper
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

    private var upNextTrack: SpotifyQueueTrack? = null
    private val artworkState = NowPlayingArtwork.State()
    private var lastBackgroundKey: String? = null
    private val recentAdapter = QueueTrackAdapter { track -> playSelectedTrack(track) }

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

        val openDevices: () -> Unit = {
            onControlUsed()
            findActivity()?.let { activity ->
                SpotifyDevicePicker.show(activity) {
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
    }

    fun requestControlFocus() {
        buttonPlay.post { buttonPlay.requestFocus() }
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
            NowPlayingArtwork.reset(artworkState)
            lastBackgroundKey = null
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
