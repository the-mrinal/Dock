package com.ambient.tvclock.receiver

/**
 * ReceiverState — Represents the lifecycle state of the ReceiverService.
 *
 * WHY: The UI needs to know if the service is running, stopped, or in an error
 * state to show the correct UI and enable/disable controls appropriately.
 *
 * HOW: Emitted by [ReceiverService] via a broadcast or LiveData.
 * Observed by [HomeFragment] to update service status cards.
 *
 * Example:
 *   when (state) {
 *       ReceiverState.RUNNING  -> showStatusRunning()
 *       ReceiverState.STOPPED  -> showStatusStopped()
 *       ReceiverState.ERROR    -> showStatusError(state.errorMessage)
 *   }
 */
sealed class ReceiverState {

    /** The service is running normally and all enabled protocols are advertising. */
    object Running : ReceiverState()

    /** The service has been stopped by the user. No protocols are active. */
    object Stopped : ReceiverState()

    /**
     * The service encountered an unrecoverable error.
     * @param message Human-readable error description (already localized if possible).
     */
    data class Error(val message: String) : ReceiverState()

    /** The service is currently restarting (brief transition between Stopped and Running). */
    object Restarting : ReceiverState()
}

/**
 * ProtocolState — Represents the state of a single receiver protocol (AirPlay / Miracast / Cast).
 *
 * WHY: Each protocol has its own independent lifecycle. This enum lets the UI
 * show a fine-grained status per protocol card without conflating them.
 *
 * HOW: Each receiver component (AirPlayReceiver, MiracastReceiver, CastReceiver)
 * emits [ProtocolState] changes that are aggregated by [ReceiverService].
 */
enum class ProtocolState {
    /** Protocol is disabled in Settings. */
    DISABLED,

    /** Protocol is enabled and advertising — waiting for a sender. */
    ADVERTISING,

    /** A sender is actively connected and streaming. */
    CONNECTED,

    /** The protocol encountered an error (e.g., port already in use). */
    ERROR
}

/**
 * ActiveConnection — Describes a currently active streaming connection.
 *
 * @param senderName   The display name of the sender (e.g., "Max's MacBook Pro").
 * @param protocol     Which protocol the connection uses.
 * @param startedAt    System clock millis when the connection was established.
 */
data class ActiveConnection(
    val senderName: String,
    val protocol: Protocol,
    val startedAt: Long = System.currentTimeMillis()
) {
    /** Returns the elapsed streaming time in seconds. */
    val durationSeconds: Long
        get() = (System.currentTimeMillis() - startedAt) / 1000L
}

/** Identifies one of the three supported protocols. */
enum class Protocol {
    AIRPLAY, MIRACAST, CAST
}

/**
 * VideoSize — Source video dimensions reported by the active decoder.
 *
 * Used to drive aspect-ratio-preserving layout in the mirroring overlay: a
 * portrait phone (e.g. 1170×2532) must render upright with pillarbox bars on
 * a horizontal TV, not be stretched to 16:9. The dimensions reflect the
 * cropped output (visible pixels), not the H.264 coded picture size.
 */
data class VideoSize(val width: Int, val height: Int)
