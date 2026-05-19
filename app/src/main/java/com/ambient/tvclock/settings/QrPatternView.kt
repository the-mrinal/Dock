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

/**
 * Deterministic 25×25 QR-style hash pattern, ported verbatim from the HTML
 * prototype's `QrPattern` function. This is **decorative only** — the project
 * does not use zxing today, and rather than introducing a transitive dep we
 * mirror the design exactly so artboard 10 reproduces. The seed driver
 * (`seed = "${url}"`) means the same LAN URL produces the same pattern across
 * runs.
 *
 * Cell math (from the HTML):
 *   - 25×25 grid
 *   - djb2-ish hash: `h = ((h << 5) + h + x*31 + y*17) >>> 0`, `on = (h & 3) > 1`
 *   - three finder squares (top-left, top-right, bottom-left), 7×7 each,
 *     drawn as a ring + 3×3 inner block, overriding the hash cells.
 */
class QrPatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0A0B.toInt() }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val cellRect = RectF()

    /**
     * Seed string. Empty string falls back to the constant the HTML uses on
     * first render, which keeps the pattern stable across cold starts.
     */
    var seed: String = ""
        set(value) {
            if (field == value) return
            field = value
            cells = buildCells(value)
            invalidate()
        }

    private var cells: BooleanArray = buildCells("")

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
        val cs = side / N
        canvas.drawRect(0f, 0f, side, side, bgPaint)
        for (y in 0 until N) {
            for (x in 0 until N) {
                if (!cells[y * N + x]) continue
                cellRect.set(x * cs, y * cs, (x + 1) * cs, (y + 1) * cs)
                canvas.drawRect(cellRect, onPaint)
            }
        }
    }

    companion object {
        private const val N = 25

        private fun inFinder(x: Int, y: Int, xx: Int, yy: Int): Boolean =
            x >= xx && x < xx + 7 && y >= yy && y < yy + 7

        private fun inFinderInner(x: Int, y: Int, xx: Int, yy: Int): Boolean =
            x >= xx + 2 && x < xx + 5 && y >= yy + 2 && y < yy + 5

        private fun inFinderRing(x: Int, y: Int, xx: Int, yy: Int): Boolean {
            val inside = inFinder(x, y, xx, yy) && !(x > xx && x < xx + 6 && y > yy && y < yy + 6)
            return inside || inFinderInner(x, y, xx, yy)
        }

        private fun buildCells(seed: String): BooleanArray {
            val out = BooleanArray(N * N)
            // The HTML hash is seedless; injecting the seed by folding it into
            // the initial `h` keeps the same distribution but tied to LAN URL.
            var h: Long = 5381L
            for (ch in seed) {
                h = ((h * 33L) + ch.code) and 0xFFFFFFFFL
            }
            val finders = arrayOf(
                intArrayOf(0, 0),
                intArrayOf(N - 7, 0),
                intArrayOf(0, N - 7),
            )
            for (y in 0 until N) {
                for (x in 0 until N) {
                    h = ((h shl 5) + h + x * 31L + y * 17L) and 0xFFFFFFFFL
                    var on = (h and 3L) > 1L
                    for (f in finders) {
                        if (inFinder(x, y, f[0], f[1])) {
                            on = inFinderRing(x, y, f[0], f[1])
                        }
                    }
                    out[y * N + x] = on
                }
            }
            return out
        }
    }
}
