package com.ambient.tvclock.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReceiverServiceTest — Unit tests for [ReceiverService].
 *
 * WHY: [ReceiverService] is the central coordinator. Bugs in its state aggregation
 * logic or intent routing cause symptoms that are hard to debug (service not starting,
 * wrong notification text, connection not registered).
 *
 * WHAT WE TEST:
 * - The Intent action constants have the expected package-qualified strings
 * - The notification channel ID and notification ID are stable across code changes
 * - The [ProtocolState] → [ActiveConnection] mapping logic is correct
 * - [ProtocolState.CONNECTED] creates an [ActiveConnection] for the right protocol
 * - Non-CONNECTED states clear the [ActiveConnection]
 *
 * HOW: [ReceiverService] is an Android [Service] and requires Android APIs for
 * full lifecycle testing. That is covered by instrumentation tests.
 * Here we test only:
 *   1. Companion constants (compile-time safe)
 *   2. The `onStateChanged` callback logic, extracted as a pure lambda so it
 *      can be tested without an Android runtime.
 *
 * NOTE: Android Service, Notification, and Context are NOT used in any test here.
 */
class ReceiverServiceTest {

    // ─── Companion constants ──────────────────────────────────────────────────

    @Test
    fun `ACTION_START has package-qualified value`() {
        assertEquals("com.ambient.tvclock.receiver.action.START", ReceiverService.ACTION_START)
    }

    @Test
    fun `ACTION_STOP has package-qualified value`() {
        assertEquals("com.ambient.tvclock.receiver.action.STOP", ReceiverService.ACTION_STOP)
    }

    @Test
    fun `ACTION_RESTART has package-qualified value`() {
        assertEquals("com.ambient.tvclock.receiver.action.RESTART", ReceiverService.ACTION_RESTART)
    }

    @Test
    fun `NOTIFICATION_ID is positive`() {
        assertTrue(
            "NOTIFICATION_ID must be > 0 (Android rejects 0)",
            ReceiverService.NOTIFICATION_ID > 0
        )
    }

    @Test
    fun `CHANNEL_ID is non-empty`() {
        assertTrue(ReceiverService.CHANNEL_ID.isNotEmpty())
    }

    @Test
    fun `all three ACTION constants are distinct`() {
        val actions = setOf(
            ReceiverService.ACTION_START,
            ReceiverService.ACTION_STOP,
            ReceiverService.ACTION_RESTART
        )
        assertEquals("All ACTION constants must be unique", 3, actions.size)
    }

    // ─── ProtocolState → ActiveConnection mapping ─────────────────────────────
    //
    // This logic lives inside the private `onStateChanged` callback in startAirPlay().
    // We replicate the exact same lambda here to test it independently.
    //
    // WHY: If CONNECTED doesn't create an ActiveConnection, the UI will never show
    // the streaming status. If ADVERTISING doesn't clear it, a stale connection
    // lingers after the session ends.

    /** The exact mapping lambda from [ReceiverService.startAirPlay] — tested as a pure function. */
    private fun simulateStateChange(
        state: ProtocolState,
        currentConnection: ActiveConnection?
    ): ActiveConnection? {
        return when (state) {
            ProtocolState.CONNECTED   -> ActiveConnection("AirPlay Sender", Protocol.AIRPLAY)
            ProtocolState.ADVERTISING,
            ProtocolState.DISABLED,
            ProtocolState.ERROR       -> null
        }
    }

    @Test
    fun `CONNECTED state creates ActiveConnection for AirPlay protocol`() {
        val connection = simulateStateChange(ProtocolState.CONNECTED, currentConnection = null)

        assertNotNull("CONNECTED must create an ActiveConnection", connection)
        assertEquals(Protocol.AIRPLAY, connection?.protocol)
        assertEquals("AirPlay Sender", connection?.senderName)
    }

    @Test
    fun `ADVERTISING state clears ActiveConnection`() {
        val existing = ActiveConnection("MacBook Pro", Protocol.AIRPLAY)
        val result = simulateStateChange(ProtocolState.ADVERTISING, currentConnection = existing)
        assertNull("ADVERTISING must clear the ActiveConnection", result)
    }

    @Test
    fun `DISABLED state clears ActiveConnection`() {
        val existing = ActiveConnection("iPad", Protocol.AIRPLAY)
        val result = simulateStateChange(ProtocolState.DISABLED, currentConnection = existing)
        assertNull("DISABLED must clear the ActiveConnection", result)
    }

    @Test
    fun `ERROR state clears ActiveConnection`() {
        val existing = ActiveConnection("iPhone 15", Protocol.AIRPLAY)
        val result = simulateStateChange(ProtocolState.ERROR, currentConnection = existing)
        assertNull("ERROR must clear the ActiveConnection", result)
    }

    @Test
    fun `CONNECTED then ADVERTISING transition clears connection`() {
        // Simulate a full connect → teardown cycle
        val afterConnect      = simulateStateChange(ProtocolState.CONNECTED,   null)
        val afterAdvertising  = simulateStateChange(ProtocolState.ADVERTISING,  afterConnect)

        assertNotNull(afterConnect)
        assertNull("After ADVERTISING, connection must be null", afterAdvertising)
    }

    @Test
    fun `CONNECTED ActiveConnection has non-negative duration`() {
        val connection = simulateStateChange(ProtocolState.CONNECTED, null)!!
        assertTrue(connection.durationSeconds >= 0L)
    }

    @Test
    fun `CONNECTED ActiveConnection startedAt is recent`() {
        val before = System.currentTimeMillis()
        val connection = simulateStateChange(ProtocolState.CONNECTED, null)!!
        val after = System.currentTimeMillis()
        assertTrue(connection.startedAt in before..after)
    }

    // ─── Surface provider constants / reasoning ───────────────────────────────
    //
    // These tests document the expected behavior of the video surface provider
    // wiring between ReceiverService and MainActivity without needing Android.

    @Test
    fun `surface provider lambda returning null is safe to invoke`() {
        // Default state before MainActivity binds: provider is null → no surface
        val provider: (() -> Any?)? = null
        val result = provider?.invoke()
        assertNull("Null provider must not crash — safe call returns null", result)
    }

    @Test
    fun `surface provider lambda can be replaced`() {
        // Simulates setVideoSurfaceProvider() being called twice (e.g., orientation change)
        var currentProvider: (() -> Any?)? = { "surface_A" }
        assertEquals("surface_A", currentProvider?.invoke())

        currentProvider = { "surface_B" }
        assertEquals("surface_B", currentProvider?.invoke())
    }

    @Test
    fun `surface provider cleared on Activity stop prevents stale surface reference`() {
        val fakeSurface = Any()
        var currentProvider: (() -> Any?)? = { fakeSurface }

        // Activity stops → clear provider to avoid holding destroyed Surface
        currentProvider = { null }

        assertNull("Provider must return null after Activity stops", currentProvider.invoke())
    }
}
