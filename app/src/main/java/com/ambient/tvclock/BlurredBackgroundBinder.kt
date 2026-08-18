package com.ambient.tvclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.ambient.tvclock.background.BackgroundImage
import com.ambient.tvclock.background.BackgroundSurface
import com.ambient.tvclock.background.ImageLocation
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors

/**
 * Renders the full-bleed home background. Accepts two source kinds:
 *
 *  - now-playing album art (via [bind]) — the current track's artwork is
 *    downscaled and (optionally) blurred so the foreground stays readable.
 *  - an arbitrary image URL (via [applyPhotoUrl]) — used for the Unsplash
 *    background source. Bytes are downloaded once and cached in
 *    [AlbumArtLoader]'s shared LRU; the same crossfade pipeline applies.
 *
 * Blur can be toggled at runtime via [setBlurEnabled]. On API 31+ this flips
 * the ImageView's [RenderEffect]; on older API levels it switches the CPU
 * prep path between [AlbumArtBlur.blurForBackground] and a plain downscale.
 *
 * In ambient mode the background is *dimmed*, not discarded. Whether the
 * screensaver shows anything at all is [com.ambient.tvclock.background
 * .AmbientBackgroundPolicy]'s decision, made by the controller; this class
 * only paints what it is handed. It used to force a fade-out here, which is
 * why the screensaver was black even with a wallpaper selected.
 */
class BlurredBackgroundBinder(private val imageView: ImageView) : BackgroundSurface {

    /**
     * What the binder is currently rendering.
     *
     *  - [ALBUM_ART_WASH] — the dim, blurred backdrop that sits behind cards.
     *    Low alpha (`ACTIVE_ALPHA`), small decode size, blur honoured.
     *  - [SHARP_WALLPAPER] — the Unsplash wallpaper mode. High alpha, large
     *    decode (~1920 px), no blur regardless of preference so the photo
     *    reads clearly. The host layout (HomeScreenBinder) drops widget cards
     *    in tandem so the photo becomes the focal element.
     */
    enum class Mode { ALBUM_ART_WASH, SHARP_WALLPAPER }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastKey: String? = null
    // Tracks the last drawable that was fully phased in. We keep it around so
    // we can hand it to the *next* TransitionDrawable as the "from" layer
    // without nesting TransitionDrawables (which renders incorrectly on song
    // changes — the inner transition is left in an indeterminate state).
    private var currentDrawable: Drawable? = null
    private var ambient: Boolean = false
    private var blurEnabled: Boolean = true
    private var mode: Mode = Mode.ALBUM_ART_WASH

    init {
        applyRenderEffect()
    }

