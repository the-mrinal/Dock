package com.ambient.tvclock

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ambient.tvclock.settings.AboutSettingsFragment
import com.ambient.tvclock.settings.CalendarSettingsFragment
import com.ambient.tvclock.settings.DisplaySettingsFragment
import com.ambient.tvclock.settings.MusicSettingsFragment
import com.ambient.tvclock.settings.PhoneMirroringSettingsFragment
import com.ambient.tvclock.settings.SettingsGroup
import com.ambient.tvclock.settings.SettingsGroups
import com.ambient.tvclock.settings.SettingsRailAdapter
import com.ambient.tvclock.settings.VpnSettingsFragment
import com.ambient.tvclock.ui.ArtWashView

/**
 * Custom Settings host (artboard 09). Replaces the legacy `PreferenceFragmentCompat`
 * shell with a `RecyclerView` rail + `FragmentContainerView` content frame.
 *
 * Behaviour:
 *  - Rail selection swaps the content fragment with a 150ms fade.
 *  - Rail row gaining focus also activates the row (matches the HTML where the
 *    eyebrow + title update as soon as the rail focus moves).
 *  - Top eyebrow + 56sp title rebind from the [SettingsGroup] metadata.
 *  - Activity entry point is unchanged — `MainActivity` still launches us via
 *    `startActivity(Intent(this, SettingsActivity::class.java))` from the
 *    MENU/SETTINGS key handlers.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var rail: RecyclerView
    private lateinit var eyebrow: TextView
    private lateinit var title: TextView
    private lateinit var content: View
    private lateinit var artWash: ArtWashView
    private lateinit var railAdapter: SettingsRailAdapter

    private var currentGroupId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OnboardingPreferences.markSettingsVisited(this)
        setContentView(R.layout.activity_settings)

        rail = findViewById(R.id.settingsRail)
        eyebrow = findViewById(R.id.settingsEyebrow)
        title = findViewById(R.id.settingsTitle)
        content = findViewById(R.id.settingsContent)
        artWash = findViewById(R.id.settingsArtWash)

        artWash.setSeed("settings")
        artWash.setIntensity(0.08f)

        railAdapter = SettingsRailAdapter(SettingsGroups.ALL) { group ->
            selectGroup(group)
        }
        rail.layoutManager = LinearLayoutManager(this)
        rail.adapter = railAdapter

        // Restore selection from process recreation; otherwise default to
        // Calendar (matches HTML default).
        val initialId = savedInstanceState?.getString(STATE_GROUP) ?: SettingsGroups.ID_CALENDAR
        val initialGroup = SettingsGroups.ALL.firstOrNull { it.id == initialId }
            ?: SettingsGroups.ALL.first()
        selectGroup(initialGroup, animate = false)

        // Give the rail first focus so the user can immediately D-pad through
        // the groups without having to wake the content area.
        rail.post {
            rail.findViewHolderForAdapterPosition(SettingsGroups.ALL.indexOf(initialGroup))
                ?.itemView
                ?.findViewById<View>(R.id.railItemSurface)
                ?.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentGroupId?.let { outState.putString(STATE_GROUP, it) }
    }

    /**
     * Swap the content fragment to match [group] and refresh the header.
     * Skips the work if the group is already current. The fade is purely
     * cosmetic and runs on the *new* fragment view — we don't try to fade out
     * the previous one because the FragmentManager has already torn it down by
     * the time the new view binds.
     */
    private fun selectGroup(group: SettingsGroup, animate: Boolean = true) {
        if (currentGroupId == group.id) return
        currentGroupId = group.id
        railAdapter.setSelected(group.id)

        eyebrow.text = getString(group.eyebrowRes)
        title.text = getString(group.titleRes)

        val fragment: Fragment = when (group.id) {
            SettingsGroups.ID_CALENDAR -> CalendarSettingsFragment()
            SettingsGroups.ID_MUSIC -> MusicSettingsFragment()
            SettingsGroups.ID_MIRRORING -> PhoneMirroringSettingsFragment()
            SettingsGroups.ID_VPN -> VpnSettingsFragment()
            SettingsGroups.ID_DISPLAY -> DisplaySettingsFragment()
            SettingsGroups.ID_ABOUT -> AboutSettingsFragment()
            else -> CalendarSettingsFragment()
        }
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settingsContent, fragment, group.id)
            .commitNow()

        if (animate && content.isVisible) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            content.alpha = 1f
        }
    }

    /**
     * Intercept the BACK key from inside the content frame so it returns the
     * D-pad to the rail rather than dismissing the activity outright — matches
     * the HTML "BACK Home" hint at the bottom.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val focused = currentFocus
            if (focused != null && rail.findContainingItemView(focused) == null) {
                // We're in the content frame — bounce back to the rail.
                rail.findViewHolderForAdapterPosition(
                    SettingsGroups.ALL.indexOfFirst { it.id == currentGroupId }.coerceAtLeast(0)
                )?.itemView?.findViewById<View>(R.id.railItemSurface)?.requestFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val STATE_GROUP = "settings.activeGroup"
        const val FADE_MS = 150L
    }
}
