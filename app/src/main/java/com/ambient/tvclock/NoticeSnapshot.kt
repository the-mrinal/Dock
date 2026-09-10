package com.ambient.tvclock

/**
 * Everything known about the board after the last fetch attempt.
 *
 * [sourceUrl] is carried so a URL change invalidates both the ETag and the
 * cached notices: notices from the old feed must never survive a re-point.
 */
data class NoticeSnapshot(
    val notices: List<Notice>,
    val sourceUrl: String?,
    val etag: String?,
    val fetchedAtMillis: Long,
    val failure: NoticeClient.Failure? = null,
) {
    /** What belongs on screen right now, in feed order. */
    fun live(nowMillis: Long): List<Notice> = notices.filter { it.isLive(nowMillis) }

    companion object {
        val EMPTY = NoticeSnapshot(emptyList(), null, null, 0L)
    }
}
