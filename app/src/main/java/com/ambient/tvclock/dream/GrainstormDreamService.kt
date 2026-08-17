package com.ambient.tvclock.dream

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.widget.ImageView
import android.widget.TextView
import com.ambient.tvclock.BackgroundPreferences
import com.ambient.tvclock.BlurredBackgroundBinder
import com.ambient.tvclock.R
import com.ambient.tvclock.background.AmbientBackgroundPolicy
import com.ambient.tvclock.background.AmbientDrifter
import com.ambient.tvclock.background.BackgroundSource
import com.ambient.tvclock.grainstorm.GrainstormBackgroundSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The system screensaver: the wallpaper you chose, plus a drifting clock.
 *
 * Runs outside MainActivity, so Fire TV can show it whatever app is in front.
 * That independence is also its constraint — the pollers are per-Activity
 * instances and are not reachable here, so this depends only on a
 * [BackgroundSource], which owns its own fetching and caching.
 *
 * A dream has no dashboard to fade, so it needs no ambient policy of its own:
 * being on screen *is* the ambient state. It reads the same
 * `background_when_ambient` preference so the two screensavers agree about
 * what to show, falling back to the wallpaper when the preference says
 * "same as awake" (there is no awake source out here).
 *
 * Fire OS does not expose the screensaver picker on every build. Where it is
 * missing, select this explicitly:
 *   adb shell settings put secure screensaver_components \
 *     com.ambient.tvclock.firetv/com.ambient.tvclock.dream.GrainstormDreamService
 * Where even that is blocked, the in-app ambient mode covers the same ground.
 */
class GrainstormDreamService : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private var source: BackgroundSource? = null
    private var drifter: AmbientDrifter? = null
    private var binder: BlurredBackgroundBinder? = null
    private var clock: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            // Only the minute matters here; sleeping to the boundary keeps the
            // dream from waking the CPU once a second all night.
            handler.postDelayed(this, msUntilNextMinute())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        isScreenBright = true
        setContentView(R.layout.dream_grainstorm)

        val background = findViewById<ImageView>(R.id.dreamBackground)
        clock = findViewById(R.id.dreamClock)
        binder = BlurredBackgroundBinder(background).apply {
            setBlurEnabled(false)   // a wallpaper is the subject, not a wash
            setAmbient(true)        // dimmed, so the clock still owns the room
        }
        drifter = AmbientDrifter(findViewById(R.id.dreamClockGroup))
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        updateClock()
        handler.post(tick)
        drifter?.start()

        val id = sourceId()
        if (id == null) {
            binder?.show(null)
            return
        }
        val source = GrainstormBackgroundSource(this).takeIf { id == GrainstormBackgroundSource.ID }
        this.source = source
        if (source == null) {
            // A source this dream cannot host on its own (album art needs the
            // dashboard's MediaSession plumbing). Black is the honest answer.
            binder?.show(null)
            return
        }
        source.current()?.let { binder?.show(it) }
        source.start { image -> handler.post { binder?.show(image) } }
    }

    override fun onDreamingStopped() {
        handler.removeCallbacks(tick)
        drifter?.stop()
        source?.stop()
        source = null
        binder?.releaseBitmap()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        source?.stop()
        source = null
        binder = null
        clock = null
        drifter = null
        super.onDetachedFromWindow()
    }

    /**
     * Which background to paint. `same` has no meaning out here — there is no
     * awake dashboard to match — so it resolves to the wallpaper, which is
     * what someone choosing it would have meant.
     */
    private fun sourceId(): String? {
        val preference = BackgroundPreferences.whenAmbientSource(this)
        if (preference == AmbientBackgroundPolicy.SAME_AS_AWAKE) return GrainstormBackgroundSource.ID
        return AmbientBackgroundPolicy.fromPreference(preference)
            .ambientSourceId(GrainstormBackgroundSource.ID)
    }

    private fun updateClock() {
        clock?.text = timeFormat.format(Date())
    }

    private fun msUntilNextMinute(): Long {
        val now = System.currentTimeMillis()
        return 60_000L - (now % 60_000L)
    }

    private val timeFormat by lazy {
        SimpleDateFormat(
            if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm",
            Locale.getDefault(),
        )
    }
}
