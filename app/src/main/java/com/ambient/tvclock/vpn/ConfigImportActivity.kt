package com.ambient.tvclock.vpn

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ambient.tvclock.R

/**
 * Hosts the LAN HTTP drop-in server for the duration this Activity is alive.
 * Shows the URL + PIN on the TV so the user knows where to POST their wg0.conf.
 * Finishes on success, cancel, or the session's 5-minute TTL.
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
    private lateinit var curlView: TextView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_import)

        urlView = findViewById(R.id.import_url_value)
        pinView = findViewById(R.id.import_pin_value)
        curlView = findViewById(R.id.import_curl_hint)
        statusView = findViewById(R.id.import_status)
        findViewById<Button>(R.id.import_cancel).setOnClickListener { finish() }

        val lanIp = LanIp.firstIPv4()
        if (lanIp == null) {
            urlView.text = getString(R.string.vpn_import_no_lan)
            pinView.text = ""
            curlView.text = ""
            statusView.text = ""
            return
        }

        val sess = ConfigImportSession.generate().also { session = it }
        val srv = ConfigImportServer(applicationContext, sess, this).also { server = it }
        if (!srv.start()) {
            statusView.text = getString(R.string.vpn_import_status_error, "could not bind a LAN port")
            return
        }

        urlView.text = getString(R.string.vpn_import_url_value, lanIp, srv.port)
        pinView.text = getString(R.string.vpn_import_pin_value, sess.pin)
        curlView.text = getString(R.string.vpn_import_curl_hint, sess.pin, lanIp, srv.port)
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
            statusView.text = getString(R.string.vpn_import_status_received)
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
            statusView.text = getString(R.string.vpn_import_status_expired)
            mainHandler.removeCallbacks(ticker)
            server?.stop()
            return
        }
        val totalSec = remainingMillis / 1_000
        val mins = (totalSec / 60).toInt()
        val secs = (totalSec % 60).toInt()
        statusView.text = getString(R.string.vpn_import_status_waiting, mins, secs)
    }
}
