package com.ambient.tvclock.grainstorm

import com.ambient.tvclock.HttpClients
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Talks to a grainstorm library server. Blocking — every call must run off the
 * main thread; the repository above it owns that.
 *
 * Deliberately dependency-free beyond what the app already ships: OkHttp for
 * transport and `org.json` for parsing, matching every other client here. No
 * Retrofit, no serialization library.
 */
class GrainstormClient(
    private val baseUrl: String,
    private val token: String,
) {

    private val http = HttpClients.shared

    /** A failure the UI can explain to someone standing in front of the TV. */
    sealed interface Failure {
        object NotConfigured : Failure
        object Unreachable : Failure
        object Unauthorized : Failure
        object NotFound : Failure
        data class Server(val code: Int) : Failure
        data class Malformed(val what: String) : Failure
    }

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        /** The server confirmed nothing has changed since [etag]. */
        object NotModified : Result<Nothing>
        data class Err(val failure: Failure) : Result<Nothing>
    }

    fun ping(): Result<Boolean> = get("/healthz") { Result.Ok(true) }

    /** A page of the library, newest first. */
    fun listAssets(limit: Int = 24, cursor: String? = null): Result<SyncContract.AssetPage> {
        val query = buildString {
            append("/v1/assets?limit=").append(limit)
            if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor)
        }
        return get(query) { body ->
            SyncContract.parseAssetPage(body)
                ?.let { Result.Ok(it) }
                ?: Result.Err(Failure.Malformed("asset listing"))
        }
    }

    /**
     * What this screen should show. Pass the previously seen ETag to get a
     * cheap [Result.NotModified] when nothing has changed — which is the usual
     * answer, since the Mac's rotation only moves once a day.
     */
    fun current(deviceKey: String, etag: String?): Result<Pair<SyncContract.Current, String?>> =
        get("/v1/devices/$deviceKey/current", etag) { body, responseEtag ->
            SyncContract.parseCurrent(body)
                ?.let { Result.Ok(it to responseEtag) }
                ?: Result.Err(Failure.Malformed("current wallpaper"))
        }

    /** Enrol this screen. The server fills in the render tuning from w/h. */
    fun register(key: String, label: String, width: Int, height: Int): Result<Boolean> =
        send("POST", "/v1/devices/register", SyncContract.registerBody(key, label, width, height))

    /** Point this screen at a wallpaper from the library. */
    fun setCurrent(deviceKey: String, assetId: String): Result<Boolean> =
        send("PUT", "/v1/devices/$deviceKey/current", SyncContract.setCurrentBody(assetId))

    /**
     * Download an image to [target], verifying it against [expectedSha] before
     * the caller is told about it. A truncated download must never become the
     * wallpaper, so the bytes are written to a sibling temp file and renamed
     * only once the hash matches.
     */
    fun download(url: String, target: File, expectedSha: String?): Result<File> {
        val request = build(Request.Builder().url(SyncContract.absoluteUrl(baseUrl, url)))
        return try {
            http.newCall(request).execute().use { response ->
                classify(response.code)?.let { return Result.Err(it) }
                val bytes = response.body?.bytes() ?: return Result.Err(Failure.Malformed("empty image"))
                val actual = sha256(bytes)
                if (!expectedSha.isNullOrBlank() && actual != expectedSha) {
                    return Result.Err(Failure.Malformed("image hash mismatch"))
                }
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".part")
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return Result.Err(Failure.Malformed("could not store image"))
                }
                Result.Ok(target)
            }
        } catch (e: Exception) {
            Timber.w(e, "grainstorm: download failed")
            Result.Err(Failure.Unreachable)
        }
    }

    // ---- plumbing ----

    private fun <T> get(path: String, parse: (String) -> Result<T>): Result<T> =
        get(path, null) { body, _ -> parse(body) }

    private fun <T> get(
        path: String,
        etag: String?,
        parse: (String, String?) -> Result<T>,
    ): Result<T> {
        if (baseUrl.isBlank()) return Result.Err(Failure.NotConfigured)
        val builder = Request.Builder().url(baseUrl + path)
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        return try {
            http.newCall(build(builder)).execute().use { response ->
                if (response.code == 304) return Result.NotModified
                classify(response.code)?.let { return Result.Err(it) }
                val body = response.body?.string().orEmpty()
                parse(body, response.header("ETag"))
            }
        } catch (e: Exception) {
            Timber.w(e, "grainstorm: GET %s failed", path)
            Result.Err(Failure.Unreachable)
        }
    }

    private fun send(method: String, path: String, json: String): Result<Boolean> {
        if (baseUrl.isBlank()) return Result.Err(Failure.NotConfigured)
        val body = json.toRequestBody(JSON)
        val request = build(Request.Builder().url(baseUrl + path).method(method, body))
        return try {
            http.newCall(request).execute().use { response ->
                classify(response.code)?.let { return Result.Err(it) }
                Result.Ok(true)
            }
        } catch (e: Exception) {
            Timber.w(e, "grainstorm: %s %s failed", method, path)
            Result.Err(Failure.Unreachable)
        }
    }

    private fun build(builder: Request.Builder): Request {
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder.build()
    }

    /** Null means "this response is fine". */
    private fun classify(code: Int): Failure? = when {
        code in 200..299 -> null
        code == 401 || code == 403 -> Failure.Unauthorized
        code == 404 -> Failure.NotFound
        else -> Failure.Server(code)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = StringBuilder(digest.size * 2)
            for (b in digest) out.append("%02x".format(b))
            return out.toString()
        }
    }
}
