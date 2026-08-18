package com.ambient.tvclock

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object IcalDateTimes {

    private const val TAG = "IcalDateTimes"

    fun parseDateTime(raw: String, timeZoneId: String?): Long {
        val value = raw.trim()
        if (value.length == 8 && value.all { it.isDigit() }) {
            return parseDate(value, endOfDay = false, timeZoneId = timeZoneId)
        }

        parseManual(value, timeZoneId)?.let { return it }

        val patterns = if (value.endsWith("Z")) {
            listOf("yyyyMMdd'T'HHmmss'Z'" to TimeZone.getTimeZone("UTC"))
        } else {
            val tz = IcalTimeZones.timeZone(timeZoneId)
            listOf("yyyyMMdd'T'HHmmss" to tz, "yyyyMMdd'T'HHmmssX" to tz)
        }

        for ((pattern, tz) in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.isLenient = true
                fmt.timeZone = tz
                val parsed = fmt.parse(value) ?: continue
                return parsed.time
            } catch (_: Exception) {
                // Try next.
            }
        }

        Log.w(TAG, "Unparseable datetime: $value")
        throw java.text.ParseException("Unparseable date: \"$value\"", 0)
    }

    /** Parses `yyyyMMdd'T'HHmmss` without relying on SimpleDateFormat quirks on older Android. */
    private fun parseManual(value: String, timeZoneId: String?): Long? {
        if (value.length < 15 || value[8] != 'T') {
            return null
        }
        val datePart = value.substring(0, 8)
        val timePart = value.substring(9).take(6)
        if (datePart.length != 8 || timePart.length != 6) {
            return null
        }
        if (!datePart.all { it.isDigit() } || !timePart.all { it.isDigit() }) {
            return null
        }

        // A trailing Z marks UTC and takes precedence over any TZID on the property.
        val isUtc = value.length > 15 && value[15] == 'Z'
        val tz = if (isUtc) TimeZone.getTimeZone("UTC") else IcalTimeZones.timeZone(timeZoneId)
        val cal = Calendar.getInstance(tz)
        cal.set(
            datePart.substring(0, 4).toInt(),
            datePart.substring(4, 6).toInt() - 1,
            datePart.substring(6, 8).toInt(),
            timePart.substring(0, 2).toInt(),
            timePart.substring(2, 4).toInt(),
            timePart.substring(4, 6).toInt()
        )
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun parseDate(raw: String, endOfDay: Boolean, timeZoneId: String?): Long {
        val tz = IcalTimeZones.timeZone(timeZoneId)
        val cal = Calendar.getInstance(tz)
        val digits = raw.trim()
        if (digits.length == 8 && digits.all { it.isDigit() }) {
            cal.set(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt() - 1,
                digits.substring(6, 8).toInt(),
                0, 0, 0
            )
        } else {
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            fmt.timeZone = tz
            fmt.parse(digits)?.let { cal.time = it }
        }
        if (endOfDay) {
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
        }
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
