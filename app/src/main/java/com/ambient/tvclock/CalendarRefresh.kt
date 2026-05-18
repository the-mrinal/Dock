package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

object CalendarRefresh {
    private const val TAG = "CalendarRefresh"
    private val mainHandler = Handler(Looper.getMainLooper())

    // One persistent daemon thread services every refresh — receiver pings,
    // poller ticks, and the settings "refresh now" button all coalesce here
    // instead of allocating a fresh OS thread per call.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "calendar-fetch").apply { isDaemon = true }
    }

    fun publishAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val snapshot = try {
                CalendarRepository.refresh(app)
            } catch (e: Exception) {
                Log.e(TAG, "Refresh crashed: ${e.message}", e)
                CalendarSnapshot(
                    events = CalendarCenter.current.events,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    errorMessage = if (CalendarCenter.current.events.isEmpty()) "error" else null
                )
            }
            mainHandler.post { CalendarCenter.update(snapshot) }
        }
    }
}
