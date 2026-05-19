package com.ambient.tvclock.receiver.ui

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ambient.tvclock.R
import com.ambient.tvclock.receiver.Protocol
import com.ambient.tvclock.receiver.ReceiverStateBus
import com.ambient.tvclock.util.Logger

/**
 * Full-bleed SurfaceView container that hosts the AirPlay video stream.
 *
 * Sits inside MainActivity's root container; visibility is toggled by the
 * activeConnection observer. [currentSurface] returns the live Surface or
 * null when the SurfaceView hasn't been created yet (or has been destroyed),
 * matching the contract VideoDecoder expects.
 *
 * The inner SurfaceView is an [AspectRatioSurfaceView]: when the source video
 * dimensions are known (via [setVideoSize]), it shrinks to preserve the source
 * aspect ratio and is centered inside this FrameLayout, leaving black bars on
 * the unused sides — so a portrait phone is shown upright, not stretched to
 * fill a 16:9 TV.
 */
class StreamingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val surfaceView: AspectRatioSurfaceView = AspectRatioSurfaceView(context)

    /**
     * Composite pill in the upper-right corner. Replaces the legacy single
     * TextView with an eyebrow ("AIRPLAY · MRINAL'S IPHONE") + a JBMono
     * session-length subline ("7m 22s"). [setSenderInfo] writes the eyebrow
     * line; the session timer ticks from [resetSessionClock] until the next
     * surface destroy.
     */
    private val senderPillContainer: LinearLayout = LinearLayout(context)
    private val senderEyebrow: TextView = TextView(context)
    private val senderDuration: TextView = TextView(context)

    /** Backwards-compat handle — callers may read the text on the pill. */
    val senderPill: TextView get() = senderEyebrow

    @Volatile
    private var surface: Surface? = null

    private var sessionStartMs: Long = 0L
    private val durationTicker = object : Runnable {
        override fun run() {
            if (sessionStartMs == 0L) return
            val elapsedSec = ((SystemClock.elapsedRealtime() - sessionStartMs) / 1000L).toInt()
            val m = elapsedSec / 60
            val s = elapsedSec % 60
            senderDuration.text = "${m}m ${s.toString().padStart(2, '0')}s"
            postDelayed(this, 1_000L)
        }
    }

    init {
        val surfaceLayout = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        addView(surfaceView, surfaceLayout)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                // Push the new surface to the receiver pipeline so the active
                // MediaCodec rebinds onto it (via setOutputSurface). Without
                // this, a SurfaceView resize destroys the surface MediaCodec
                // was configured with — the decoder then writes into a dead
                // surface and the entire mirror pipeline goes garbage.
                ReceiverStateBus.publishVideoSurface(holder.surface)
                Logger.d("StreamingOverlay: surface created")
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Logger.d("StreamingOverlay: surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                ReceiverStateBus.publishVideoSurface(null)
                Logger.d("StreamingOverlay: surface destroyed")
                resetSessionClock()
            }
        })

        // Pill container: vertical eyebrow + JBMono duration. Backdrop is the
        // legacy translucent black scrim so the pill stays readable over any
        // sender's video. Typography matches the redesign's other eyebrows.
        senderPillContainer.orientation = LinearLayout.VERTICAL
        senderPillContainer.setBackgroundResource(R.drawable.bg_sender_pill)
        senderPillContainer.gravity = Gravity.END
        val padX = dp(18)
        val padY = dp(10)
        senderPillContainer.setPadding(padX, padY, padX, padY)

        senderEyebrow.setTextAppearance(R.style.TextAppearance_Dock_Eyebrow)
        senderEyebrow.setTextColor(Color.parseColor("#F5F5F5"))
        senderEyebrow.includeFontPadding = false
        senderEyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        senderPillContainer.addView(senderEyebrow)

        senderDuration.setTextAppearance(R.style.TextAppearance_Dock_MonoData)
        senderDuration.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        senderDuration.setTextColor(ContextCompat.getColor(context, R.color.dock_text_sec))
        senderDuration.includeFontPadding = false
        val durationLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) }
        senderPillContainer.addView(senderDuration, durationLp)

        val pillLayout = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            val margin = dp(24)
            setMargins(margin, margin, margin, margin)
        }
        addView(senderPillContainer, pillLayout)
    }

    fun currentSurface(): Surface? = surface

    /**
     * Sets the source video resolution so the SurfaceView can letterbox itself
     * to preserve the source aspect ratio. Safe to call repeatedly — only
     * triggers a relayout when the aspect actually changes (e.g. a sender flips
     * its phone from portrait to landscape mid-session).
     *
     * Pass `width <= 0` or `height <= 0` to reset to full-bleed (the default
     * before any video has arrived).
     */
    fun setVideoSize(width: Int, height: Int) {
        surfaceView.setVideoSize(width, height)
    }

    fun setSenderInfo(senderName: String, protocol: Protocol) {
        val protoLabel = when (protocol) {
            Protocol.AIRPLAY -> "AirPlay"
            Protocol.CAST -> "Cast"
            Protocol.MIRACAST -> "Miracast"
        }
        // Eyebrow renders ALL CAPS via the TextAppearance, so we feed plain
        // "AirPlay · Mrinal's iPhone" here and let the style transform.
        senderEyebrow.text = "$protoLabel · $senderName"
        // Start (or restart) the session timer if the pill is showing fresh.
        if (sessionStartMs == 0L) {
            sessionStartMs = SystemClock.elapsedRealtime()
            senderDuration.text = "0m 00s"
            post(durationTicker)
        }
    }

    /**
     * Reset the session timer (e.g. when the receiver hands off to a new
     * sender). The next `setSenderInfo` call will restart from zero.
     */
    fun resetSessionClock() {
        sessionStartMs = 0L
        removeCallbacks(durationTicker)
        senderDuration.text = ""
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

/**
 * SurfaceView that constrains itself to a given source aspect ratio (fit-inside,
 * a.k.a. letterbox / pillarbox). The parent supplies the available area; this
 * view picks the largest centered rectangle of the target aspect that fits.
 *
 * Until [setVideoSize] is called with valid dimensions, behaves exactly like a
 * regular SurfaceView (fills its measure spec) so the overlay still renders
 * before the first SPS arrives.
 */
private class AspectRatioSurfaceView(context: Context) : SurfaceView(context) {

    // Width / height of the source video. <= 0 means "unknown — fill parent".
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    fun setVideoSize(width: Int, height: Int) {
        val w = if (width > 0) width else 0
        val h = if (height > 0) height else 0
        if (w == sourceWidth && h == sourceHeight) return
        sourceWidth = w
        sourceHeight = h
        Logger.d("AspectRatioSurfaceView: source size set to ${w}x${h}")
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val srcW = sourceWidth
        val srcH = sourceHeight
        if (srcW <= 0 || srcH <= 0) return

        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)
        if (parentW <= 0 || parentH <= 0) return

        // Compare aspects without float division to keep this branch-allocation-free
        // on the layout hot path: source.w*parent.h vs source.h*parent.w.
        val sourceWideRelativeToParent = srcW.toLong() * parentH > srcH.toLong() * parentW
        val (measuredW, measuredH) = if (sourceWideRelativeToParent) {
            // Source is wider than the parent slot → width-bounded (letterbox top/bottom).
            parentW to ((parentW.toLong() * srcH) / srcW).toInt().coerceAtLeast(1)
        } else {
            // Source is taller (or equal) → height-bounded (pillarbox left/right).
            // This is the portrait-phone case the bug report describes.
            ((parentH.toLong() * srcW) / srcH).toInt().coerceAtLeast(1) to parentH
        }
        setMeasuredDimension(measuredW, measuredH)
    }
}
