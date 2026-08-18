package com.ambient.tvclock.grainstorm

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * The panel's real pixel size.
 *
 * Nothing in the dock asked the display about itself before this — the only
 * `displayMetrics` use anywhere is `density`, for dp maths. The wallpaper
 * feature needs the true resolution so the library can render an exact-fit
 * image rather than something scaled to fit.
 */
object DisplayMetricsProvider {

    data class PanelSize(val width: Int, val height: Int) {

        /** "3840×2160 · 16:9" — what Settings shows next to "This screen". */
        fun describe(): String = "$width×$height · ${aspectLabel()}"

        /** The nearest common aspect name, or the exact ratio if it is unusual. */
        fun aspectLabel(): String {
            if (width <= 0 || height <= 0) return "unknown"
            val ratio = width.toDouble() / height.toDouble()
            val known = listOf(
                "16:9" to 16.0 / 9.0,
                "16:10" to 16.0 / 10.0,
                "21:9" to 21.0 / 9.0,
                "4:3" to 4.0 / 3.0,
                "3:2" to 3.0 / 2.0,
                "9:16" to 9.0 / 16.0,
            )
            // Loose enough that 3440×1440 reads as the "21:9" it is sold as
            // rather than its true 43:18, and still far tighter than the gap
            // between any two entries above.
            val closest = known.minByOrNull { kotlin.math.abs(it.second - ratio) }
            if (closest != null && kotlin.math.abs(closest.second - ratio) < 0.06) return closest.first
            val divisor = gcd(width, height)
            return "${width / divisor}:${height / divisor}"
        }

        val isUsable: Boolean get() = width >= 200 && height >= 200

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }

    /**
     * The full panel size, including any area under system bars — a wallpaper
     * covers the whole screen, so the usable-area metrics would be wrong here.
     */
    @Suppress("DEPRECATION")
    fun panelSize(context: Context): PanelSize {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return PanelSize(0, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            PanelSize(bounds.width(), bounds.height())
        } else {
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            PanelSize(metrics.widthPixels, metrics.heightPixels)
        }
    }
}
