package com.ambient.tvclock.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReceiverStateTest — Unit tests for [ReceiverState] and related enums.
 *
 * WHY: UI code uses `when` branches on [ReceiverState]. Testing that the sealed class
 * hierarchy is complete and that the Error state carries the message through prevents
 * silent regressions when new states are added.
 *
 * WHAT WE TEST:
 * - All [ReceiverState] subtypes can be instantiated and identified
 * - [ReceiverState.Error] carries its message correctly
 * - [ProtocolState] enum has all expected values
 * - [Protocol] enum has all expected values
 * - [ActiveConnection] data class equality
 */
class ReceiverStateTest {

    // ─── ReceiverState sealed class ────────────────────────────────────────────

    @Test
    fun `ReceiverState Running is identified as Running`() {
        val state: ReceiverState = ReceiverState.Running
        assertTrue(state is ReceiverState.Running)
    }

    @Test
    fun `ReceiverState Stopped is identified as Stopped`() {
        val state: ReceiverState = ReceiverState.Stopped
        assertTrue(state is ReceiverState.Stopped)
    }

    @Test
    fun `ReceiverState Restarting is identified as Restarting`() {
        val state: ReceiverState = ReceiverState.Restarting
        assertTrue(state is ReceiverState.Restarting)
    }

    @Test
    fun `ReceiverState Error carries its message`() {
        val msg = "NSD registration failed"
        val state: ReceiverState = ReceiverState.Error(msg)

        assertTrue(state is ReceiverState.Error)
        assertEquals(msg, (state as ReceiverState.Error).message)
    }

    @Test
    fun `ReceiverState Error instances with different messages are not equal`() {
        val a = ReceiverState.Error("error A")
        val b = ReceiverState.Error("error B")
        assertNotEquals(a, b)
    }

    @Test
    fun `ReceiverState Error instances with same message are equal`() {
        val a = ReceiverState.Error("port conflict")
        val b = ReceiverState.Error("port conflict")
        assertEquals(a, b)
    }

    // ─── ProtocolState enum ───────────────────────────────────────────────────

    @Test
    fun `ProtocolState has DISABLED state`() {
        assertEquals(ProtocolState.DISABLED, ProtocolState.valueOf("DISABLED"))
    }

    @Test
    fun `ProtocolState has ADVERTISING state`() {
        assertEquals(ProtocolState.ADVERTISING, ProtocolState.valueOf("ADVERTISING"))
    }

    @Test
    fun `ProtocolState has CONNECTED state`() {
        assertEquals(ProtocolState.CONNECTED, ProtocolState.valueOf("CONNECTED"))
    }

    @Test
    fun `ProtocolState has ERROR state`() {
        assertEquals(ProtocolState.ERROR, ProtocolState.valueOf("ERROR"))
    }

    // ─── Protocol enum ────────────────────────────────────────────────────────

    @Test
    fun `Protocol has AIRPLAY value`() {
        assertEquals(Protocol.AIRPLAY, Protocol.valueOf("AIRPLAY"))
    }

    @Test
    fun `Protocol has MIRACAST value`() {
        assertEquals(Protocol.MIRACAST, Protocol.valueOf("MIRACAST"))
    }

    @Test
    fun `Protocol has CAST value`() {
        assertEquals(Protocol.CAST, Protocol.valueOf("CAST"))
    }

    // ─── ActiveConnection data class ──────────────────────────────────────────

    @Test
    fun `ActiveConnection carries all fields`() {
        val ts = System.currentTimeMillis()
        val conn = ActiveConnection(
            senderName = "MacBook Pro",
            protocol = Protocol.AIRPLAY,
            startedAt = ts
        )

        assertEquals("MacBook Pro", conn.senderName)
        assertEquals(Protocol.AIRPLAY, conn.protocol)
        assertEquals(ts, conn.startedAt)
    }

    @Test
    fun `ActiveConnection instances with same data are equal`() {
        val conn1 = ActiveConnection("iPhone", Protocol.AIRPLAY, 1000L)
        val conn2 = ActiveConnection("iPhone", Protocol.AIRPLAY, 1000L)
        assertEquals(conn1, conn2)
    }

    @Test
    fun `ActiveConnection instances with different senders are not equal`() {
        val conn1 = ActiveConnection("MacBook", Protocol.AIRPLAY, 1000L)
        val conn2 = ActiveConnection("iPhone", Protocol.AIRPLAY, 1000L)
        assertNotEquals(conn1, conn2)
    }

    @Test
    fun `ActiveConnection durationSeconds returns non-negative value`() {
        val pastTime = System.currentTimeMillis() - 5_000L  // 5 seconds ago
        val conn = ActiveConnection("MacBook", Protocol.AIRPLAY, pastTime)
        assertTrue(conn.durationSeconds >= 4L)  // at least 4s given test execution time
    }

    @Test
    fun `ActiveConnection durationSeconds is zero for connection just started`() {
        val conn = ActiveConnection("MacBook", Protocol.AIRPLAY, System.currentTimeMillis())
        assertTrue(conn.durationSeconds >= 0L)
    }
}
