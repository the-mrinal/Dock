package com.ambient.tvclock.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Full-bleed blurred radial wash, driven by [setSeed] (cover id) and
 * [setIntensity] (0..1).
 *
 * The HTML prototype lays two radial gradients on top of `T.bg` and runs
 * `filter: blur(120px) saturate(115%)` on the composite. We render an
 * equivalent gradient into a small bitmap and upscale; the upscale itself
 * does most of the visual softening. On API 31+ we additionally attach a
 * [RenderEffect] blur on the View — that's free and GPU-accelerated. On older
 * devices we apply a one-pass box blur to the bitmap before upscaling so the
 * result still looks soft.
 *
 * A subtle 1px grain layer sits on top to take the curse off the gradient.
 */
class ArtWashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ─── Public API ─────────────────────────────────────────────────

    /** Crossfade duration when seed or intensity change. Matches Phase 0 plan. */
    var crossfadeDurationMs: Long = 360L

    private var pendingSeed: String? = null
    private var currentSeed: String = ""
    private var intensity: Float = DEFAULT_INTENSITY

    // We keep two cached layer bitmaps and crossfade between them.
    private var currentLayer: Bitmap? = null
    private var nextLayer: Bitmap? = null
    private var transitionT: Float = 1f
    private var fadeAnimator: ValueAnimator? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = COLOR_BG }
    private val dstRect = Rect()

    private val bitmapCache = LinkedHashMap<String, Bitmap>(0, 0.75f, true)

    init {
        // The view is purely decorative — never eat focus or input.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Hardware blur on the rendered surface in addition to the
            // bitmap upscaling. Cheap on API 31+ and dramatically softens
            // the radial gradients further.
            setLayerType(LAYER_TYPE_HARDWARE, null)
            setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP))
        } else {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
    }

    /**
     * Update the wash palette source. Crossfades to the new wash if the seed
     * changes; no-ops if identical. Safe to call repeatedly with the same id.
     */
    fun setSeed(seed: String) {
        if (seed == currentSeed && pendingSeed == null) return
        pendingSeed = seed
        scheduleCrossfade()
    }

    /**
     * Adjust the wash opacity. Higher intensity reveals more of the seeded
     * gradient; lower values melt into solid `dock_bg`. The HTML prototype
     * ranges from 0.10 (Settings) up to 0.55 (NowPlaying).
     */
    fun setIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == intensity) return
        intensity = clamped
        invalidate()
    }

    // ─── Lifecycle ──────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (currentSeed.isNotEmpty() && currentLayer == null) {
            currentLayer = buildLayer(currentSeed)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        dstRect.set(0, 0, w, h)

        // Solid base — covers the case where intensity is dialled all the way down.
        dimPaint.color = COLOR_BG
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dimPaint)

        val cur = currentLayer
        val nxt = nextLayer

        if (cur != null) {
            basePaint.alpha = (255f * intensity * (1f - transitionT)).toInt().coerceIn(0, 255)
            canvas.drawBitmap(cur, null, dstRect, basePaint)
        }
        if (nxt != null) {
            basePaint.alpha = (255f * intensity * transitionT).toInt().coerceIn(0, 255)
            canvas.drawBitmap(nxt, null, dstRect, basePaint)
        }

        // 1px grain — barely-there sparkle.
        grainPaint.color = COLOR_GRAIN
        grainPaint.alpha = 14
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                canvas.drawPoint(x.toFloat(), y.toFloat(), grainPaint)
                x += 3
            }
            y += 3
        }
    }

    // ─── Internals ──────────────────────────────────────────────────

    private fun scheduleCrossfade() {
        val seed = pendingSeed ?: return
        pendingSeed = null

        // First seed ever — no crossfade, just paint it in.
        if (currentLayer == null) {
            currentSeed = seed
            currentLayer = buildLayer(seed)
            transitionT = 1f
            invalidate()
            return
        }

        // Same seed in flight — skip.
        if (seed == currentSeed) return

        nextLayer = buildLayer(seed)
        currentSeed = seed

        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = crossfadeDurationMs
            addUpdateListener {
                transitionT = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentLayer = nextLayer
                    nextLayer = null
                    transitionT = 1f
                    invalidate()
                }
            })
        }
        fadeAnimator?.start()
    }

    private fun buildLayer(seed: String): Bitmap {
        bitmapCache[seed]?.let { return it }

        val palette = CoverPalette.coverBg(seed)
        val bg = palette[0]
        val fg1 = palette[1]
        val fg2 = palette[2]

        val bmp = Bitmap.createBitmap(LAYER_W, LAYER_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Base.
        p.color = bg
        c.drawRect(0f, 0f, LAYER_W.toFloat(), LAYER_H.toFloat(), p)
        // Two radial blobs, mirroring the HTML coords (22% / 78%).
        p.shader = RadialGradient(
            LAYER_W * 0.22f, LAYER_H * 0.38f,
            max(LAYER_W, LAYER_H) * 0.55f,
            withAlpha(fg1, 1f), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, LAYER_W.toFloat(), LAYER_H.toFloat(), p)
        p.shader = RadialGradient(
            LAYER_W * 0.78f, LAYER_H * 0.65f,
            max(LAYER_W, LAYER_H) * 0.60f,
            withAlpha(fg2, 1f), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, LAYER_W.toFloat(), LAYER_H.toFloat(), p)

        // Pre-blur on API < 31. On API 31+ the View's RenderEffect already
        // softens everything, but a single box blur pass still helps the
        // gradient stops bleed into each other.
        val blurred = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            boxBlur(bmp, 4)
        } else {
            boxBlur(bmp, 2)
        }
        if (blurred !== bmp) bmp.recycle()

        rememberLayer(seed, blurred)
        return blurred
    }

    private fun rememberLayer(seed: String, bitmap: Bitmap) {
        bitmapCache[seed] = bitmap
        while (bitmapCache.size > MAX_CACHED) {
            val eldest = bitmapCache.entries.iterator().next()
            bitmapCache.remove(eldest.key)
            if (eldest.value !== currentLayer && eldest.value !== nextLayer) {
                eldest.value.recycle()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fadeAnimator?.cancel()
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (255f * alpha.coerceIn(0f, 1f)).toInt())

    /**
     * Simple box blur of [src] using `pass` passes of a 3-tap window in
     * each direction. Operates on the 160×90 layer in well under a frame.
     */
    private fun boxBlur(src: Bitmap, passes: Int): Bitmap {
        if (passes <= 0) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val scratch = IntArray(w * h)
        var input = pixels
        var output = scratch
        for (p in 0 until passes) {
            blur1D(input, output, w, h, horizontal = true)
            blur1D(output, input, w, h, horizontal = false)
        }
        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        out.setPixels(input, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * One-dimensional 3-sample blur. Cheap and good enough for 160×90.
     * Always 3-tap; we control the visual softness by chaining passes.
     */
    private fun blur1D(input: IntArray, output: IntArray, w: Int, h: Int, horizontal: Boolean) {
        if (horizontal) {
            for (y in 0 until h) {
                val rowStart = y * w
                for (x in 0 until w) {
                    val a = input[rowStart + max(0, x - 1)]
                    val b = input[rowStart + x]
                    val c = input[rowStart + min(w - 1, x + 1)]
                    output[rowStart + x] = avg3(a, b, c)
                }
            }
        } else {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = input[max(0, y - 1) * w + x]
                    val b = input[y * w + x]
                    val c = input[min(h - 1, y + 1) * w + x]
                    output[y * w + x] = avg3(a, b, c)
                }
            }
        }
    }

    /**
     * Average three ARGB-packed ints channel-by-channel. We don't care which
     * channel sits in which byte — all three inputs share the same layout and
     * we reassemble with the same shifts, so the result is colour-correct.
     */
    private fun avg3(a: Int, b: Int, c: Int): Int {
        val a3 = (a ushr 24) and 0xFF
        val a2 = (a ushr 16) and 0xFF
        val a1 = (a ushr 8) and 0xFF
        val a0 = a and 0xFF
        val b3 = (b ushr 24) and 0xFF
        val b2 = (b ushr 16) and 0xFF
        val b1 = (b ushr 8) and 0xFF
        val b0 = b and 0xFF
        val c3 = (c ushr 24) and 0xFF
        val c2 = (c ushr 16) and 0xFF
        val c1 = (c ushr 8) and 0xFF
        val c0 = c and 0xFF
        val o3 = (a3 + b3 + c3) / 3
        val o2 = (a2 + b2 + c2) / 3
        val o1 = (a1 + b1 + c1) / 3
        val o0 = (a0 + b0 + c0) / 3
        return (o3 shl 24) or (o2 shl 16) or (o1 shl 8) or o0
    }

    companion object {
        private const val DEFAULT_INTENSITY = 0.40f
        private const val COLOR_BG = 0xFF0A0A0B.toInt()
        private const val COLOR_GRAIN = 0xFFFFFFFF.toInt()

        // The wash bitmap is rendered tiny + blurred; we then upscale at draw
        // time so the bilinear upscale does the heavy lifting for free.
        private const val LAYER_W = 160
        private const val LAYER_H = 90

        // 4-page TV app → at most 4 distinct seeds in flight, plus a couple
        // for crossfades. Cap keeps RAM bounded if a track-driven wash ever
        // tries to thrash the cache.
        private const val MAX_CACHED = 8
    }
}
