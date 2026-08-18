package com.ambient.tvclock

import android.util.Log
import java.util.TimeZone

/**
 * Resolves ICS TZID values to Java time zones. Outlook feeds publish Windows zone
 * names ("India Standard Time") that TimeZone.getTimeZone() silently maps to GMT,
 * shifting every event by the zone's full UTC offset.
 */
object IcalTimeZones {

    private const val TAG = "IcalTimeZones"

    /** Resolves [rawId] leniently; falls back to the device zone when unresolvable. */
    fun timeZone(rawId: String?): TimeZone {
        val id = resolveId(rawId) ?: return TimeZone.getDefault()
        return TimeZone.getTimeZone(id)
    }

    /**
     * Normalizes a raw TZID to an ID Java recognizes, or null when it can't be resolved.
     * [vtimezones] maps TZIDs declared in the feed's VTIMEZONE blocks to GMT-offset IDs.
     */
    fun resolveId(rawId: String?, vtimezones: Map<String, String> = emptyMap()): String? {
        if (rawId.isNullOrBlank()) return null
        val id = rawId.trim().trim('"')
        if (isValid(id)) return id
        WINDOWS_TO_IANA[id]?.let { return it }
        vtimezones[id]?.let { return it }
        // e.g. "/mozilla.org/20070129_1/Asia/Kolkata" — try each trailing path suffix.
        if ('/' in id) {
            val parts = id.split('/').filter { it.isNotEmpty() }
            for (i in 1 until parts.size) {
                val candidate = parts.subList(i, parts.size).joinToString("/")
                if (isValid(candidate)) return candidate
            }
        }
        // e.g. "(UTC+05:30) Chennai, Kolkata, Mumbai, New Delhi"
        UTC_OFFSET.find(id)?.let { m ->
            val (sign, hours, minutes) = m.destructured
            return "GMT$sign${hours.padStart(2, '0')}:$minutes"
        }
        Log.w(TAG, "Unresolvable TZID: $rawId")
        return null
    }

    /** Converts an ICS UTC offset ("+0530", "-08:00", "+053000") to a GMT-offset zone ID. */
    fun offsetToZoneId(offset: String): String? {
        val m = ICS_OFFSET.matchEntire(offset.trim()) ?: return null
        val (sign, hours, minutes) = m.destructured
        return "GMT$sign${hours.padStart(2, '0')}:$minutes"
    }

    private fun isValid(id: String): Boolean =
        TimeZone.getTimeZone(id).id.equals(id, ignoreCase = true)

    private val ICS_OFFSET = Regex("""([+-])(\d{1,2}):?(\d{2})(?:\d{2})?""")
    private val UTC_OFFSET = Regex("""(?:UTC|GMT)\s*([+-])(\d{1,2}):?(\d{2})""")