    private fun applyRenderEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Wallpaper mode is always sharp — the GPU RenderEffect would defeat
        // the whole point of switching to a high-resolution decode.
        val wantBlur = blurEnabled && mode == Mode.ALBUM_ART_WASH
        imageView.setRenderEffect(
            if (wantBlur) {
                RenderEffect.createBlurEffect(
                    RENDER_EFFECT_RADIUS,
                    RENDER_EFFECT_RADIUS,
                    Shader.TileMode.CLAMP
                )
            } else {
                null
            }
        )
    }

    private val targetAlpha: Float
        get() = when (mode) {
            Mode.SHARP_WALLPAPER -> if (ambient) AMBIENT_WALLPAPER_ALPHA else WALLPAPER_ALPHA
            Mode.ALBUM_ART_WASH -> if (ambient) AMBIENT_WASH_ALPHA else ACTIVE_ALPHA
        }

    private val decodeTargetPx: Int
        get() = when (mode) {
            Mode.SHARP_WALLPAPER -> WALLPAPER_DECODE_TARGET
            Mode.ALBUM_ART_WASH -> WASH_DECODE_TARGET
        }

    /**
     * Toggle background blur. Re-applies the GPU [RenderEffect] immediately;
     * the CPU prep path picks the new mode on the next [bind]/[applyPhotoUrl].
     * Caller is responsible for re-issuing the current source when the change
     * needs to apply retroactively on API < 31.
     */
    override fun setBlurEnabled(enabled: Boolean) {
        if (this.blurEnabled == enabled) return
        this.blurEnabled = enabled
        applyRenderEffect()
    }

    fun isBlurEnabled(): Boolean = blurEnabled

    fun bind(info: NowPlayingInfo?) {
        // Ambient is no longer a veto here — the controller decides whether a
        // screensaver shows anything, and dims via targetAlpha if it does.
        val art = info?.artwork
        val show = info?.hasActiveSession == true && art != null

        if (!show) {
            fadeOutIfShowing()
            return
        }

        switchMode(Mode.ALBUM_ART_WASH)

        val track = info!!
        // Belt-and-braces key: include every metadata field. Some sources reuse
        // mediaUri across tracks (or leave it blank) so we mix in title/artist
        // /album to make sure a real track change always invalidates the key.
        val key = "art|" + buildString {
            append(track.mediaUri); append('|')
            append(track.title); append('|')
            append(track.artist); append('|')
            append(track.album)
        }
        applyPreparedAsync(key) { art!! }
    }

    /**
     * Apply an arbitrary photo URL (Unsplash, etc.) as the background. The
     * URL itself is the dedupe key, so callers can re-call with the same URL
     * cheaply. Empty / blank URL fades the background out.
     *
     * Photo URLs are always rendered in [Mode.SHARP_WALLPAPER] — high alpha
     * and a high-resolution decode so the photo reads clearly. The caller
     * (BackgroundController + HomeScreenBinder) handles the matching foreground
     * layout change to drop the widget cards.
     */
    fun applyPhotoUrl(url: String) {
        applyPhotoUri(url, "url|$url")
    }

    /**
     * As [applyPhotoUrl], but with an explicit dedupe key. A locally cached
     * wallpaper keeps the same path when it is replaced, so the key has to be
     * the content hash or the new image would never repaint.
     */
    fun applyPhotoUri(uri: String, key: String) {
        if (uri.isBlank()) {
            fadeOutIfShowing()
            return
        }
        switchMode(Mode.SHARP_WALLPAPER)
        applyPreparedAsync(key) { loadSoftwareBitmap(uri) }
    }

    private fun switchMode(target: Mode) {
        if (mode == target) return
        mode = target
        applyRenderEffect()
    }

    /**
     * Fade the background to transparent — used when the controller switches
     * us to the "Black" source explicitly. Idempotent.
     */
    fun fadeBackgroundOut() {
        fadeOutIfShowing()
    }

    /**
     * Drop the current bitmap reference entirely. Used on memory-pressure
     * trim signals and when the controller stops (MainActivity.onStop). The
     * next [bind] or [applyPhotoUrl] call will re-decode from the source.
     */
    override fun releaseBitmap() {
        lastKey = null
        currentDrawable = null
        imageView.animate().cancel()
        imageView.setImageDrawable(null)
        imageView.alpha = 0f
    }

    /**
     * Slightly dim the background while ambient/screensaver is engaged so the
     * lone clock still reads as the primary element on the screen.
     */
    override fun setAmbient(ambient: Boolean) {
        if (this.ambient == ambient) return
        this.ambient = ambient
        // Only the brightness changes. What is painted while idle — a
        // wallpaper, the album art, or nothing at all — is the controller's
        // call, and it re-issues show() around this.
        if (lastKey != null) {
            imageView.animate().cancel()
            imageView.animate().alpha(targetAlpha).setDuration(FADE_IN_MS).start()
        }
    }

    /** Paint [image], or fade to nothing when it is null. */
    override fun show(image: BackgroundImage?) {
        when (image) {
            null -> fadeBackgroundOut()
            is BackgroundImage.AlbumArt -> bind(image.info)
            is BackgroundImage.Remote -> applyPhotoUri(image.uri, image.key)
        }
    }

    private fun fadeOutIfShowing() {
        if (lastKey != null) {
            lastKey = null
            fadeOut()
        }
    }

    private fun applyPreparedAsync(key: String, fetchSource: () -> Bitmap?) {
        if (key == lastKey) return
        lastKey = key
        executor.execute {
            val source = try {
                fetchSource()
            } catch (_: Exception) {
                null
            }
            val prepared = if (source != null) prepareForBackground(source) else null
            mainHandler.post {
                // Stale: a newer bind has overtaken this one — drop it.
                if (lastKey != key) return@post
                if (prepared != null) {
                    applyBitmap(prepared)
                } else {
                    // Prep failed for this bitmap. Roll lastKey back so the
                    // next bind() for the same source can retry instead of
                    // short-circuiting on the now-stale key match.
                    lastKey = null
                }
            }
        }
    }

    private fun prepareForBackground(source: Bitmap): Bitmap? {
        return try {
            when (mode) {
                Mode.SHARP_WALLPAPER -> {
                    // The bitmap was already decoded at WALLPAPER_DECODE_TARGET
                    // via decodeSoftware(). No further downscale / blur — the
                    // ImageView will centerCrop it across the full TV screen.
                    source
                }
                Mode.ALBUM_ART_WASH -> when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        AlbumArtBlur.downscaleForBackground(source)
                    blurEnabled ->
                        AlbumArtBlur.blurForBackground(source)
                    else ->
                        AlbumArtBlur.downscaleForBackground(source)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bytes for a local or `http(s)://` image. [ImageLocation] owns the
     * which-is-it decision — see the note there on why a local URI must never
     * be retried over the network. The on-disk branch is what lets a cached
     * wallpaper render with no network at all: a dock that boots offline
     * still comes up showing yesterday's image.
     */
    private fun loadSoftwareBitmap(uri: String): Bitmap? =
        when (val location = ImageLocation.of(uri)) {
            is ImageLocation.OnDisk -> try {
                if (!location.file.isFile) null else decodeSoftware(location.file.readBytes())
            } catch (_: Exception) {
                null
            }
            is ImageLocation.Url -> fetchSoftwareBitmap(location.value)
            null -> null
        }

    private fun fetchSoftwareBitmap(url: String): Bitmap? {
        val request = Request.Builder().url(url).get().build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                decodeSoftware(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSoftware(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, decodeTargetPx)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Wallpaper mode never reads pixels back (no CPU blur), so we can
            // ask for HARDWARE on API 26+ to keep the 14 MB / 2400 px bitmap
            // out of the Dalvik heap. Wash mode keeps ARGB_8888 because the
            // CPU box-blur path on API < 31 needs to scan pixels.
            inPreferredConfig = if (
                mode == Mode.SHARP_WALLPAPER &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                Bitmap.Config.HARDWARE
            } else {
                Bitmap.Config.ARGB_8888
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun computeSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun applyBitmap(bitmap: Bitmap) {
        val from: Drawable = currentDrawable ?: ColorDrawable(Color.TRANSPARENT)
        val to = BitmapDrawable(imageView.resources, bitmap)
        val transition = TransitionDrawable(arrayOf(from, to))
        transition.isCrossFadeEnabled = true
        imageView.setImageDrawable(transition)
        transition.startTransition(CROSSFADE_MS.toInt())
        // The end state of this transition is `to`; stash it so the next
        // bind starts its fade from this bitmap rather than from a stale
        // TransitionDrawable.
        currentDrawable = to

        imageView.animate().cancel()
        if (imageView.alpha < targetAlpha - 0.01f) {
            imageView.animate()
                .alpha(targetAlpha)
                .setDuration(FADE_IN_MS)
                .start()
        } else {
            imageView.alpha = targetAlpha
        }
    }

    private fun fadeOut() {
        imageView.animate().cancel()
        imageView.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                if (lastKey == null) {
                    imageView.setImageDrawable(null)
                    currentDrawable = null
                }
            }
            .start()
    }

    companion object {
        // Album-art-wash mode: visible enough to feel like an ambient backdrop,
        // dim enough that white text and widget cards still read clearly.
        private const val ACTIVE_ALPHA = 0.32f
        // Sharp-wallpaper mode: photo is the focal point. The HomeScreenBinder
        // drops the widget cards in tandem so foreground text reads on top of
        // the photo directly via its drop-shadow.
        private const val WALLPAPER_ALPHA = 0.95f
        // Screensaver: the wallpaper stays up but steps back so the drifting
        // clock still owns the room. The album-art wash goes nearly dark —
        // it was only ever a backdrop for cards that ambient mode hides.
        private const val AMBIENT_WALLPAPER_ALPHA = 0.72f
        private const val AMBIENT_WASH_ALPHA = 0.18f

        // GPU Gaussian radius (in output pixels). On API 31+ this *is* the blur
        // — the input bitmap is just a downscaled crop of the album art. Tuned
        // to roughly match the visual weight of the legacy CPU pyramid+box pass
        // when the bitmap is centerCrop'd to a 1080p / 4K background.
        private const val RENDER_EFFECT_RADIUS = 28f

        // Decode targets are mode-specific: the wash mode is heavily blurred
        // so a 512 px source is plenty, while the sharp wallpaper path keeps
        // the photo near its source resolution (Unsplash `full` is ~2400 px)
        // so it stays crisp on 4K panels. ARGB_8888 @ 2400×1500 ≈ 14 MB —
        // acceptable for a single background bitmap on Fire TV Stick 4K Max.
        private const val WASH_DECODE_TARGET = 512
        private const val WALLPAPER_DECODE_TARGET = 2400

        private const val FADE_IN_MS = 700L
        private const val FADE_OUT_MS = 700L
        private const val CROSSFADE_MS = 900L

        private val http = HttpClients.shared
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "home-bg-blur").apply { isDaemon = true }
        }
    }
}
