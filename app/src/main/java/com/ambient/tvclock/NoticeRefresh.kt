package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Fetches the board off the main thread and publishes the result on it.
 *
 * A fetch that fails keeps the notices already on screen: a dashboard on a
 * shelf should ride out a rebooting server rather than blank the wall. Expiry
 * still runs against that cached list, so a dead server can never leave a
 * notice up past its end time — it can only fail to bring new ones in.
 */
object NoticeRefresh {

    private val mainHandler = Handler(Looper.getMainLooper())

    // One persistent daemon thread services every refresh, matching
    // CalendarRefresh: poller ticks, page opens and settings saves coalesce
    // here instead of allocating a thread per call.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notice-fetch").apply { isDaemon = true }
    }

    fun publishAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val snapshot = try {
                fetch(app)
            } catch (e: Exception) {
                Timber.e(e, "notices: refresh crashed")
                NoticeCenter.current
            }
            mainHandler.post { NoticeCenter.update(snapshot) }
        }
    }

    private fun fetch(app: Context): NoticeSnapshot {
        // Turned off or never configured: clear the board without a request.
        if (!NoticePreferences.isPageAvailable(app)) return NoticeSnapshot.EMPTY

        val url = NoticePreferences.getUrl(app)
        val previous = NoticeCenter.current
        val sameSource = previous.sourceUrl == url
        val now = System.currentTimeMillis()

        return when (val result = NoticeClient(url).fetch(previous.etag.takeIf { sameSource })) {
            is NoticeClient.Result.Ok -> NoticeSnapshot(
                notices = result.value.notices,
                sourceUrl = url,
                etag = result.value.etag,
                fetchedAtMillis = now,
            )

            NoticeClient.Result.NotModified -> previous.copy(
                fetchedAtMillis = now,
                failure = null,
            )

            is NoticeClient.Result.Err -> NoticeSnapshot(
                // Only the same feed's notices are worth keeping; a new URL
                // starts empty rather than showing the old board.
                notices = if (sameSource) previous.notices else emptyList(),
                sourceUrl = url,
                etag = previous.etag.takeIf { sameSource },
                fetchedAtMillis = now,
                failure = result.failure,
            )
        }
    }
}
