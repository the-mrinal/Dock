package com.ambient.tvclock.vpn

import android.content.Context
import androidx.preference.PreferenceManager

object VpnPreferences {
    const val KEY_VPN_ENABLED = "vpn_enabled"
    const val KEY_VPN_CONFIG_PRESENT = "vpn_config_present"

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_VPN_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_VPN_ENABLED, enabled)
            .apply()
    }

    fun hasConfig(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_VPN_CONFIG_PRESENT, false)

    fun setConfigPresent(context: Context, present: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_VPN_CONFIG_PRESENT, present)
            .apply()
    }
}
