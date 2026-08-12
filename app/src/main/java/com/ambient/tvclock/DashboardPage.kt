package com.ambient.tvclock

import androidx.annotation.LayoutRes

/**
 * The dashboard sections. Which pages are actually shown (and in what pager
 * position) is decided at runtime by MainActivity — HOMELAB is only present
 * when enabled in settings — so nothing may assume ordinal == pager position.
 */
enum class DashboardPage(@LayoutRes val layoutRes: Int) {
    STATUS(R.layout.screen_status),
    HOME(R.layout.screen_home),
    CALENDAR(R.layout.screen_calendar),
    MUSIC(R.layout.screen_music),
    ADBLOCK(R.layout.screen_adblock),
    HOMELAB(R.layout.screen_homelab_placeholder);
}
