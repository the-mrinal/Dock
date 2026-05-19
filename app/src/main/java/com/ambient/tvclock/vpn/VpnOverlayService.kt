package com.ambient.tvclock.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View as AndroidView
import android.view.WindowManager
import android.widget.TextView
import com.ambient.tvclock.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.cos
import kotlin.math.sin

/**
 * Surfaces a tiny "🔒 <country>" pill in the top-right of the screen while
 * [WireGuardStateBus] reports [VpnState.Up], regardless of which app is in
 * the foreground. Driven by [SYSTEM_ALERT_WINDOW]; if the user hasn't granted
 * overlay permission yet this service is a no-op (started-and-stopped logs
 * a warning and exits).
 *
 * A 60-second drift cycle nudges the pill within a ~24px diameter so OLED
 * panels don't burn the glyphs in over time — same trick the dock's clock
 * and the streaming receiver pill use.
 */
class VpnOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var driftJob: Job? = null

    private var pillView: AndroidView? = null
    private var geoText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var driftPhase = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("VpnOverlayService: SYSTEM_ALERT_WINDOW not granted; stopping")
            stopSelf()
            return
        }
        collectJob = scope.launch {
            WireGuardStateBus.state.collectLatest { state ->
                when (state) {
                    is VpnState.Up -> showPill()
                    else -> hidePill()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        hidePill()
        collectJob?.cancel()
        driftJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun showPill() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (pillView == null) {
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_vpn_pill, null, false)
            geoText = view.findViewById(R.id.overlay_geo_text)
            geoText?.text = ""
            geoText?.visibility = AndroidView.GONE
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = OVERLAY_MARGIN_PX
                y = OVERLAY_MARGIN_PX
            }
            try {
                wm.addView(view, params)
                pillView = view
                layoutParams = params
                startDrift()
            } catch (e: Exception) {
                Timber.w(e, "VpnOverlayService: addView failed")
                return
            }
        }
        scope.launch { resolveAndApplyCountry() }
    }

    private fun hidePill() {
        val view = pillView ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            Timber.w(e, "VpnOverlayService: removeView failed")
        }
        pillView = null
        geoText = null
        layoutParams = null
        driftJob?.cancel()
        driftJob = null
    }

    private suspend fun resolveAndApplyCountry() {
        val country = withContext(Dispatchers.IO) { GeoIpResolver.currentCountry() }
        if (country.isNullOrBlank()) {
            geoText?.text = ""
            geoText?.visibility = AndroidView.GONE
        } else {
            geoText?.text = country
            geoText?.visibility = AndroidView.VISIBLE
        }
    }

    private fun startDrift() {
        driftJob?.cancel()
        driftJob = scope.launch {
            while (true) {
                delay(DRIFT_INTERVAL_MS)
                val params = layoutParams ?: return@launch
                val view = pillView ?: return@launch
                driftPhase = (driftPhase + 1) % DRIFT_STEPS
                val angle = (2.0 * Math.PI * driftPhase) / DRIFT_STEPS
                params.x = OVERLAY_MARGIN_PX + (DRIFT_RADIUS_PX * cos(angle)).toInt()
                params.y = OVERLAY_MARGIN_PX + (DRIFT_RADIUS_PX * sin(angle)).toInt()
                try {
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(view, params)
                } catch (e: Exception) {
                    Timber.w(e, "VpnOverlayService: drift updateViewLayout failed")
                    return@launch
                }
            }
        }
    }

    companion object {
        private const val OVERLAY_MARGIN_PX = 40
        private const val DRIFT_RADIUS_PX = 12
        private const val DRIFT_INTERVAL_MS = 60_000L
        private const val DRIFT_STEPS = 6

        fun start(context: Context) {
            val ctx = context.applicationContext
            ctx.startService(Intent(ctx, VpnOverlayService::class.java))
        }

        fun stop(context: Context) {
            val ctx = context.applicationContext
            ctx.stopService(Intent(ctx, VpnOverlayService::class.java))
        }
    }
}