    /** CLDR windowsZones mapping, Windows zone name to territory-001 IANA ID. */
    private val WINDOWS_TO_IANA = mapOf(
        "Dateline Standard Time" to "Etc/GMT+12",
        "UTC-11" to "Etc/GMT+11",
        "Aleutian Standard Time" to "America/Adak",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Marquesas Standard Time" to "Pacific/Marquesas",
        "Alaskan Standard Time" to "America/Anchorage",
        "UTC-09" to "Etc/GMT+9",
        "Pacific Standard Time (Mexico)" to "America/Tijuana",
        "UTC-08" to "Etc/GMT+8",
        "Pacific Standard Time" to "America/Los_Angeles",
        "US Mountain Standard Time" to "America/Phoenix",
        "Mountain Standard Time (Mexico)" to "America/Chihuahua",
        "Mountain Standard Time" to "America/Denver",
        "Central America Standard Time" to "America/Guatemala",
        "Central Standard Time" to "America/Chicago",
        "Easter Island Standard Time" to "Pacific/Easter",
        "Central Standard Time (Mexico)" to "America/Mexico_City",
        "Canada Central Standard Time" to "America/Regina",
        "SA Pacific Standard Time" to "America/Bogota",
        "Eastern Standard Time (Mexico)" to "America/Cancun",
        "Eastern Standard Time" to "America/New_York",
        "Haiti Standard Time" to "America/Port-au-Prince",
        "Cuba Standard Time" to "America/Havana",
        "US Eastern Standard Time" to "America/Indiana/Indianapolis",
        "Paraguay Standard Time" to "America/Asuncion",
        "Atlantic Standard Time" to "America/Halifax",
        "Venezuela Standard Time" to "America/Caracas",
        "Central Brazilian Standard Time" to "America/Cuiaba",
        "SA Western Standard Time" to "America/La_Paz",
        "Pacific SA Standard Time" to "America/Santiago",
        "Newfoundland Standard Time" to "America/St_Johns",
        "Tocantins Standard Time" to "America/Araguaina",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "SA Eastern Standard Time" to "America/Cayenne",
        "Argentina Standard Time" to "America/Argentina/Buenos_Aires",
        "Greenland Standard Time" to "America/Godthab",
        "Montevideo Standard Time" to "America/Montevideo",
        "Bahia Standard Time" to "America/Bahia",
        "UTC-02" to "Etc/GMT+2",
        "Azores Standard Time" to "Atlantic/Azores",
        "Cape Verde Standard Time" to "Atlantic/Cape_Verde",
        "UTC" to "Etc/UTC",
        "Coordinated Universal Time" to "Etc/UTC",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "Morocco Standard Time" to "Africa/Casablanca",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Romance Standard Time" to "Europe/Paris",
        "Central European Standard Time" to "Europe/Warsaw",
        "W. Central Africa Standard Time" to "Africa/Lagos",
        "Jordan Standard Time" to "Asia/Amman",
        "GTB Standard Time" to "Europe/Bucharest",
        "Middle East Standard Time" to "Asia/Beirut",
        "Egypt Standard Time" to "Africa/Cairo",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "Syria Standard Time" to "Asia/Damascus",
        "West Bank Standard Time" to "Asia/Hebron",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "FLE Standard Time" to "Europe/Kiev",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Kaliningrad Standard Time" to "Europe/Kaliningrad",
        "Libya Standard Time" to "Africa/Tripoli",
        "Namibia Standard Time" to "Africa/Windhoek",
        "Arabic Standard Time" to "Asia/Baghdad",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Arab Standard Time" to "Asia/Riyadh",
        "Belarus Standard Time" to "Europe/Minsk",
        "Russian Standard Time" to "Europe/Moscow",
        "E. Africa Standard Time" to "Africa/Nairobi",
        "Iran Standard Time" to "Asia/Tehran",
        "Arabian Standard Time" to "Asia/Dubai",
        "Astrakhan Standard Time" to "Europe/Astrakhan",
        "Azerbaijan Standard Time" to "Asia/Baku",
        "Caucasus Standard Time" to "Asia/Yerevan",
        "Georgian Standard Time" to "Asia/Tbilisi",
        "Mauritius Standard Time" to "Indian/Mauritius",
        "Saratov Standard Time" to "Europe/Saratov",
        "Afghanistan Standard Time" to "Asia/Kabul",
        "West Asia Standard Time" to "Asia/Tashkent",
        "Ekaterinburg Standard Time" to "Asia/Yekaterinburg",
        "Pakistan Standard Time" to "Asia/Karachi",
        "India Standard Time" to "Asia/Kolkata",
        "Sri Lanka Standard Time" to "Asia/Colombo",
        "Nepal Standard Time" to "Asia/Kathmandu",
        "Central Asia Standard Time" to "Asia/Almaty",
        "Bangladesh Standard Time" to "Asia/Dhaka",
        "Myanmar Standard Time" to "Asia/Yangon",
        "SE Asia Standard Time" to "Asia/Bangkok",
        "Altai Standard Time" to "Asia/Barnaul",
        "N. Central Asia Standard Time" to "Asia/Novosibirsk",
        "North Asia Standard Time" to "Asia/Krasnoyarsk",
        "Tomsk Standard Time" to "Asia/Tomsk",
        "China Standard Time" to "Asia/Shanghai",
        "North Asia East Standard Time" to "Asia/Irkutsk",
        "Singapore Standard Time" to "Asia/Singapore",
        "Taipei Standard Time" to "Asia/Taipei",
        "Ulaanbaatar Standard Time" to "Asia/Ulaanbaatar",
        "W. Australia Standard Time" to "Australia/Perth",
        "Korea Standard Time" to "Asia/Seoul",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Yakutsk Standard Time" to "Asia/Yakutsk",
        "Cen. Australia Standard Time" to "Australia/Adelaide",
        "AUS Central Standard Time" to "Australia/Darwin",
        "E. Australia Standard Time" to "Australia/Brisbane",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "West Pacific Standard Time" to "Pacific/Port_Moresby",
        "Tasmania Standard Time" to "Australia/Hobart",
        "Vladivostok Standard Time" to "Asia/Vladivostok",
        "Lord Howe Standard Time" to "Australia/Lord_Howe",
        "Magadan Standard Time" to "Asia/Magadan",
        "Norfolk Standard Time" to "Pacific/Norfolk",
        "Sakhalin Standard Time" to "Asia/Sakhalin",
        "Russia Time Zone 11" to "Asia/Kamchatka",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "UTC+12" to "Etc/GMT-12",
        "Fiji Standard Time" to "Pacific/Fiji",
        "Chatham Islands Standard Time" to "Pacific/Chatham",
        "UTC+13" to "Etc/GMT-13",
        "Tonga Standard Time" to "Pacific/Tongatapu",
        "Samoa Standard Time" to "Pacific/Apia",
        "Line Islands Standard Time" to "Pacific/Kiritimati"
    )
}
