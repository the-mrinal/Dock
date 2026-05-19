package com.ambient.tvclock.vpn

import android.content.Context
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny single-purpose HTTP server that lives only for the duration of the
 * [ConfigImportActivity]. Listens on the first free port in [PORT_CANDIDATES],
 * serves `GET /` (a paste-and-submit form for the browser case) and accepts
 * `POST /upload` with a PIN gate. On a successful upload, hands the raw
 * config to [WireGuardConfigStore], notifies [listener], and shuts down.
 *
 * HTTP only — no TLS. Private keys traverse the LAN unencrypted; documented
 * in the README as a trusted-network-only feature.
 */
class ConfigImportServer(
    private val context: Context,
    private val session: ConfigImportSession,
    private val listener: Listener,
) {

    interface Listener {
        fun onConfigAccepted()
        fun onServerError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2)
    private var serverSocket: ServerSocket? = null
    private var boundPort: Int = -1
    private var acceptThread: Thread? = null

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
        acceptThread = Thread({ acceptLoop(socket) }, "wg-import-accept").also { it.start() }
        Timber.i("ConfigImportServer: listening on 0.0.0.0:%d", boundPort)
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
                Timber.w(e, "ConfigImportServer: failed to bind :%d", candidate)
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
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
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
                    method == "POST" && path == "/upload" -> handleUpload(reader, headers, output)
                    else -> writeStatus(output, 404, "Not Found", "text/plain", "")
                }
            } catch (e: Exception) {
                Timber.w(e, "ConfigImportServer: error handling client")
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
            val k = line.substring(0, colon).trim().lowercase()
            val v = line.substring(colon + 1).trim()
            out[k] = v
        }
        return out
    }

    private fun handleUpload(
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
            writeStatus(output, 413, "Payload Too Large", "text/plain", "config exceeds ${MAX_BODY_BYTES / 1024} KB")
            return
        }
        val contentType = headers["content-type"].orEmpty().lowercase()
        // We read the full body from the BufferedReader (which has already seen the headers).
        val bodyBuf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(bodyBuf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val body = String(bodyBuf, 0, read)

        val (configText, pin) = parseBody(body, contentType, headers)

        when (session.verify(pin.orEmpty())) {
            ConfigImportSession.Verdict.EXPIRED -> {
                writeStatus(output, 410, "Gone", "text/plain", "import window expired")
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

        if (configText.isBlank()) {
            writeStatus(output, 400, "Bad Request", "text/plain", "empty config")
            return
        }

        try {
            WireGuardConfigStore(context).save(configText)
        } catch (e: Exception) {
            writeStatus(output, 400, "Bad Request", "text/plain", "config does not parse: ${e.message}")
            return
        }

        writeStatus(output, 200, "OK", "text/plain", "config saved")
        listener.onConfigAccepted()
    }

    private fun parseBody(
        body: String,
        contentType: String,
        headers: Map<String, String>,
    ): Pair<String, String?> {
        val headerPin = headers["x-wg-pin"]
        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            val fields = body.split('&').associate {
                val eq = it.indexOf('=')
                if (eq < 0) it to ""
                else java.net.URLDecoder.decode(it.substring(0, eq), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            }
            val formText = fields["config"].orEmpty()
            // `curl --data-binary @file` defaults to this content type but sends the raw bytes
            // without a `config=` prefix. Fall back to the body itself when the form field is empty.
            val text = if (formText.isNotBlank()) formText else body
            val pin = headerPin ?: fields["pin"]
            return text to pin
        }
        return body to headerPin
    }

    private fun serveForm(output: OutputStream) {
        writeStatus(output, 200, "OK", "text/html; charset=utf-8", HTML_FORM)
    }

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
        private val PORT_CANDIDATES = intArrayOf(8765, 8766, 8767)
        private const val MAX_BODY_BYTES = 16 * 1024

        private val HTML_FORM = """
            <!doctype html><meta charset="utf-8">
            <title>Send WireGuard config to dock</title>
            <style>
              body{font:14px/1.4 system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 16px;background:#111;color:#eee}
              h1{font-weight:600}
              textarea{width:100%;height:280px;font:13px/1.4 ui-monospace,monospace;background:#000;color:#0f0;border:1px solid #333;padding:8px}
              input[type=text]{font:14px ui-monospace,monospace;padding:6px 10px;background:#000;color:#eee;border:1px solid #333}
              button{padding:8px 18px;background:#0a7;color:#fff;border:0;font-weight:600;cursor:pointer}
            </style>
            <h1>Send your wg0.conf</h1>
            <form method="POST" action="/upload" enctype="application/x-www-form-urlencoded">
              <p><label>PIN (shown on TV): <input name="pin" type="text" autocomplete="off" required></label></p>
              <p><textarea name="config" required placeholder="[Interface]&#10;PrivateKey = ...&#10;Address = 10.7.0.2/32&#10;DNS = 1.1.1.1&#10;&#10;[Peer]&#10;PublicKey = ...&#10;AllowedIPs = 0.0.0.0/0&#10;Endpoint = 1.2.3.4:51820"></textarea></p>
              <p><button type="submit">Send to dock</button></p>
            </form>
        """.trimIndent()
    }
}
