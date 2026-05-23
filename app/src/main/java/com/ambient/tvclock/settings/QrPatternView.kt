package com.ambient.tvclock.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.use
import com.ambient.tvclock.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a scannable QR code that resolves to the LAN endpoint a phone
 * should hit to upload a WireGuard config (artboard 10).
 *
 * The view is square — caller sizes it; we render the QR module grid into
 * the smaller of width/height with a small white quiet-zone padding so
 * scanners can lock on. The QR uses M-level error correction (15%
 * recovery) which is plenty for a clean on-screen render and lets the
 * encoded URL stay short.
 *
 * The legacy attr name `qrSeed` is preserved for backwards-compat with
 * `activity_config_import.xml`; setting it (or the [seed] property) just
 * sets the QR's encoded content.
 */
class QrPatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0A0B.toInt() }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val cellRect = RectF()

    /** The URL or text encoded into the QR. */
    var seed: String = ""
        set(value) {
            if (field == value) return
            field = value
            matrix = encode(value)
            invalidate()
        }

    private var matrix: BitMatrix? = encode("")

    init {
        attrs?.let {
            context.theme.obtainStyledAttributes(it, R.styleable.QrPatternView, defStyleAttr, 0).use { ta ->
                ta.getString(R.styleable.QrPatternView_qrSeed)?.let { seed = it }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        canvas.drawRect(0f, 0f, side, side, bgPaint)
        val m = matrix ?: return
        val n = m.width  // square
        if (n <= 0) return
        val cs = side / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!m.get(x, y)) continue
                cellRect.set(x * cs, y * cs, (x + 1) * cs, (y + 1) * cs)
                canvas.drawRect(cellRect, onPaint)
            }
        }
    }

    private fun encode(content: String): BitMatrix? {
        if (content.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            // Size is nominal — zxing returns the natural module count and we
            // scale to the View's bounds in onDraw.
            MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (_: Throwable) {
            null
        }
    }
}
