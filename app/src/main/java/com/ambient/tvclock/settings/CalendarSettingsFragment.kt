package com.ambient.tvclock.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.ambient.tvclock.CalendarPoller
import com.ambient.tvclock.CalendarPreferences
import com.ambient.tvclock.R

/**
 * Calendar feeds page. Same SharedPreferences keys as the legacy
 * `preferences.xml` — only the UI changes. Refresh interval is fixed at
 * `CalendarPreferences.POLL_MS` (5 min); when a multi-value setting lands
 * we'll wire a chooser.
 */
class CalendarSettingsFragment :
    SettingsScreenFragment(R.layout.fragment_settings_calendar) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        // Personal iCal URL row.
        val personalRow = view.findViewById<PrefRow>(R.id.prefCalendarPersonal)
        personalRow.label = getString(R.string.settings_calendar_personal_label)
        personalRow.setValue(formatUrl(CalendarPreferences.getPersonalUrl(ctx)))
        personalRow.setOnClickListener {
            promptForUrl(
                title = getString(R.string.settings_calendar_personal_label),
                current = CalendarPreferences.getPersonalUrl(ctx),
            ) { newValue ->
                prefs().edit().putString(CalendarPreferences.KEY_PERSONAL_URL, newValue).apply()
                personalRow.setValue(formatUrl(newValue))
                CalendarPoller(ctx).publishNow()
            }
        }

        // Work iCal URL row.
        val workRow = view.findViewById<PrefRow>(R.id.prefCalendarWork)
        workRow.label = getString(R.string.settings_calendar_work_label)
        workRow.setValue(formatUrl(CalendarPreferences.getWorkUrl(ctx)))
        workRow.setOnClickListener {
            promptForUrl(
                title = getString(R.string.settings_calendar_work_label),
                current = CalendarPreferences.getWorkUrl(ctx),
            ) { newValue ->
                prefs().edit().putString(CalendarPreferences.KEY_WORK_URL, newValue).apply()
                workRow.setValue(formatUrl(newValue))
                CalendarPoller(ctx).publishNow()
            }
        }

        // Refresh interval — read-only display today.
        val refreshRow = view.findViewById<PrefRow>(R.id.prefCalendarRefresh)
        refreshRow.label = getString(R.string.settings_calendar_refresh_label)
        refreshRow.setValue(getString(R.string.settings_calendar_refresh_value))
        refreshRow.isFocusable = false
        refreshRow.setOnClickListener(null)

        // "Show declined / show calendar" toggle.
        val showRow = view.findViewById<PrefRow>(R.id.prefCalendarShow)
        showRow.label = getString(R.string.settings_calendar_show_label)
        showRow.setToggle(CalendarPreferences.isEnabled(ctx)) { newValue ->
            prefs().edit().putBoolean(CalendarPreferences.KEY_SHOW_CALENDAR, newValue).apply()
            CalendarPoller(ctx).publishNow()
        }

        // "Tomorrow preview on Home" — cosmetic placeholder pref.
        val tomorrowRow = view.findViewById<PrefRow>(R.id.prefCalendarTomorrow)
        tomorrowRow.label = getString(R.string.settings_calendar_tomorrow_label)
        val tomorrowOn = prefs().getBoolean(KEY_TOMORROW_PREVIEW, true)
        tomorrowRow.setToggle(tomorrowOn) { newValue ->
            prefs().edit().putBoolean(KEY_TOMORROW_PREVIEW, newValue).apply()
        }

        // Danger row — reset.
        val resetRow = view.findViewById<PrefDangerRow>(R.id.prefCalendarReset)
        resetRow.label = getString(R.string.settings_calendar_reset_label)
        resetRow.setHint(getString(R.string.settings_calendar_reset_hint))
        resetRow.setValue("↵") // ↵
        resetRow.setOnClickListener {
            prefs().edit()
                .remove(CalendarPreferences.KEY_PERSONAL_URL)
                .remove(CalendarPreferences.KEY_WORK_URL)
                .apply()
            personalRow.setValue(formatUrl(""))
            workRow.setValue(formatUrl(""))
            CalendarPoller(ctx).publishNow()
            Toast.makeText(ctx, R.string.settings_calendar_reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatUrl(url: String): String =
        if (url.isBlank()) getString(R.string.settings_calendar_value_unset) else url

    private fun promptForUrl(title: String, current: String, onSaved: (String) -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
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

    private companion object {
        const val KEY_TOMORROW_PREVIEW = "calendar_tomorrow_preview"
    }
}
