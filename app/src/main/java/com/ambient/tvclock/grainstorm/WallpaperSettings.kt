package com.ambient.tvclock.grainstorm

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.ambient.tvclock.R
import java.util.concurrent.Executors

/**
 * The Wallpaper section of Settings: point the dock at a library, let the
 * screen enrol itself, and open the picker.
 *
 * Lives beside the feature rather than inside `SettingsActivity`, which is
 * already long and has no business knowing how a screen registers itself.
 */
object WallpaperSettings {

    private const val KEY_BROWSE = "wallpaper_browse"
    private const val KEY_PANEL = "wallpaper_panel_size"
    private const val KEY_REGISTER = "wallpaper_register"
    private const val KEY_TEST = "wallpaper_test_connection"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wallpaper-settings").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun wire(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()

        fragment.findPreference<Preference>(KEY_PANEL)?.summary = panelSummary(context)

        fragment.findPreference<Preference>(KEY_BROWSE)?.setOnPreferenceClickListener {
            if (requireConfigured(context)) {
                fragment.startActivity(Intent(context, WallpaperPickerActivity::class.java))
            }
            true
        }

        fragment.findPreference<Preference>(KEY_TEST)?.let { pref ->
            pref.setOnPreferenceClickListener {
                if (!requireConfigured(context)) return@setOnPreferenceClickListener true
                pref.summary = context.getString(R.string.wallpaper_testing)
                val repository = WallpaperRepository(context)
                io.execute {
                    val result = repository.ping()
                    main.post {
                        pref.summary = when (result) {
                            is GrainstormClient.Result.Ok ->
                                context.getString(R.string.wallpaper_test_ok)
                            is GrainstormClient.Result.Err -> describe(context, result.failure)
                            is GrainstormClient.Result.NotModified ->
                                context.getString(R.string.wallpaper_test_ok)
                        }
                    }
                }
                true
            }
        }

        fragment.findPreference<Preference>(KEY_REGISTER)?.let { pref ->
            pref.setOnPreferenceClickListener {
                if (!requireConfigured(context)) return@setOnPreferenceClickListener true
                val panel = DisplayMetricsProvider.panelSize(context)
                if (!panel.isUsable) {
                    toast(context, context.getString(R.string.wallpaper_panel_unknown))
                    return@setOnPreferenceClickListener true
                }
                pref.summary = context.getString(R.string.wallpaper_registering)
                val repository = WallpaperRepository(context)
                val label = android.os.Build.MODEL.ifBlank { repository.deviceKey() }
                io.execute {
                    val result = repository.register(label, panel.width, panel.height)
                    main.post {
                        pref.summary = when (result) {
                            is GrainstormClient.Result.Ok -> context.getString(
                                R.string.wallpaper_registered, repository.deviceKey(), panel.describe()
                            )
                            is GrainstormClient.Result.Err -> describe(context, result.failure)
                            is GrainstormClient.Result.NotModified ->
                                context.getString(R.string.wallpaper_test_ok)
                        }
                    }
                }
                true
            }
        }
    }

    fun panelSummary(context: Context): String {
        val panel = DisplayMetricsProvider.panelSize(context)
        return if (panel.isUsable) panel.describe() else context.getString(R.string.wallpaper_panel_unknown)
    }

    /** Turn a client failure into something readable from across a room. */
    fun describe(context: Context, failure: GrainstormClient.Failure): String = when (failure) {
        GrainstormClient.Failure.NotConfigured -> context.getString(R.string.wallpaper_not_configured)
        GrainstormClient.Failure.Unreachable -> context.getString(R.string.wallpaper_error_unreachable)
        GrainstormClient.Failure.Unauthorized -> context.getString(R.string.wallpaper_error_unauthorized)
        GrainstormClient.Failure.NotFound -> context.getString(R.string.wallpaper_error_not_found)
        is GrainstormClient.Failure.Server -> context.getString(R.string.wallpaper_error_server, failure.code)
        is GrainstormClient.Failure.Malformed -> context.getString(R.string.wallpaper_error_malformed)
    }

    private fun requireConfigured(context: Context): Boolean {
        if (GrainstormPreferences.isConfigured(context)) return true
        toast(context, context.getString(R.string.wallpaper_not_configured))
        return false
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
