package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

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
        thread(name = "calendar-fetch") {
            val snapshot = try {
                CalendarRepository.refresh(appContext)
            } catch (e: Exception) {
                android.util.Log.e("CalendarPoller", "Refresh crashed: ${e.message}", e)
                CalendarSnapshot(
                    events = CalendarCenter.current.events,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    errorMessage = if (CalendarCenter.current.events.isEmpty()) "error" else null
                )
            }
            handler.post { CalendarCenter.update(snapshot) }
        }
    }
}
