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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ambient.tvclock.ui.CoverDrawable
import com.ambient.tvclock.ui.VuBarsView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Music page binder (Stream A redesign).
 *
 * Drives the single-surface Music page introduced in artboards 11–16:
 *   • TopChrome (page chrome row: tile + breadcrumb + LAN + clock + pill)
 *   • An in-screen [MusicNavController] state machine that swaps the
 *     visible sub-view between NowPlaying / Empty / Browse / PlaylistTracks
 *     with a 150ms crossfade.
 *
 * The binder is owned by [MainActivity]; the streamed `bindNowPlaying`,
 * `bindQueue`, and `onBackPressed` entry points keep the public surface
 * source-compatible with the pre-redesign API.
 */
class MusicScreenBinder(
    private val root: View,
    private val onControlUsed: () -> Unit
) {

    // ─── TopChrome views ──────────────────────────────────────────────
    private val topChrome: View = root.findViewById(R.id.musicTopChrome)
    private val topChromeClock: TextView = root.findViewById(R.id.topChromeClock)
    private val topChromeBrowseFocus: View = root.findViewById(R.id.topChromeBrowseFocus)
    private val topChromeBrowsePill: View = root.findViewById(R.id.topChromeBrowsePill)
    private val topChromeBreadcrumb: View = root.findViewById(R.id.topChromeBreadcrumb)
    private val topChromeBreadcrumbDivider: View = root.findViewById(R.id.topChromeBreadcrumbDivider)
    private val topChromeCrumb3: TextView = root.findViewById(R.id.topChromeCrumb3)
    private val topChromeCrumb3Chevron: View = root.findViewById(R.id.topChromeCrumb3Chevron)

    // ─── Sub-view roots (crossfade host) ──────────────────────────────
    private val subviewFrame: View = root.findViewById(R.id.musicSubviewFrame)
    private val viewNowPlaying: View = root.findViewById(R.id.musicViewNowPlaying)
    private val viewEmpty: View = root.findViewById(R.id.musicViewEmpty)
    private val viewBrowse: View = root.findViewById(R.id.musicViewBrowse)
    private val viewPlaylistTracks: View = root.findViewById(R.id.musicViewPlaylistTracks)

    // ─── NowPlaying sub-view ──────────────────────────────────────────
    private val heroArtContainer: View = viewNowPlaying.findViewById(R.id.heroArtContainer)
    private val heroArt: ImageView = viewNowPlaying.findViewById(R.id.heroArtImage)
    private val heroEyebrow: TextView = viewNowPlaying.findViewById(R.id.heroEyebrow)
    private val heroEyebrowBars: VuBarsView = viewNowPlaying.findViewById(R.id.heroEyebrowBars)
    private val heroContext: TextView = viewNowPlaying.findViewById(R.id.heroContext)
    private val heroDeviceFocus: View = viewNowPlaying.findViewById(R.id.heroDeviceFocus)
    private val heroDeviceChip: View = viewNowPlaying.findViewById(R.id.heroDeviceChip)
    private val heroDeviceName: TextView = viewNowPlaying.findViewById(R.id.heroDeviceName)
    private val heroTitle: TextView = viewNowPlaying.findViewById(R.id.heroTitle)
    private val heroArtist: TextView = viewNowPlaying.findViewById(R.id.heroArtist)
    private val heroAlbumYear: TextView = viewNowPlaying.findViewById(R.id.heroAlbumYear)
    private val heroProgress: PlaybackProgressBar = viewNowPlaying.findViewById(R.id.heroProgress)
    private val heroTimeCurrent: TextView = viewNowPlaying.findViewById(R.id.heroTimeCurrent)
    private val heroTimeRemaining: TextView = viewNowPlaying.findViewById(R.id.heroTimeRemaining)
    private val heroPrev: ImageButton = viewNowPlaying.findViewById(R.id.heroButtonPrev)
    private val heroPlay: ImageButton = viewNowPlaying.findViewById(R.id.heroButtonPlay)
    private val heroNext: ImageButton = viewNowPlaying.findViewById(R.id.heroButtonNext)
    private val heroPlayFocus: View = viewNowPlaying.findViewById(R.id.heroPlayFocus)
    private val heroQueueChip: View = viewNowPlaying.findViewById(R.id.heroQueueChip)

    private val upNextFocus: View = viewNowPlaying.findViewById(R.id.upNextFocus)
    private val upNextCard: View = viewNowPlaying.findViewById(R.id.upNextCard)
    private val upNextCover: ImageView = viewNowPlaying.findViewById(R.id.upNextCover)
    private val upNextTitle: TextView = viewNowPlaying.findViewById(R.id.upNextTitle)
    private val upNextArtist: TextView = viewNowPlaying.findViewById(R.id.upNextArtist)
    private val upNextHint: TextView = viewNowPlaying.findViewById(R.id.upNextHint)
    private val upNextHintText: TextView = viewNowPlaying.findViewById(R.id.upNextHintText)
    private val upNextLoading: ProgressBar = viewNowPlaying.findViewById(R.id.upNextLoading)

    private val recentRecycler: RecyclerView = viewNowPlaying.findViewById(R.id.recentRecycler)
    private val recentHintText: TextView = viewNowPlaying.findViewById(R.id.recentHintText)

    // ─── Empty sub-view ───────────────────────────────────────────────
    private val emptyBrowseFocus: View = viewEmpty.findViewById(R.id.emptyBrowseFocus)
    private val emptyBrowseCta: View = viewEmpty.findViewById(R.id.emptyBrowseCta)
    private val emptyDeviceFocus: View = viewEmpty.findViewById(R.id.emptyDeviceFocus)
    private val emptyDeviceCta: View = viewEmpty.findViewById(R.id.emptyDeviceCta)

    // ─── Browse sub-view ──────────────────────────────────────────────
    private val browseRecycler: RecyclerView = viewBrowse.findViewById(R.id.browseRecycler)
    private val browseHint: TextView = viewBrowse.findViewById(R.id.browseHint)

    // ─── PlaylistTracks sub-view ──────────────────────────────────────
    private val tracksCover: ImageView = viewPlaylistTracks.findViewById(R.id.tracksCover)
    private val tracksEyebrow: TextView = viewPlaylistTracks.findViewById(R.id.tracksEyebrow)
    private val tracksTitle: TextView = viewPlaylistTracks.findViewById(R.id.tracksTitle)
    private val tracksSubtitle: TextView = viewPlaylistTracks.findViewById(R.id.tracksSubtitle)
    private val tracksRecycler: RecyclerView = viewPlaylistTracks.findViewById(R.id.tracksRecycler)
    private val tracksHint: TextView = viewPlaylistTracks.findViewById(R.id.tracksHint)
    private val tracksPlayAllFocus: View = viewPlaylistTracks.findViewById(R.id.tracksPlayAllFocus)
    private val tracksPlayAll: View = viewPlaylistTracks.findViewById(R.id.tracksPlayAll)
    private val tracksShuffleFocus: View = viewPlaylistTracks.findViewById(R.id.tracksShuffleFocus)
    private val tracksShuffle: View = viewPlaylistTracks.findViewById(R.id.tracksShuffle)

    // ─── Adapters ─────────────────────────────────────────────────────
    private val playlistAdapter = PlaylistAdapter { p -> nav.enterPlaylist(p) }
    private val playlistTracksAdapter = PlaylistTracksAdapter { t -> playInPlaylistContext(t) }
    private val recentAdapter = RecentCardAdapter { t -> playRecentlyPlayed(t) }

    // ─── State ────────────────────────────────────────────────────────
    private var upNextTrack: SpotifyQueueTrack? = null
    private val artworkState = NowPlayingArtwork.State()
    private val nav = MusicNavController()
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockTick = Runnable { updateClock() }

    /** Latest Spotify-provided URL for the currently playing track's cover.
     *  Used by `bindNowPlaying` as a network fallback when the MediaSession
     *  bitmap is null. Updated each queue tick by `bindQueue`. */
    private var currentImageUrl: String = ""

    init {
        // Hero cover container — clip to outline with 18dp radius.
        val cardRadius = root.resources.getDimension(R.dimen.now_playing_card_radius)
        heroArtContainer.outlineProvider = roundOutline(cardRadius)
        heroArtContainer.clipToOutline = true

        upNextCover.outlineProvider = roundOutline(8f * root.resources.displayMetrics.density)
        upNextCover.clipToOutline = true

        tracksCover.outlineProvider = roundOutline(16f * root.resources.displayMetrics.density)
        tracksCover.clipToOutline = true

        // Recycler setups.
        recentRecycler.layoutManager = GridLayoutManager(root.context, 5).apply {
            orientation = GridLayoutManager.VERTICAL
            // We always show one row of 5 cards on Music NowPlaying; if the
            // Spotify API ever returns more, the grid will wrap to a second
            // row below — better than horizontally hiding them.
        }
        recentRecycler.adapter = recentAdapter
        recentRecycler.itemAnimator = null
        recentRecycler.setHasFixedSize(false)
        // 24dp inter-item gap matches the HTML grid `gap: 24`.
        recentRecycler.addItemDecoration(SpacingItemDecoration.grid(root.context, 24, 5))

        browseRecycler.layoutManager = GridLayoutManager(root.context, 4)
        browseRecycler.adapter = playlistAdapter
        browseRecycler.itemAnimator = null
        browseRecycler.setHasFixedSize(false)
        browseRecycler.addItemDecoration(SpacingItemDecoration.grid(root.context, 28, 4))

        tracksRecycler.layoutManager = LinearLayoutManager(root.context)
        tracksRecycler.adapter = playlistTracksAdapter
        tracksRecycler.itemAnimator = null
        tracksRecycler.setHasFixedSize(false)

        // Transport wiring.
        wireTransportButton(heroPrev) { MediaTransport.skipToPrevious(root.context) }
        wireTransportButton(heroPlay) { MediaTransport.playPause(root.context) }
        wireTransportButton(heroNext) { MediaTransport.skipToNext(root.context) }

        // Up Next card → play that track.
        upNextCard.isClickable = true
        upNextCard.isFocusable = false
        upNextFocus.setOnClickListener { upNextTrack?.let { playSelectedTrack(it) } }
        upNextFocus.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                upNextTrack?.let { playSelectedTrack(it) }
                true
            } else {
                false
            }
        }

        // TopChrome "Browse Playlists" pill.
        topChromeBrowsePill.isClickable = true
        topChromeBrowsePill.isFocusable = false
        topChromeBrowseFocus.setOnClickListener {
            onControlUsed()
            nav.enterBrowse()
        }
        topChromeBrowseFocus.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onControlUsed()
                nav.enterBrowse()
                true
            } else {
                false
            }
        }

        // Empty-state CTAs.
        emptyBrowseCta.isClickable = true
        emptyBrowseCta.isFocusable = false
        emptyBrowseFocus.setOnClickListener {
            onControlUsed()
            nav.enterBrowse()
        }
        emptyBrowseFocus.setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_UP &&
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
            ) { onControlUsed(); nav.enterBrowse(); true } else false
        }
        emptyDeviceCta.isClickable = true
        emptyDeviceCta.isFocusable = false
        emptyDeviceFocus.setOnClickListener { onControlUsed(); openDevicePicker() }
        emptyDeviceFocus.setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_UP &&
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
            ) { onControlUsed(); openDevicePicker(); true } else false
        }

        // Hero device chip → opens picker.
        heroDeviceChip.isClickable = true
        heroDeviceChip.isFocusable = false
        heroDeviceFocus.setOnClickListener { onControlUsed(); openDevicePicker() }
        heroDeviceFocus.setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_UP &&
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
            ) { onControlUsed(); openDevicePicker(); true } else false
        }

        // Hero queue chip is presentational for now (no in-app queue surface).
        heroQueueChip.isClickable = true
        heroQueueChip.isFocusable = false

        // PlaylistTracks: Play / Shuffle pills.
        tracksPlayAll.isClickable = true
        tracksPlayAll.isFocusable = false
        tracksPlayAllFocus.setOnClickListener {
            onControlUsed(); playCurrentPlaylistFromStart(false)
        }
        tracksPlayAllFocus.setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_UP &&
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
            ) { onControlUsed(); playCurrentPlaylistFromStart(false); true } else false
        }
        tracksShuffle.isClickable = true
        tracksShuffle.isFocusable = false
        tracksShuffleFocus.setOnClickListener {
            onControlUsed(); playCurrentPlaylistFromStart(true)
        }
        tracksShuffleFocus.setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_UP &&
                (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)
            ) { onControlUsed(); playCurrentPlaylistFromStart(true); true } else false
        }

        updateClock()
        nav.render(animate = false)
    }

    // ─── Public API (called by MainActivity) ──────────────────────────

    fun requestControlFocus() {
        val target = nav.preferredFocusTarget()
        target?.post { target.requestFocus() }
    }

    /**
     * Returns true when the press is consumed (drill-down popped a level).
     */
    fun onBackPressed(): Boolean = nav.popOnBack()

    fun bindNowPlaying(info: NowPlayingInfo?) {
        val context = root.context
        val show = NowPlayingPreferences.isEnabled(context) &&
            info != null &&
            info.hasActiveSession

        nav.setHasActiveSession(show)

        if (!show) {
            heroTitle.text = ""
            heroArtist.text = ""
            heroAlbumYear.text = ""
            heroEyebrow.text = context.getString(R.string.music_now_playing_from_playlist)
            heroContext.text = ""
            heroEyebrowBars.stop()
            heroProgress.visibility = View.INVISIBLE
            heroTimeCurrent.text = ""
            heroTimeRemaining.text = ""
            heroArt.setImageDrawable(CoverDrawable("empty"))
            return
        }

        val track = info!!
        heroTitle.text = track.title
        heroArtist.text = track.artist.ifEmpty { context.getString(R.string.unknown_artist) }
        if (track.album.isNotEmpty()) {
            heroAlbumYear.text = track.album
            heroAlbumYear.visibility = View.VISIBLE
        } else {
            heroAlbumYear.visibility = View.GONE
        }
        // Eyebrow + context line keep the HTML's "Playing from playlist" /
        // "Deep Focus" split. We don't yet have a context-name signal from
        // NowPlayingInfo so the context line reuses the album as a
        // best-effort secondary descriptor.
        heroEyebrow.text = context.getString(R.string.music_now_playing_from_playlist)
        heroContext.text = track.album.ifEmpty { track.artist }

        // Album art priority:
        //   1. MediaSession bitmap (when Spotify actually exposes it)
        //   2. Spotify Web API URL captured from /v1/me/player/queue's
        //      `currently_playing.album.images` — see SpotifyQueuePoller
        //   3. Procedural CoverDrawable seeded by the track URI
        val art = track.artwork
        if (art != null) {
            NowPlayingArtwork.bind(heroArt, heroArt, track, artworkState)
        } else if (currentImageUrl.isNotBlank()) {
            artworkState.lastBitmap = null
            AlbumArtLoader.load(currentImageUrl, heroArt)
        } else {
            artworkState.lastBitmap = null
            val seed = track.mediaUri.ifBlank { "${track.title}|${track.artist}" }
            heroArt.setImageDrawable(CoverDrawable(seed))
        }

        // Play/pause icon.
        heroPlay.setImageResource(
            if (track.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // VuBars only animate when actually playing.
        if (track.isPlaying) heroEyebrowBars.start() else heroEyebrowBars.stop()

        bindProgress(track)
    }

    fun bindQueue(snapshot: SpotifyQueueSnapshot) {
        val context = root.context
        bindUpNext(context, snapshot.state, snapshot.upNext)
        bindRecent(context, snapshot.recentState, snapshot.recentlyPlayed)
        updateDeviceLabel(snapshot.activeDeviceName)
        // Capture the currently-playing cover URL and apply it eagerly. We do
        // NOT gate on `NowPlayingCenter.current?.hasActiveSession` here because
        // the queue poll commonly races ahead of the MediaSession callback on
        // a fresh app launch — by the time MediaSession is ready, the URL has
        // already been seen and AlbumArtLoader's URL-tag dedupes a re-fetch.
        val urlChanged = snapshot.currentImageUrl != currentImageUrl
        currentImageUrl = snapshot.currentImageUrl
        if (urlChanged && currentImageUrl.isNotBlank()) {
            artworkState.lastBitmap = null
            AlbumArtLoader.load(currentImageUrl, heroArt)
        }
    }

    // ─── Up Next ──────────────────────────────────────────────────────

    private fun bindUpNext(
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
                upNextHintText.visibility = View.GONE
                upNextCard.visibility = View.VISIBLE
                upNextTrack = track
                upNextTitle.text = track.title
                upNextArtist.text = track.artist.ifEmpty { "—" }
                upNextHint.text = context.getString(R.string.music_press_ok_to_jump)
                if (track.imageUrl.isNotBlank()) {
                    AlbumArtLoader.load(track.imageUrl, upNextCover)
                } else {
                    val seed = track.uri.ifBlank { "${track.title}|${track.artist}" }
                    upNextCover.setImageDrawable(CoverDrawable(seed))
                }
            }
            SpotifyQueueState.NOT_LINKED -> showUpNextHint(context.getString(R.string.spotify_queue_not_linked))
            SpotifyQueueState.NOT_PLAYING -> showUpNextHint(context.getString(R.string.spotify_queue_not_playing))
            SpotifyQueueState.NO_QUEUE -> showUpNextHint(context.getString(R.string.spotify_queue_empty))
            SpotifyQueueState.API_ERROR -> showUpNextHint(context.getString(R.string.spotify_queue_api_error))
            SpotifyQueueState.RATE_LIMITED -> showUpNextHint(context.getString(R.string.spotify_queue_rate_limited))
        }
    }

    private fun showUpNextHint(message: String) {
        upNextCard.visibility = View.GONE
        upNextHintText.visibility = View.VISIBLE
        upNextHintText.text = message
    }

    // ─── Recently Played ──────────────────────────────────────────────

    private fun bindRecent(
        context: android.content.Context,
        state: SpotifyQueueState,
        tracks: List<SpotifyQueueTrack>
    ) {
        when (state) {
            SpotifyQueueState.OK, SpotifyQueueState.NOT_PLAYING -> {
                recentHintText.visibility = View.GONE
                recentRecycler.visibility = View.VISIBLE
                recentAdapter.submit(tracks.take(5))
            }
            SpotifyQueueState.NOT_LINKED -> showRecentHint(context.getString(R.string.spotify_recent_not_linked))
            SpotifyQueueState.NO_QUEUE -> showRecentHint(context.getString(R.string.spotify_recent_empty))
            SpotifyQueueState.API_ERROR -> showRecentHint(context.getString(R.string.spotify_recent_api_error))
            SpotifyQueueState.RATE_LIMITED -> showRecentHint(context.getString(R.string.spotify_recent_rate_limited))
        }
    }

    private fun showRecentHint(message: String) {
        recentRecycler.visibility = View.GONE
        recentHintText.visibility = View.VISIBLE
        recentHintText.text = message
    }

    // ─── Device chip ──────────────────────────────────────────────────

    private fun updateDeviceLabel(deviceName: String?) {
        val context = root.context
        val label = deviceName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.music_device_default_name)
        heroDeviceName.text = label
        heroDeviceFocus.contentDescription =
            context.getString(R.string.music_playing_on_device, label)
    }

    // ─── Progress bar ─────────────────────────────────────────────────

    private fun bindProgress(info: NowPlayingInfo) {
        val controller = NowPlayingCenter.activeController
        val state = controller?.playbackState
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) {
            heroProgress.visibility = View.INVISIBLE
            heroTimeCurrent.text = ""
            heroTimeRemaining.text = ""
            return
        }

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
        heroProgress.setPlayback(
            positionMs = livePosition,
            durationMs = duration,
            playing = isPlayingSession,
            speed = if (speed > 0f) speed else 1f
        )
        heroProgress.visibility = View.VISIBLE
        heroTimeCurrent.text = formatTime(livePosition)
        heroTimeRemaining.text = "-${formatTime(duration - livePosition)}"
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val m = total / 60L
        val s = total % 60L
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    // ─── Clock ────────────────────────────────────────────────────────

    private fun updateClock() {
        topChromeClock.text = clockFormat.format(Date())
        root.removeCallbacks(clockTick)
        root.postDelayed(clockTick, 30_000L)
    }

    // ─── Playback wiring ──────────────────────────────────────────────

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
        beginLoading(track.uri)
        if (track.contextUri.isNotBlank()) {
            startPlay(track.contextUri, track.uri, deviceId = null)
        } else {
            startPlayUris(buildUriQueueFrom(track), deviceId = null)
        }
        refreshPlaybackSoon()
    }

    private fun playRecentlyPlayed(track: SpotifyQueueTrack) = playSelectedTrack(track)

    private fun buildUriQueueFrom(start: SpotifyQueueTrack): List<String> {
        val recent = SpotifyQueueCenter.current.recentlyPlayed
        val startIdx = recent.indexOfFirst { it.uri == start.uri && start.uri.isNotBlank() }
        val slice = if (startIdx >= 0) recent.drop(startIdx) else listOf(start)
        return slice.map { it.uri }.filter { it.isNotBlank() }
    }

    private fun playInPlaylistContext(track: SpotifyQueueTrack) {
        val playlist = nav.currentPlaylist ?: return
        if (track.uri.isBlank()) return
        onControlUsed()
        beginLoading(track.uri)
        if (playlist.isLikedSongs) {
            startPlayUris(buildLikedSongsQueueFrom(track), deviceId = null)
        } else {
            startPlay(playlist.uri, track.uri, deviceId = null)
        }
        refreshPlaybackSoon()
    }

    private fun playCurrentPlaylistFromStart(shuffle: Boolean) {
        val playlist = nav.currentPlaylist ?: return
        if (playlist.isLikedSongs) {
            val tracks = playlistTracksAdapter.currentList
            if (tracks.isEmpty()) return
            startPlayUris(tracks.take(50).map { it.uri }.filter { it.isNotBlank() }, null, shuffle)
        } else {
            startPlay(playlist.uri, offsetUri = "", deviceId = null, shuffle = shuffle)
        }
        refreshPlaybackSoon()
    }

    private fun buildLikedSongsQueueFrom(start: SpotifyQueueTrack): List<String> {
        val tracks = playlistTracksAdapter.currentList
        val idx = tracks.indexOfFirst { it.uri == start.uri && start.uri.isNotBlank() }
        val slice = if (idx >= 0) tracks.drop(idx) else listOf(start)
        return slice.take(50).map { it.uri }.filter { it.isNotBlank() }
    }

    private fun beginLoading(uri: String) {
        if (uri.isBlank()) return
        // RecentCardAdapter doesn't expose loading state; visual feedback
        // here only flows through upNextLoading + playlistTracksAdapter.
        playlistTracksAdapter.setLoadingUri(uri)
        upNextLoading.visibility =
            if (upNextTrack?.uri == uri) View.VISIBLE else View.GONE
        root.removeCallbacks(loadingSafetyTimeout)
        root.postDelayed(loadingSafetyTimeout, LOADING_SAFETY_MS)
    }

    private fun endLoading() {
        playlistTracksAdapter.setLoadingUri(null)
        upNextLoading.visibility = View.GONE
        root.removeCallbacks(loadingSafetyTimeout)
    }

    private val loadingSafetyTimeout = Runnable { endLoading() }

    private fun startPlay(
        contextUri: String,
        offsetUri: String,
        deviceId: String?,
        shuffle: Boolean = false
    ) {
        val activity = findActivity()
        val context = root.context.applicationContext
        playbackExecutor.execute {
            val result = SpotifyPlaybackControl.playContext(
                context = context,
                contextUri = contextUri,
                offsetUri = offsetUri,
                deviceId = deviceId
            )
            // Apply shuffle on the now-active session. Calling this before
            // playContext often 404s when no device is awake yet; after the
            // play call succeeds the shuffle endpoint has a session to bind to.
            if (result == SpotifyPlaybackControl.PlayResult.OK) {
                SpotifyPlaybackControl.setShuffle(context, shuffle, deviceId)
            }
            root.post {
                handlePlaybackResult(activity, result) { newDeviceId ->
                    startPlay(contextUri, offsetUri, newDeviceId, shuffle)
                }
            }
        }
    }

    private fun startPlayUris(uris: List<String>, deviceId: String?, shuffle: Boolean = false) {
        if (uris.isEmpty()) return
        val activity = findActivity()
        val context = root.context.applicationContext
        playbackExecutor.execute {
            val result = SpotifyPlaybackControl.playUris(
                context = context,
                uris = uris,
                deviceId = deviceId
            )
            if (result == SpotifyPlaybackControl.PlayResult.OK) {
                SpotifyPlaybackControl.setShuffle(context, shuffle, deviceId)
            }
            root.post {
                handlePlaybackResult(activity, result) { newDeviceId ->
                    startPlayUris(uris, newDeviceId, shuffle)
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
        endLoading()
        when (result) {
            SpotifyPlaybackControl.PlayResult.OK -> Unit
            SpotifyPlaybackControl.PlayResult.NO_DEVICE_404 -> {
                if (activity != null) {
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

    // ─── Device picker entry ──────────────────────────────────────────

    private fun openDevicePicker() {
        val activity = findActivity() ?: return
        SpotifyDevicePicker.show(activity) { deviceId ->
            playbackExecutor.execute {
                SpotifyPlaybackControl.transferToDevice(
                    activity.applicationContext, deviceId
                )
            }
            refreshPlaybackSoon()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun findActivity(): Activity? {
        var ctx = root.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  MusicNavController — in-screen state machine for Music sub-views.
    // ──────────────────────────────────────────────────────────────────

    enum class MusicScreen { NOW_PLAYING, EMPTY, BROWSE, PLAYLIST_TRACKS }

    /**
     * Owns the visible sub-view state and animates 150ms crossfades between
     * them. State transitions:
     *   NowPlaying ↔ Empty (driven by hasActiveSession)
     *   NowPlaying → Browse → PlaylistTracks(id)   on user navigation
     *   PlaylistTracks → Browse → NowPlaying       on BACK
     */
    inner class MusicNavController {

        private var screen: MusicScreen = MusicScreen.NOW_PLAYING
        private var hasActiveSession: Boolean = false
        var currentPlaylist: SpotifyPlaylist? = null
            private set
        private var lastPlaylistRowIndex: Int = 0

        fun setHasActiveSession(active: Boolean) {
            if (hasActiveSession == active) return
            hasActiveSession = active
            // Flip between NowPlaying and Empty if we're currently on either.
            if (screen == MusicScreen.NOW_PLAYING && !active) {
                screen = MusicScreen.EMPTY
                render(animate = true)
            } else if (screen == MusicScreen.EMPTY && active) {
                screen = MusicScreen.NOW_PLAYING
                render(animate = true)
            }
        }

        fun enterBrowse() {
            screen = MusicScreen.BROWSE
            currentPlaylist = null
            loadPlaylists()
            render(animate = true)
        }

        fun enterPlaylist(playlist: SpotifyPlaylist) {
            lastPlaylistRowIndex = playlistAdapter.currentList.indexOf(playlist).coerceAtLeast(0)
            currentPlaylist = playlist
            screen = MusicScreen.PLAYLIST_TRACKS
            // Pre-bind tracks pane header with what we know NOW so the page
            // is never blank during the network round-trip.
            tracksTitle.text = playlist.name
            tracksEyebrow.text = root.context.getString(
                R.string.music_tracks_eyebrow_format,
                playlist.trackCount
            )
            tracksSubtitle.text = ""
            // Cover: real art preferred, CoverDrawable fallback.
            tracksCover.setImageDrawable(CoverDrawable(playlist.id))
            if (playlist.imageUrl.isNotBlank()) {
                AlbumArtLoader.load(playlist.imageUrl, tracksCover)
            }
            playlistTracksAdapter.submit(emptyList())
            loadTracks(playlist)
            render(animate = true)
        }

        /**
         * BACK semantics:
         *   PLAYLIST_TRACKS → BROWSE
         *   BROWSE → NOW_PLAYING (or EMPTY when no session)
         *   anything else → not consumed
         */
        fun popOnBack(): Boolean = when (screen) {
            MusicScreen.PLAYLIST_TRACKS -> {
                screen = MusicScreen.BROWSE
                currentPlaylist = null
                render(animate = true)
                true
            }
            MusicScreen.BROWSE -> {
                screen = if (hasActiveSession) MusicScreen.NOW_PLAYING else MusicScreen.EMPTY
                render(animate = true)
                true
            }
            else -> false
        }

        /**
         * @return the highest-priority focusable view for the current screen,
         * or null if no candidate is visible yet (caller retries on the next
         * layout pass).
         */
        fun preferredFocusTarget(): View? = when (screen) {
            // Land on the actual focusable ImageButton (the FocusableContainer
            // wrapper is non-focusable in the redesigned hero so the button's
            // own `state_focused` drawable selector can fire).
            MusicScreen.NOW_PLAYING -> heroPlay
            MusicScreen.EMPTY -> emptyBrowseFocus
            MusicScreen.BROWSE -> {
                val idx = lastPlaylistRowIndex.coerceAtMost((playlistAdapter.currentList.size - 1).coerceAtLeast(0))
                browseRecycler.findViewHolderForAdapterPosition(idx)?.itemView ?: topChromeBrowseFocus
            }
            MusicScreen.PLAYLIST_TRACKS -> {
                tracksRecycler.findViewHolderForAdapterPosition(0)?.itemView ?: tracksPlayAllFocus
            }
        }

        fun render(animate: Boolean) {
            val target = when (screen) {
                MusicScreen.NOW_PLAYING -> viewNowPlaying
                MusicScreen.EMPTY -> viewEmpty
                MusicScreen.BROWSE -> viewBrowse
                MusicScreen.PLAYLIST_TRACKS -> viewPlaylistTracks
            }
            // Cross-fade: bring target to alpha=1, fade everything else to 0.
            val views = listOf(viewNowPlaying, viewEmpty, viewBrowse, viewPlaylistTracks)
            for (v in views) {
                val isTarget = v === target
                if (animate && v.visibility == View.VISIBLE && !isTarget) {
                    v.animate().alpha(0f).setDuration(150L).withEndAction {
                        v.visibility = View.GONE
                        v.alpha = 1f
                    }.start()
                } else if (animate && isTarget && v.visibility != View.VISIBLE) {
                    v.alpha = 0f
                    v.visibility = View.VISIBLE
                    v.animate().alpha(1f).setDuration(150L).start()
                } else {
                    v.visibility = if (isTarget) View.VISIBLE else View.GONE
                    v.alpha = 1f
                }
            }

            // Update TopChrome: Browse pill is hidden when already in
            // Browse / PlaylistTracks. Breadcrumb shows the trail.
            val showBrowsePill = screen == MusicScreen.NOW_PLAYING || screen == MusicScreen.EMPTY
            topChromeBrowseFocus.visibility = if (showBrowsePill) View.VISIBLE else View.GONE

            val showCrumb = screen == MusicScreen.BROWSE || screen == MusicScreen.PLAYLIST_TRACKS
            topChromeBreadcrumb.visibility = if (showCrumb) View.VISIBLE else View.GONE
            topChromeBreadcrumbDivider.visibility = if (showCrumb) View.VISIBLE else View.GONE
            if (screen == MusicScreen.PLAYLIST_TRACKS) {
                topChromeCrumb3.visibility = View.VISIBLE
                topChromeCrumb3Chevron.visibility = View.VISIBLE
                topChromeCrumb3.text = currentPlaylist?.name ?: ""
            } else {
                topChromeCrumb3.visibility = View.GONE
                topChromeCrumb3Chevron.visibility = View.GONE
            }

            // Refocus on the new screen.
            root.post {
                val focusTarget = preferredFocusTarget() ?: return@post
                if (root.findFocus() == null ||
                    root.findFocus()?.let { isInside(it, target) } != true
                ) {
                    focusTarget.requestFocus()
                }
            }
        }

        private fun isInside(view: View, container: View): Boolean {
            var p: View? = view
            while (p != null) {
                if (p === container) return true
                p = p.parent as? View
            }
            return false
        }

        private fun loadPlaylists() {
            val activity = findActivity() ?: return
            browseHint.visibility = View.VISIBLE
            browseHint.text = root.context.getString(R.string.spotify_playlists_loading)
            browseRecycler.visibility = View.GONE
            SpotifyPlaylistRepository.loadPlaylists(activity, cb@{ snapshot ->
                if (screen != MusicScreen.BROWSE) return@cb
                renderPlaylistsSnapshot(snapshot)
            })
        }

        private fun loadTracks(playlist: SpotifyPlaylist) {
            val activity = findActivity() ?: return
            tracksHint.visibility = View.VISIBLE
            tracksHint.text = root.context.getString(R.string.spotify_playlists_tracks_loading)
            SpotifyPlaylistRepository.loadTracks(activity, playlist.id, cb@{ snapshot ->
                if (screen != MusicScreen.PLAYLIST_TRACKS) return@cb
                if (currentPlaylist?.id != snapshot.playlistId) return@cb
                renderTracksSnapshot(snapshot)
            })
        }

        private fun renderPlaylistsSnapshot(snapshot: SpotifyPlaylistSnapshot) {
            val context = root.context
            when (snapshot.state) {
                SpotifyPlaylistBrowseState.OK -> {
                    browseHint.visibility = View.GONE
                    browseRecycler.visibility = View.VISIBLE
                    playlistAdapter.submit(snapshot.playlists)
                    browseRecycler.post {
                        if (screen != MusicScreen.BROWSE) return@post
                        if (browseRecycler.findFocus() == null) {
                            val index = lastPlaylistRowIndex.coerceAtMost(
                                (snapshot.playlists.size - 1).coerceAtLeast(0)
                            )
                            browseRecycler.findViewHolderForAdapterPosition(index)
                                ?.itemView?.requestFocus()
                        }
                    }
                }
                SpotifyPlaylistBrowseState.LOADING -> {
                    browseHint.visibility = View.VISIBLE
                    browseHint.text = context.getString(R.string.spotify_playlists_loading)
                    browseRecycler.visibility = View.GONE
                }
                SpotifyPlaylistBrowseState.EMPTY -> {
                    browseHint.visibility = View.VISIBLE
                    browseHint.text = context.getString(R.string.spotify_playlists_empty)
                    browseRecycler.visibility = View.GONE
                    playlistAdapter.submit(emptyList())
                }
                SpotifyPlaylistBrowseState.NEEDS_REAUTH -> {
                    browseHint.visibility = View.VISIBLE
                    browseHint.text = context.getString(R.string.spotify_playlists_needs_reauth)
                    browseRecycler.visibility = View.GONE
                    playlistAdapter.submit(emptyList())
                    launchReauthOnTap(browseHint)
                }
                SpotifyPlaylistBrowseState.NOT_LINKED -> {
                    browseHint.visibility = View.VISIBLE
                    browseHint.text = context.getString(R.string.spotify_queue_not_linked)
                    browseRecycler.visibility = View.GONE
                    playlistAdapter.submit(emptyList())
                }
                SpotifyPlaylistBrowseState.RATE_LIMITED -> {
                    browseHint.visibility = View.VISIBLE
                    browseHint.text = context.getString(R.string.spotify_queue_rate_limited)
                    browseRecycler.visibility = View.GONE
                }
                SpotifyPlaylistBrowseState.API_ERROR -> {
                    browseHint.visibility = View.VISIBLE
                    browseHint.text = context.getString(R.string.spotify_playlists_api_error)
                    browseRecycler.visibility = View.GONE
                }
            }
        }

        private fun renderTracksSnapshot(snapshot: SpotifyPlaylistTracksSnapshot) {
            val context = root.context
            when (snapshot.state) {
                SpotifyPlaylistBrowseState.OK -> {
                    tracksHint.visibility = View.GONE
                    tracksRecycler.visibility = View.VISIBLE
                    playlistTracksAdapter.submit(snapshot.tracks) {
                        if (screen != MusicScreen.PLAYLIST_TRACKS) return@submit
                        tracksRecycler.post {
                            if (screen != MusicScreen.PLAYLIST_TRACKS) return@post
                            val focused = root.findFocus()
                            val shouldPromote = focused == null ||
                                !isInside(focused, viewPlaylistTracks) ||
                                focused === tracksPlayAllFocus
                            if (shouldPromote) {
                                tracksRecycler.findViewHolderForAdapterPosition(0)
                                    ?.itemView?.requestFocus()
                            }
                        }
                    }
                }
                SpotifyPlaylistBrowseState.LOADING -> {
                    tracksHint.visibility = View.VISIBLE
                    tracksHint.text = context.getString(R.string.spotify_playlists_tracks_loading)
                    tracksRecycler.visibility = View.GONE
                }
                SpotifyPlaylistBrowseState.EMPTY -> {
                    tracksHint.visibility = View.VISIBLE
                    tracksHint.text = context.getString(R.string.spotify_playlists_tracks_empty)
                    tracksRecycler.visibility = View.GONE
                    playlistTracksAdapter.submit(emptyList())
                }
                SpotifyPlaylistBrowseState.NEEDS_REAUTH -> {
                    tracksHint.visibility = View.VISIBLE
                    tracksHint.text = context.getString(R.string.spotify_playlists_needs_reauth)
                    tracksRecycler.visibility = View.GONE
                    playlistTracksAdapter.submit(emptyList())
                    launchReauthOnTap(tracksHint)
                }
                SpotifyPlaylistBrowseState.NOT_LINKED -> {
                    tracksHint.visibility = View.VISIBLE
                    tracksHint.text = context.getString(R.string.spotify_queue_not_linked)
                    tracksRecycler.visibility = View.GONE
                    playlistTracksAdapter.submit(emptyList())
                }
                SpotifyPlaylistBrowseState.RATE_LIMITED -> {
                    tracksHint.visibility = View.VISIBLE
                    tracksHint.text = context.getString(R.string.spotify_queue_rate_limited)
                    tracksRecycler.visibility = View.GONE
                }
                SpotifyPlaylistBrowseState.API_ERROR -> {
                    tracksHint.visibility = View.VISIBLE
                    tracksHint.text = context.getString(R.string.spotify_playlists_api_error)
                    tracksRecycler.visibility = View.GONE
                }
            }
        }

        private fun launchReauthOnTap(view: TextView) {
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener {
                findActivity()?.let { activity ->
                    activity.startActivity(Intent(activity, SpotifyAuthActivity::class.java))
                }
            }
        }
    }

    companion object {
        private val playbackExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "spotify-playback").apply { isDaemon = true }
        }
        /** Upper bound for the inline loading bar — Spotify usually responds
         *  in well under a second, but the OkHttp read timeout is 20s. After
         *  this long, drop the bar even if the call hasn't returned. */
        private const val LOADING_SAFETY_MS = 8_000L
    }
}
