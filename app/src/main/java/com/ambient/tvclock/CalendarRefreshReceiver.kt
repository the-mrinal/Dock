package com.ambient.tvclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb shell am broadcast -a com.ambient.tvclock.action.REFRESH_CALENDAR \
 *   -n com.ambient.tvclock/.CalendarRefreshReceiver
 */
class CalendarRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CalendarRefresh.publishAsync(context)
    }
}
