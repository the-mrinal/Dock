package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

object MealPlanRefresh {
    private const val TAG = "MealPlanRefresh"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "meal-plan-fetch").apply { isDaemon = true }
    }

    fun publishAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val snapshot = try {
                MealPlanRepository.refresh(app)
            } catch (e: Exception) {
                Log.e(TAG, "Refresh crashed: ${e.message}", e)
                MealPlanCenter.current
            }
            mainHandler.post { MealPlanCenter.update(snapshot) }
        }
    }
}
