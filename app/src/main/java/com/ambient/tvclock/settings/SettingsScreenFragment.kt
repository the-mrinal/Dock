package com.ambient.tvclock.settings

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager

/**
 * Tiny base class that exposes [prefs] to all six setting fragments so they
 * read/write the same SharedPreferences the legacy `PreferenceFragmentCompat`
 * touched. No data migration — only the UI changes.
 */
abstract class SettingsScreenFragment(layoutId: Int) : Fragment(layoutId) {

    protected fun prefs() = PreferenceManager.getDefaultSharedPreferences(requireContext())

    protected fun appCtx(): Context = requireContext().applicationContext
}
