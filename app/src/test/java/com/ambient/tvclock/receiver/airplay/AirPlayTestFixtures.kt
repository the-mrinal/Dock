package com.ambient.tvclock.receiver.airplay

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

object AirPlayTestFixtures {

    fun stubControlHandler(): AirPlayControlHandler {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } returns null
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } just runs
        return AirPlayControlHandler(
            context = context,
            pairing = AirPlayPairing(context),
            deviceName = { "PhairPlay" },
            onSetupComplete = {}
        )
    }
}
