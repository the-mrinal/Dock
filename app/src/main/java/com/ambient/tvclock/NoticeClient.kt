package com.ambient.tvclock

import okhttp3.Request
import timber.log.Timber

/**
 * Fetches the notice feed. Blocking — every call must run off the main thread;
 * [NoticeRefresh] owns that.
 *
 * Dependency-free beyond what the app already ships (OkHttp + org.json),
 * matching [com.ambient.tvclock.grainstorm.GrainstormClient], whose failure
 * vocabulary this borrows.
 */
class NoticeClient(private val url: String) {

    /** A failure the caller can act on; none of them reach the screen, since a
     *  board that can't refresh keeps showing what it last had. */
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
        /** The server confirmed nothing has changed since the sent ETag. */
        object NotModified : Result<Nothing>
        data class Err(val failure: Failure) : Result<Nothing>
    }

    data class Feed(val notices: List<Notice>, val etag: String?)

    /**
     * Pass the ETag from the last successful fetch of this same URL to get a
     * cheap [Result.NotModified] when the board hasn't changed, which is the
     * usual answer.
     */
    fun fetch(etag: String?): Result<Feed> {
        if (url.isBlank()) return Result.Err(Failure.NotConfigured)
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        return try {
            HttpClients.shared.newCall(builder.build()).execute().use { response ->
                if (response.code == 304) return Result.NotModified
                classify(response.code)?.let { return Result.Err(it) }
                val body = response.body?.string().orEmpty()
                val notices = NoticeContract.parse(body)
                    ?: return Result.Err(Failure.Malformed("notice feed"))
                Result.Ok(Feed(notices, response.header("ETag")))
            }
        } catch (e: Exception) {
            Timber.w(e, "notices: GET failed")
            Result.Err(Failure.Unreachable)
        }
    }

    /** Null means "this response is fine". */
    private fun classify(code: Int): Failure? = when {
        code in 200..299 -> null
        code == 401 || code == 403 -> Failure.Unauthorized
        code == 404 -> Failure.NotFound
        else -> Failure.Server(code)
    }
}
