package com.ambient.tvclock

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object MealPlanParser {

    private const val TAG = "MealPlanParser"

    fun parse(body: String): MealPlan? {
        return try {
            val root = JSONObject(body)
            val version = root.optInt("version", 1)
            val generatedAt = parseGeneratedAt(root.optString("generated_at", ""))
            val targets = parseTargets(root.optJSONObject("daily_targets"))
            val routine = parseRoutine(root.optJSONArray("daily_routine"))
            val days = parseDays(root.optJSONObject("days"))
            val hydration = parseHydration(root.optJSONObject("hydration_prompt"))
            MealPlan(
                version = version,
                generatedAtMs = generatedAt,
                dailyTargets = targets,
                dailyRoutine = routine,
                days = days,
                hydrationPrompt = hydration
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    private fun parseGeneratedAt(value: String): Long? {
        if (value.isBlank()) return null
        return try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseTargets(obj: JSONObject?): DailyTargets? {
        if (obj == null) return null
        val protein = if (obj.has("protein_g")) obj.optInt("protein_g") else null
        val calories = obj.optString("calories", "").takeIf { it.isNotBlank() }
        if (protein == null && calories == null) return null
        return DailyTargets(protein, calories)
    }

    private fun parseRoutine(arr: JSONArray?): List<RoutineEvent> {
        if (arr == null) return emptyList()
        val out = mutableListOf<RoutineEvent>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val time = parseTime(obj.optString("time", "")) ?: continue
            val label = obj.optString("label", "").takeIf { it.isNotBlank() } ?: continue
            val kind = parseEventKind(obj.optString("kind", "other"))
            val detail = obj.optString("detail", "").takeIf { it.isNotBlank() }
            val skip = parseSkipOn(obj.optJSONArray("skip_on"))
            out += RoutineEvent(time, label, kind, detail, skip)
        }
        return out
    }

    private fun parseSkipOn(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim().lowercase()
            if (v.isNotEmpty()) out += v
        }
        return out
    }

    private fun parseDays(obj: JSONObject?): Map<String, MealDay> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, MealDay>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val dayObj = obj.optJSONObject(key) ?: continue
            val day = parseDay(dayObj) ?: continue
            out[key.lowercase()] = day
        }
        return out
    }

    private fun parseDay(obj: JSONObject): MealDay? {
        val label = obj.optString("label", "").takeIf { it.isNotBlank() } ?: return null
        val kind = parseDayKind(obj.optString("kind", "normal"))
        val kindLabel = obj.optString("kind_label", "").takeIf { it.isNotBlank() }
        val proteinSummary = obj.optString("protein_summary", "").takeIf { it.isNotBlank() }
        val meals = parseMeals(obj.optJSONArray("meals"))
        val note = obj.optString("note", "").takeIf { it.isNotBlank() && it != "null" }
        val prep = parseStringArray(obj.optJSONArray("prep_for_tomorrow"))
        return MealDay(label, kind, kindLabel, proteinSummary, meals, note, prep)
    }

    private fun parseMeals(arr: JSONArray?): List<Meal> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Meal>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: continue
            val time = parseTime(obj.optString("time", "")) ?: continue
            val label = obj.optString("label", "").takeIf { it.isNotBlank() } ?: continue
            val body = obj.optString("body", "").takeIf { it.isNotBlank() } ?: continue
            val protein = obj.optString("protein", "").takeIf { it.isNotBlank() }
            out += Meal(id, time, label, body, protein)
        }
        return out
    }

    private fun parseHydration(obj: JSONObject?): HydrationPrompt? {
        if (obj == null) return null
        val minutes = obj.optInt("minutes_before_meal", -1)
        val text = obj.optString("text", "").takeIf { it.isNotBlank() }
        if (minutes < 0 || text == null) return null
        return HydrationPrompt(minutes, text)
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank()) out += v
        }
        return out
    }

    private fun parseTime(value: String): LocalTime? {
        if (value.isBlank()) return null
        return try {
            LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: DateTimeParseException) {
            Log.w(TAG, "Invalid HH:MM time: $value")
            null
        }
    }

    private fun parseEventKind(value: String): EventKind = when (value.trim().lowercase()) {
        "wake" -> EventKind.WAKE
        "gym" -> EventKind.GYM
        "snack" -> EventKind.SNACK
        "meal" -> EventKind.MEAL
        "buy" -> EventKind.BUY
        "sleep" -> EventKind.SLEEP
        else -> EventKind.OTHER
    }

    private fun parseDayKind(value: String): DayKind = when (value.trim().lowercase()) {
        "veg" -> DayKind.VEG
        "diy" -> DayKind.DIY
        else -> DayKind.NORMAL
    }
}
