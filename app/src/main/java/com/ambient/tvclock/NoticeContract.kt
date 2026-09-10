package com.ambient.tvclock

import org.json.JSONObject
import timber.log.Timber
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * One notice pinned to the board: a chunk of HTML and the window it is live
 * for. Dock never trusts the server to have filtered by time — the TV's own
 * clock decides, re-checked every minute — so a feed may carry notices that
 * are long expired or days away and the board stays correct.
 */
data class Notice(
    val id: String,
    val title: String?,
    val html: String,
    /** Null means "live from the moment it appears in the feed". */
    val startsAtMillis: Long?,
    val endsAtMillis: Long,
) {
    /** Start is inclusive, end is exclusive: at [endsAtMillis] it is gone. */
    fun isLive(nowMillis: Long): Boolean =
        (startsAtMillis == null || nowMillis >= startsAtMillis) && nowMillis < endsAtMillis
}

/**
 * The wire contract for the notice feed — parsing only, no I/O, so it is
 * testable on the JVM without a device or a network.
 *
 * Two rules, matching [com.ambient.tvclock.grainstorm.SyncContract]: unknown
 * fields are ignored, and an unknown major version is refused rather than
 * half-understood. Beyond that the parser is deliberately forgiving *per
 * notice* — one malformed entry is dropped and its siblings still reach the
 * screen, because a typo in one notice must not blank the whole board.
 */
object NoticeContract {

    const val VERSION = 1

    /** Null means the payload is unusable; the caller keeps what it had. */
    fun parse(body: String?): List<Notice>? = guarded("notice feed") {
        val root = JSONObject(body ?: return@guarded null)
        // `version` is optional; when present it must be one we understand.
        val version = root.optInt("version", VERSION)
        if (version != VERSION) {
            Timber.w("notices: refusing feed version %d", version)
            return@guarded null
        }
        val array = root.optJSONArray("notices") ?: return@guarded null
        val notices = ArrayList<Notice>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseNotice(obj, i)?.let(notices::add)
        }
        notices
    }

    private fun parseNotice(obj: JSONObject, index: Int): Notice? {
        val id = optText(obj, "id") ?: "notice-$index"
        val html = optText(obj, "html")
        if (html == null) {
            Timber.w("notices: %s has no html", id)
            return null
        }
        val endsRaw = optText(obj, "ends_at")
        if (endsRaw == null) {
            Timber.w("notices: %s has no ends_at", id)
            return null
        }
        val endsAt = parseTimestamp(endsRaw)
        if (endsAt == null) {
            Timber.w("notices: %s has an unreadable ends_at (%s)", id, endsRaw)
            return null
        }
        // A missing start means "already live". A *present but broken* start is
        // an error: showing it immediately could put a notice on the wall days
        // early, so drop it and let the feed be fixed.
        val startsRaw = optText(obj, "starts_at")
        val startsAt = if (startsRaw == null) null else parseTimestamp(startsRaw)
        if (startsRaw != null && startsAt == null) {
            Timber.w("notices: %s has an unreadable starts_at (%s)", id, startsRaw)
            return null
        }
        return Notice(
            id = id,
            title = optText(obj, "title"),
            html = html,
            startsAtMillis = startsAt,
            endsAtMillis = endsAt,
        )
    }

    /** ISO-8601 with an offset. Local time without a zone is refused — the
     *  feed and the TV may sit in different ones. */
    fun parseTimestamp(raw: String): Long? = try {
        OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * org.json hands back the literal string "null" for a JSON null, and "" for
     * a missing key, so every optional string goes through here.
     */
    private fun optText(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val value = obj.optString(key)
        return value.trim().ifBlank { null }
    }

    private inline fun <T> guarded(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        Timber.w(e, "notices: could not parse %s", what)
        null
    }
}
