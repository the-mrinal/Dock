package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager
import com.ambient.tvclock.vpn.ConfigImportSession
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN HTTP server that lives only while [SetupActivity] is on screen. Lets the
 * user edit the app's URL settings from a phone/laptop browser instead of the
 * TV remote: `GET /` serves a form pre-filled with the current values,
 * `POST /save` (PIN-gated, same one-shot session as the WireGuard import)
 * writes them to the default SharedPreferences.
 *
 * Only the keys in [FIELDS] can be written, and only the keys present in the
 * request body are touched — a curl client can update a single field without
 * clobbering the rest.
 *
 * HTTP only — no TLS. Trusted-home-network feature, same caveat as
 * [com.ambient.tvclock.vpn.ConfigImportServer].
 */
class SetupServer(
    private val context: Context,
    private val session: ConfigImportSession,
    private val listener: Listener,
) {

    interface Listener {
        fun onSettingsSaved(labels: List<String>)
        fun onServerError(message: String)
    }

    /** A settable field: preference key + human label shown in the form. */
    private data class Field(val key: String, val label: String)

    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2)
    private var serverSocket: ServerSocket? = null
    private var boundPort: Int = -1

    val port: Int get() = boundPort

    fun start(): Boolean {
        if (running.get()) return true
        val socket = bindFirstFreePort() ?: run {
            listener.onServerError("Could not bind to any LAN port")
            return false
        }
        serverSocket = socket
        boundPort = socket.localPort
        running.set(true)
        Thread({ acceptLoop(socket) }, "setup-accept").start()
        Timber.i("SetupServer: listening on 0.0.0.0:%d", boundPort)
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: IOException) { }
        executor.shutdownNow()
    }

    private fun bindFirstFreePort(): ServerSocket? {
        for (candidate in PORT_CANDIDATES) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", candidate))
                return s
            } catch (_: BindException) {
                continue
            } catch (e: IOException) {
                Timber.w(e, "SetupServer: failed to bind :%d", candidate)
            }
        }
        return null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 5_000
                val output = sock.getOutputStream()
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeStatus(output, 400, "Bad Request", "text/plain", "malformed request line")
                    return
                }
                val method = parts[0]
                val path = parts[1]
                val headers = readHeaders(reader)

                when {
                    method == "GET" && path == "/" -> serveForm(output)
                    method == "POST" && path == "/save" -> handleSave(reader, headers, output)
                    else -> writeStatus(output, 404, "Not Found", "text/plain", "")
                }
            } catch (e: Exception) {
                Timber.w(e, "SetupServer: error handling client")
            }
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val out = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            out[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return out
    }

    private fun handleSave(
        reader: BufferedReader,
        headers: Map<String, String>,
        output: OutputStream,
    ) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: -1
        if (contentLength <= 0) {
            writeStatus(output, 411, "Length Required", "text/plain", "Content-Length required")
            return
        }
        if (contentLength > MAX_BODY_BYTES) {
            writeStatus(output, 413, "Payload Too Large", "text/plain", "body exceeds ${MAX_BODY_BYTES / 1024} KB")
            return
        }
        val bodyBuf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(bodyBuf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val fields = parseForm(String(bodyBuf, 0, read))

        val pin = headers["x-setup-pin"] ?: fields["pin"].orEmpty()
        when (session.verify(pin)) {
            ConfigImportSession.Verdict.EXPIRED -> {
                writeStatus(output, 410, "Gone", "text/plain", "setup window expired")
                return
            }
            ConfigImportSession.Verdict.LOCKED_OUT -> {
                writeStatus(output, 429, "Too Many Requests", "text/plain", "rate limited; try again in a minute")
                return
            }
            ConfigImportSession.Verdict.BAD_PIN -> {
                writeStatus(output, 401, "Unauthorized", "text/plain", "bad PIN")
                return
            }
            ConfigImportSession.Verdict.OK -> { /* fall through */ }
        }

        val touched = FIELDS.filter { it.key in fields }
        if (touched.isEmpty()) {
            writeStatus(output, 400, "Bad Request", "text/plain", "no known fields in body")
            return
        }

        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        for (field in touched) {
            editor.putString(field.key, fields.getValue(field.key).trim())
        }
        editor.apply()

        if (touched.any { it.key == CalendarPreferences.KEY_PERSONAL_URL || it.key == CalendarPreferences.KEY_WORK_URL }) {
            CalendarRefresh.publishAsync(context)
        }

        val labels = touched.map { it.label }
        writeStatus(output, 200, "OK", "text/plain", "saved: ${labels.joinToString(", ")}")
        listener.onSettingsSaved(labels)
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.isNotBlank() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) URLDecoder.decode(it, "UTF-8") to ""
            else URLDecoder.decode(it.substring(0, eq), "UTF-8") to
                URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }

    private fun serveForm(output: OutputStream) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val inputs = FIELDS.joinToString("\n") { field ->
            val value = htmlEscape(prefs.getString(field.key, "").orEmpty())
            """<p><label>${field.label}<br><input name="${field.key}" type="url" value="$value" placeholder="https://…"></label></p>"""
        }
        val html = """
            <!doctype html><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Dock setup</title>
            <style>
              body{font:15px/1.5 system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 16px;background:#111;color:#eee}
              h1{font-weight:600}
              input{width:100%;box-sizing:border-box;font:14px ui-monospace,monospace;padding:8px 10px;background:#000;color:#eee;border:1px solid #333}
              label{color:#bbb}
              button{padding:10px 22px;background:#0a7;color:#fff;border:0;font-weight:600;cursor:pointer;font-size:15px}
            </style>
            <h1>Dock setup</h1>
            <form method="POST" action="/save" enctype="application/x-www-form-urlencoded">
              <p><label>PIN (shown on TV)<br><input name="pin" type="text" inputmode="numeric" autocomplete="off" required></label></p>
              $inputs
              <p><button type="submit">Save to TV</button></p>
            </form>
        """.trimIndent()
        writeStatus(output, 200, "OK", "text/html; charset=utf-8", html)
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun writeStatus(out: OutputStream, code: Int, reason: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = PrintWriter(out)
        writer.print("HTTP/1.1 $code $reason\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
    }

    companion object {
        // Distinct from ConfigImportServer's 8765-8767 so both can coexist.
        private val PORT_CANDIDATES = intArrayOf(8768, 8769, 8770)
        private const val MAX_BODY_BYTES = 16 * 1024

        private val FIELDS = listOf(
            Field(CalendarPreferences.KEY_PERSONAL_URL, "Personal calendar (iCal URL)"),
            Field(CalendarPreferences.KEY_WORK_URL, "Work calendar (iCal URL)"),
            Field(HomeLabPreferences.KEY_HOMELAB_URL, "Home Lab dashboard URL"),
            Field(AdBlockPreferences.KEY_DASHBOARD_URL, "Ad-block dashboard URL"),
        )
    }
}
