package com.ambient.tvclock

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ambient.tvclock.vpn.ConfigImportSession
import com.ambient.tvclock.vpn.LanIp

/**
 * Hosts [SetupServer] for as long as this screen is visible and shows the
 * URL + PIN to enter on a phone/laptop. Unlike the WireGuard import flow the
 * screen stays open after a save so the user can adjust several settings in
 * one session; it closes on Done or the session's TTL.
 */
class SetupActivity : AppCompatActivity(), SetupServer.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: ConfigImportSession? = null
    private var server: SetupServer? = null
    private var lastSaveSummary: String? = null
    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_import)

        findViewById<TextView>(R.id.import_title).text = getString(R.string.setup_title)
        findViewById<TextView>(R.id.import_pin_label).text = getString(R.string.setup_pin_label)
        val urlView = findViewById<TextView>(R.id.import_url_value)
        val pinView = findViewById<TextView>(R.id.import_pin_value)
        val curlView = findViewById<TextView>(R.id.import_curl_hint)
        statusView = findViewById(R.id.import_status)
        findViewById<Button>(R.id.import_cancel).apply {
            text = getString(R.string.setup_done)
            setOnClickListener { finish() }
        }

        val lanIp = LanIp.firstIPv4()
        if (lanIp == null) {
            urlView.text = getString(R.string.vpn_import_no_lan)
            pinView.text = ""
            curlView.text = ""
            statusView.text = ""
            return
        }

        val sess = ConfigImportSession.generate().also { session = it }
        val srv = SetupServer(applicationContext, sess, this).also { server = it }
        if (!srv.start()) {
            statusView.text = getString(R.string.vpn_import_status_error, "could not bind a LAN port")
            return
        }

        urlView.text = getString(R.string.vpn_import_url_value, lanIp, srv.port)
        pinView.text = getString(R.string.vpn_import_pin_value, sess.pin)
        curlView.text = ""
        updateCountdown()
        mainHandler.post(ticker)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        server?.stop()
        super.onDestroy()
    }

    override fun onSettingsSaved(labels: List<String>) {
        mainHandler.post {
            lastSaveSummary = getString(R.string.setup_status_saved, labels.joinToString(", "))
            updateCountdown()
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
            statusView.text = lastSaveSummary ?: getString(R.string.vpn_import_status_expired)
            mainHandler.removeCallbacks(ticker)
            server?.stop()
            return
        }
        val totalSec = remainingMillis / 1_000
        val mins = (totalSec / 60).toInt()
        val secs = (totalSec % 60).toInt()
        val waiting = getString(R.string.setup_status_waiting, mins, secs)
        statusView.text = lastSaveSummary?.let { "$it\n$waiting" } ?: waiting
    }
}
