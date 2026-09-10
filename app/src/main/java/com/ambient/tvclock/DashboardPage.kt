package com.ambient.tvclock

import androidx.annotation.LayoutRes

/**
 * The dashboard sections. Which pages are actually shown (and in what pager
 * position) is decided at runtime by MainActivity — ADBLOCK and HOMELAB are
 * only present when enabled in settings, and NOTICES only while a notice is
 * inside its window — so nothing may assume ordinal == pager position.
 */
enum class DashboardPage(@LayoutRes val layoutRes: Int) {
    STATUS(R.layout.screen_status),
    HOME(R.layout.screen_home),
    CALENDAR(R.layout.screen_calendar),
    MUSIC(R.layout.screen_music),
    NOTICES(R.layout.screen_notices),
    ADBLOCK(R.layout.screen_adblock),
    HOMELAB(R.layout.screen_homelab_placeholder);
}
