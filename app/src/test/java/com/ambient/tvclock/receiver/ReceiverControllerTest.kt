package com.ambient.tvclock.receiver

import android.content.Context
import android.content.Intent
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * ReceiverControllerTest — Unit tests for [ReceiverController].
 *
 * WHY: [ReceiverController] is the single point through which the UI controls the
 * background service. Bugs here mean the user can't start/stop/restart the receiver.
 *
 * WHAT WE TEST: that each method dispatches the request via the correct Context
 * entry point (`startForegroundService` vs `startService`) for the current API level.
 *
 * NOTE on Intent inspection: AGP unit tests run against a stub `android.jar` where
 * `Intent` state (`action`, `component`) is not actually stored. We therefore verify
 * the dispatcher call rather than the Intent contents. Intent-content assertions
 * would require Robolectric, which this module does not pull in.
 */
class ReceiverControllerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.startForegroundService(any()) } returns null
        every { context.startService(any()) } returns mockk()
        // AGP unit-test stub jar reports Build.VERSION.SDK_INT = 0; simulate an API 34 device.
        ReceiverController.sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    @After
    fun teardown() {
        ReceiverController.sdkInt = Build.VERSION.SDK_INT
    }

    @Test
    fun `start() dispatches via startForegroundService on API 26+`() {
        ReceiverController.start(context)

        verify(exactly = 1) { context.startForegroundService(any<Intent>()) }
        verify(exactly = 0) { context.startService(any<Intent>()) }
    }

    @Test
    fun `stop() dispatches via startService`() {
        ReceiverController.stop(context)

        verify(exactly = 1) { context.startService(any<Intent>()) }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }

    @Test
    fun `restart() dispatches via startForegroundService on API 26+`() {
        ReceiverController.restart(context)

        verify(exactly = 1) { context.startForegroundService(any<Intent>()) }
        verify(exactly = 0) { context.startService(any<Intent>()) }
    }

    @Test
    fun `start() falls back to startService when sdkInt is below O`() {
        ReceiverController.sdkInt = Build.VERSION_CODES.N_MR1  // 25

        ReceiverController.start(context)

        verify(exactly = 1) { context.startService(any<Intent>()) }
        verify(exactly = 0) { context.startForegroundService(any<Intent>()) }
    }
}
