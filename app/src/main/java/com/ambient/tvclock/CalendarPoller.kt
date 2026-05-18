package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper

class CalendarPoller(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, CalendarPreferences.pollIntervalMs())
        }
    }

    fun start() {
        stop()
        publish()
        handler.postDelayed(pollRunnable, CalendarPreferences.pollIntervalMs())
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
    }

    fun publishNow() {
        publish()
    }

    private fun publish() {
        CalendarRefresh.publishAsync(appContext)
    }
}
