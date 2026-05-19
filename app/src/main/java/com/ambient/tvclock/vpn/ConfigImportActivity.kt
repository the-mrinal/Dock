package com.ambient.tvclock.vpn

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ambient.tvclock.R
import com.ambient.tvclock.settings.QrPatternView
import com.ambient.tvclock.ui.ArtWashView
import com.ambient.tvclock.ui.PillButton
import com.ambient.tvclock.ui.PulseHaloView

/**
 * Hosts the LAN HTTP drop-in server for the duration this Activity is alive
 * and renders artboard 10 (Settings · LAN .conf drop).
 *
 * The service plumbing (LanIp, ConfigImportSession, ConfigImportServer, the
 * 5-minute TTL) is untouched — only the UI surface moves to the redesign.
 *
 * The QR pattern is decorative today; we seed it with the drop URL so the
 * pattern is at least deterministic per device. When the project wants a
 * scannable QR we'll port the same seed to zxing inside the existing card.
 */
class ConfigImportActivity : AppCompatActivity(), ConfigImportServer.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: ConfigImportSession? = null
    private var server: ConfigImportServer? = null
    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    private lateinit var urlView: TextView
    private lateinit var pinView: TextView
    private lateinit var statusView: TextView
    private lateinit var listeningHalo: PulseHaloView
    private lateinit var qrPattern: QrPatternView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_import)

        findViewById<ArtWashView>(R.id.importArtWash).apply {
            setSeed("settings-lan")
            setIntensity(0.08f)
        }

        urlView = findViewById(R.id.import_url_value)
        pinView = findViewById(R.id.import_pin_value)
        statusView = findViewById(R.id.import_status)
        listeningHalo = findViewById(R.id.import_listening_halo)
        qrPattern = findViewById(R.id.importQrPattern)
        findViewById<PillButton>(R.id.import_cancel).setOnClickListener { finish() }

        val lanIp = LanIp.firstIPv4()
        if (lanIp == null) {
            urlView.text = getString(R.string.vpn_import_no_lan)
            pinView.text = ""
            statusView.text = ""
            listeningHalo.isPulsing = false
            return
        }

        val sess = ConfigImportSession.generate().also { session = it }
        val srv = ConfigImportServer(applicationContext, sess, this).also { server = it }
        if (!srv.start()) {
            statusView.text = getString(R.string.vpn_import_status_error, "could not bind a LAN port")
            listeningHalo.isPulsing = false
            return
        }

        val url = getString(R.string.vpn_import_url_value, lanIp, srv.port)
        urlView.text = "${url}drop"
        pinView.text = sess.pin
        qrPattern.seed = url
        listeningHalo.isPulsing = true
        updateCountdown()
        mainHandler.post(ticker)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        server?.stop()
        super.onDestroy()
    }

    override fun onConfigAccepted() {
        mainHandler.post {
            statusView.text = getString(R.string.vpn_drop_listening_received)
            listeningHalo.isPulsing = false
            mainHandler.postDelayed({ finish() }, 1_500)
        }
    }

    override fun onServerError(message: String) {
        mainHandler.post {
            statusView.text = getString(R.string.vpn_import_status_error, message)
        }
    }

    private fun updateCountdown() {
        val sess = session ?: return
        val remainingMillis = sess.millisRemaining()
        if (remainingMillis <= 0) {
            statusView.text = getString(R.string.vpn_drop_listening_expired)
            listeningHalo.isPulsing = false
            mainHandler.removeCallbacks(ticker)
            server?.stop()
            return
        }
        val totalSec = remainingMillis / 1_000
        val mins = (totalSec / 60).toInt()
        val secs = (totalSec % 60).toInt()
        statusView.text = getString(R.string.vpn_drop_listening, mins, secs)
    }
}
