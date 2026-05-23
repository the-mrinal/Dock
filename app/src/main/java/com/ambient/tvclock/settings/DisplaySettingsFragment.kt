package com.ambient.tvclock.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceManager
import com.ambient.tvclock.AmbientPreferences
import com.ambient.tvclock.R
import com.ambient.tvclock.TimeoutPreferences

/**
 * Display behavior page. Two list-style prefs (ambient delay, inactivity
 * timeout) — replaces the legacy ListPreference dialogs with simple
 * AlertDialog single-choice pickers backed by the same arrays.xml entries.
 */
class DisplaySettingsFragment :
    SettingsScreenFragment(R.layout.fragment_settings_display) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ambientRow = view.findViewById<PrefRow>(R.id.prefDisplayAmbient)
        ambientRow.label = getString(R.string.settings_display_ambient)
        refreshAmbient(ambientRow)
        ambientRow.setOnClickListener {
            showListPicker(
                title = getString(R.string.settings_display_ambient),
                labels = resources.getStringArray(R.array.ambient_labels),
                values = resources.getStringArray(R.array.ambient_values),
                current = currentAmbient(),
            ) { newValue ->
                prefs().edit().putString(AmbientPreferences.KEY_AMBIENT_DELAY_MS, newValue).apply()
                refreshAmbient(ambientRow)
            }
        }

        val timeoutRow = view.findViewById<PrefRow>(R.id.prefDisplayTimeout)
        timeoutRow.label = getString(R.string.settings_display_timeout)
        refreshTimeout(timeoutRow)
        timeoutRow.setOnClickListener {
            showListPicker(
                title = getString(R.string.settings_display_timeout),
                labels = resources.getStringArray(R.array.timeout_labels),
                values = resources.getStringArray(R.array.timeout_values),
                current = currentTimeout(),
            ) { newValue ->
                prefs().edit().putString(TimeoutPreferences.KEY_INACTIVITY_TIMEOUT_MS, newValue).apply()
                refreshTimeout(timeoutRow)
            }
        }
    }

    private fun refreshAmbient(row: PrefRow) {
        val current = currentAmbient()
        row.setValue(labelForValue(R.array.ambient_labels, R.array.ambient_values, current))
    }

    private fun refreshTimeout(row: PrefRow) {
        val current = currentTimeout()
        row.setValue(labelForValue(R.array.timeout_labels, R.array.timeout_values, current))
    }

    private fun currentAmbient(): String =
        prefs().getString(AmbientPreferences.KEY_AMBIENT_DELAY_MS, getString(R.string.ambient_default_value))!!

    private fun currentTimeout(): String =
        prefs().getString(TimeoutPreferences.KEY_INACTIVITY_TIMEOUT_MS, getString(R.string.timeout_default_value))!!

    private fun labelForValue(labelsResId: Int, valuesResId: Int, value: String): String {
        val values = resources.getStringArray(valuesResId)
        val labels = resources.getStringArray(labelsResId)
        val idx = values.indexOf(value).coerceAtLeast(0)
        return labels[idx]
    }

    private fun showListPicker(
        title: String,
        labels: Array<String>,
        values: Array<String>,
        current: String,
        onPicked: (String) -> Unit,
    ) {
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onPicked(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
