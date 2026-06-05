package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper

class MealPlanPoller(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, MealPlanPreferences.pollIntervalMs())
        }
    }

    fun start() {
        stop()
        publish()
        handler.postDelayed(pollRunnable, MealPlanPreferences.pollIntervalMs())
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
    }

    fun publishNow() {
        publish()
    }

    private fun publish() {
        MealPlanRefresh.publishAsync(appContext)
    }
}
