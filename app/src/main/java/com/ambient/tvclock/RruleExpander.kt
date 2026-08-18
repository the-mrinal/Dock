package com.ambient.tvclock

import java.util.Calendar
import java.util.TimeZone

object RruleExpander {

    private data class Rrule(
        val freq: String,
        val interval: Int = 1,
        val untilMillis: Long? = null,
        val count: Int? = null
    )

    fun expand(
        events: List<CalendarEvent>,
        rangeStart: Long,
        rangeEnd: Long
    ): List<CalendarEvent> {
        val out = mutableListOf<CalendarEvent>()
        for (event in events) {
            val rule = event.rrule?.let { parseRrule(it) }
            if (rule == null) {
                out.add(event)
            } else {
                out.addAll(expandOne(event, rule, rangeStart, rangeEnd))
            }
        }
        return out.sortedBy { it.startMillis }
    }

    private fun expandOne(
        master: CalendarEvent,
        rule: Rrule,
        rangeStart: Long,
        rangeEnd: Long
    ): List<CalendarEvent> {
        return when (rule.freq) {
            "DAILY" -> expandDaily(master, rule, rangeStart, rangeEnd)
            "WEEKLY" -> expandWeekly(master, rule, rangeStart, rangeEnd)
            else -> emptyList()
        }
    }

    private fun expandDaily(
        master: CalendarEvent,
        rule: Rrule,
        rangeStart: Long,
        rangeEnd: Long
    ): List<CalendarEvent> {
        val tz = timeZone(master.timeZoneId)
        val timeSource = Calendar.getInstance(tz).apply { timeInMillis = master.startMillis }
        val duration = (master.endMillis - master.startMillis).coerceAtLeast(60_000L)
        val masterDay = startOfDay(master.startMillis, tz)

        val results = mutableListOf<CalendarEvent>()
        var day = startOfDay(rangeStart, tz)
        val lastDay = startOfDay(rangeEnd - 1, tz)

        while (!day.after(lastDay)) {
            if (day.before(masterDay)) {
                day.add(Calendar.DAY_OF_YEAR, 1)
                continue
            }
            if (rule.untilMillis != null && day.timeInMillis > rule.untilMillis) {
                break
            }
            val dayOffset = daysBetween(masterDay, day)
            if (dayOffset % rule.interval == 0) {
                val instanceStart = combineDateWithTime(day, timeSource, tz)
                val instanceEnd = instanceStart + duration
                if (instanceStart < rangeEnd && instanceEnd > rangeStart) {
                    results.add(
                        master.copy(
                            startMillis = instanceStart,
                            endMillis = instanceEnd,
                            rrule = null
                        )
                    )
                }
            }
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return results
    }

    private fun expandWeekly(
        master: CalendarEvent,
        rule: Rrule,
        rangeStart: Long,
        rangeEnd: Long
    ): List<CalendarEvent> {
        val tz = timeZone(master.timeZoneId)
        val timeSource = Calendar.getInstance(tz).apply { timeInMillis = master.startMillis }
        val duration = (master.endMillis - master.startMillis).coerceAtLeast(60_000L)
        val masterDay = startOfDay(master.startMillis, tz)
        val targetDow = timeSource.get(Calendar.DAY_OF_WEEK)

        val results = mutableListOf<CalendarEvent>()
        var day = startOfDay(rangeStart, tz)
        val lastDay = startOfDay(rangeEnd - 1, tz)

        while (!day.after(lastDay)) {
            if (day.before(masterDay)) {
                day.add(Calendar.DAY_OF_YEAR, 1)
                continue
            }
            if (rule.untilMillis != null && day.timeInMillis > rule.untilMillis) {
                break
            }
            if (day.get(Calendar.DAY_OF_WEEK) == targetDow) {
                val weeks = daysBetween(masterDay, day) / 7
                if (weeks % rule.interval == 0) {
                    val instanceStart = combineDateWithTime(day, timeSource, tz)
                    val instanceEnd = instanceStart + duration
                    if (instanceStart < rangeEnd && instanceEnd > rangeStart) {
                        results.add(
                            master.copy(
                                startMillis = instanceStart,
                                endMillis = instanceEnd,
                                rrule = null
                            )
                        )
                    }
                }
            }
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return results
    }

    private fun parseRrule(raw: String): Rrule? {
        var freq: String? = null
        var interval = 1
        var until: Long? = null
        var count: Int? = null
        for (part in raw.split(';')) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) continue
            when (kv[0].uppercase()) {
                "FREQ" -> freq = kv[1].uppercase()
                "INTERVAL" -> interval = kv[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
                "UNTIL" -> until = parseUntil(kv[1])
                "COUNT" -> count = kv[1].toIntOrNull()
            }
        }
        return freq?.let { Rrule(it, interval, until, count) }
    }

    private fun parseUntil(value: String): Long? {
        val trimmed = value.trim()
        return try {
            if (trimmed.length == 8) {
                IcalDateTimes.parseDate(trimmed, endOfDay = true, timeZoneId = "UTC")
            } else {
                IcalDateTimes.parseDateTime(trimmed, timeZoneId = "UTC")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun timeZone(id: String?): TimeZone = IcalTimeZones.timeZone(id)

    private fun startOfDay(millis: Long, tz: TimeZone): Calendar =
        Calendar.getInstance(tz).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun combineDateWithTime(day: Calendar, timeSource: Calendar, tz: TimeZone): Long =
        Calendar.getInstance(tz).apply {
            set(Calendar.YEAR, day.get(Calendar.YEAR))
            set(Calendar.MONTH, day.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, day.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, timeSource.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, timeSource.get(Calendar.MINUTE))
            set(Calendar.SECOND, timeSource.get(Calendar.SECOND))
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun daysBetween(start: Calendar, end: Calendar): Int {
        val diff = end.timeInMillis - start.timeInMillis
        return (diff / (24 * 60 * 60 * 1000L)).toInt()
    }
}
