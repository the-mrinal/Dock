package com.ambient.tvclock.background

import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

/**
 * Burn-in protection: nudge a view a few pixels every minute so a static
 * element never sits on the same OLED sub-pixels for hours.
 *
 * Extracted from MainActivity so the system screensaver shares one
 * implementation with the in-app ambient mode instead of carrying a second
 * copy that could drift — in the other sense — out of step with it.
 *
 * Translation rather than layout, so the corners of the surrounding safe area
 * are never clipped.
 */
class AmbientDrifter(
    private val target: View,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxDriftXDp: Int = DEFAULT_DRIFT_X_DP,
    private val maxDriftYDp: Int = DEFAULT_DRIFT_Y_DP,
    private val animationMs: Long = DEFAULT_ANIMATION_MS,
    private val random: Random = Random.Default,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            driftOnce()
            handler.postDelayed(this, intervalMs)
        }
    }

    /** Begin drifting after [kickoffMs], so entering ambient settles first. */
    fun start(kickoffMs: Long = DEFAULT_KICKOFF_MS) {
        if (running) return
        running = true
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, kickoffMs)
    }

    /** Stop and glide back to centre. */
    fun stop(recenterMs: Long = DEFAULT_RECENTER_MS) {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        target.animate().cancel()
        target.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(recenterMs)
            .start()
    }

    fun driftOnce() {
        val density = target.resources.displayMetrics.density
        val x = offset(maxDriftXDp, density)
        val y = offset(maxDriftYDp, density)
        target.animate().cancel()
        target.animate()
            .translationX(x)
            .translationY(y)
            .setDuration(animationMs)
            .start()
    }

    private fun offset(maxDp: Int, density: Float): Float {
        val maxPx = (maxDp * density).toInt()
        if (maxPx <= 0) return 0f
        return random.nextInt(-maxPx, maxPx + 1).toFloat()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
        const val DEFAULT_KICKOFF_MS = 2_000L
        const val DEFAULT_ANIMATION_MS = 1_400L
        const val DEFAULT_RECENTER_MS = 400L
        const val DEFAULT_DRIFT_X_DP = 32
        const val DEFAULT_DRIFT_Y_DP = 14
    }
}
