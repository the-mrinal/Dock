package com.ambient.tvclock.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import com.ambient.tvclock.R
import com.ambient.tvclock.ReceiverPreferences
import com.ambient.tvclock.receiver.ReceiverController

/**
 * Mirroring receiver page. Same keys as before:
 * - receiver_enabled (master)
 * - receiver_device_name
 * - receiver_airplay_enabled / cast / miracast
 * - receiver_airplay_pin
 * - receiver_start_on_boot
 *
 * Flipping any of the protocol toggles while the receiver is enabled restarts
 * the controller so mDNS records get refreshed — mirrors the legacy behaviour.
 */
class PhoneMirroringSettingsFragment :
    SettingsScreenFragment(R.layout.fragment_settings_phone_mirroring) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // Master toggle.
        val enableRow = view.findViewById<PrefRow>(R.id.prefMirrorEnable)
        enableRow.label = getString(R.string.settings_mirror_enable)
        enableRow.setToggle(ReceiverPreferences.isReceiverEnabled(ctx)) { newValue ->
            prefs().edit().putBoolean(ReceiverPreferences.KEY_RECEIVER_ENABLED, newValue).apply()
            if (newValue) ReceiverController.start(ctx) else ReceiverController.stop(ctx)
        }

        // Device name row — opens a text dialog.
        val nameRow = view.findViewById<PrefRow>(R.id.prefMirrorDeviceName)
        nameRow.label = getString(R.string.settings_mirror_device_name)
        nameRow.setValue(currentDeviceName())
        nameRow.setOnClickListener {
            promptForString(
                title = getString(R.string.settings_mirror_device_name),
                current = prefs().getString(ReceiverPreferences.KEY_DEVICE_NAME, "").orEmpty(),
            ) { newValue ->
                prefs().edit().putString(ReceiverPreferences.KEY_DEVICE_NAME, newValue).apply()
                nameRow.setValue(currentDeviceName())
                if (ReceiverPreferences.isReceiverEnabled(ctx)) {
                    ReceiverController.restart(ctx)
                }
            }
        }

        bindProtocolToggle(
            view.findViewById(R.id.prefMirrorAirplay),
            getString(R.string.settings_mirror_airplay),
            ReceiverPreferences.KEY_AIRPLAY_ENABLED,
            defaultValue = true,
        )
        bindProtocolToggle(
            view.findViewById(R.id.prefMirrorCast),
            getString(R.string.settings_mirror_cast),
            ReceiverPreferences.KEY_CAST_ENABLED,
            defaultValue = true,
        )
        bindProtocolToggle(
            view.findViewById(R.id.prefMirrorMiracast),
            getString(R.string.settings_mirror_miracast),
            ReceiverPreferences.KEY_MIRACAST_ENABLED,
            defaultValue = true,
        )
        bindProtocolToggle(
            view.findViewById(R.id.prefMirrorPin),
            getString(R.string.settings_mirror_pin),
            ReceiverPreferences.KEY_AIRPLAY_PIN,
            defaultValue = false,
        )
        bindProtocolToggle(
            view.findViewById(R.id.prefMirrorBoot),
            getString(R.string.settings_mirror_boot),
            ReceiverPreferences.KEY_START_ON_BOOT,
            defaultValue = false,
            restartOnChange = false, // boot prefs do not need a live restart
        )
    }

    private fun bindProtocolToggle(
        row: PrefRow,
        label: String,
        key: String,
        defaultValue: Boolean,
        restartOnChange: Boolean = true,
    ) {
        val ctx = requireContext()
        row.label = label
        row.setToggle(prefs().getBoolean(key, defaultValue)) { newValue ->
            prefs().edit().putBoolean(key, newValue).apply()
            if (restartOnChange && ReceiverPreferences.isReceiverEnabled(ctx)) {
                ReceiverController.restart(ctx)
            }
        }
    }

    private fun currentDeviceName(): String {
        val raw = prefs().getString(ReceiverPreferences.KEY_DEVICE_NAME, "").orEmpty()
        return if (raw.isBlank()) getString(R.string.settings_mirror_device_name_default) else raw
    }

    private fun promptForString(title: String, current: String, onSaved: (String) -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setSelection(text.length)
        }
        val container = FrameLayout(ctx).apply {
            val density = resources.displayMetrics.density
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSaved(input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
