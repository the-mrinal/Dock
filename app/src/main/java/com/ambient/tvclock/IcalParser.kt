package com.ambient.tvclock

object IcalParser {

    fun parse(icsBody: String, source: CalendarSource): List<CalendarEvent> {
        val unfolded = unfold(icsBody)
        val vtimezones = parseVTimeZones(unfolded)
        val events = mutableListOf<CalendarEvent>()
        val blocks = unfolded.split("BEGIN:VEVENT")
        for (block in blocks.drop(1)) {
            val end = block.indexOf("END:VEVENT")
            val body = if (end >= 0) block.substring(0, end) else block
            try {
                parseEvent(body, source, vtimezones)?.let { events.add(it) }
            } catch (_: Exception) {
                // Skip malformed events; keep the rest of the feed.
            }
        }
        return events.sortedBy { it.startMillis }
    }

    /**
     * Maps each VTIMEZONE's TZID to a GMT-offset zone ID, so TZIDs that Java doesn't
     * recognize (e.g. Outlook's "Customized Time Zone") still resolve to a usable zone.
     */
    private fun parseVTimeZones(unfolded: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (block in unfolded.split("BEGIN:VTIMEZONE").drop(1)) {
            val end = block.indexOf("END:VTIMEZONE")
            val body = if (end >= 0) block.substring(0, end) else block
            val tzid = lineValue(body, "TZID:") ?: continue
            // Prefer the STANDARD offset; being an hour off during DST beats being off
            // by the zone's whole UTC offset.
            val standard = body.substringAfter("BEGIN:STANDARD", body)
            val offset = lineValue(standard, "TZOFFSETTO:") ?: lineValue(body, "TZOFFSETTO:") ?: continue
            IcalTimeZones.offsetToZoneId(offset)?.let { map[tzid] = it }
        }
        return map
    }

    private fun lineValue(body: String, prefix: String): String? =
        body.lineSequence().firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)?.trim()

    private fun parseEvent(
        body: String,
        source: CalendarSource,
        vtimezones: Map<String, String>
    ): CalendarEvent? {
        val props = parseProperties(body)

        // Cancelled events still appear in published feeds; never surface them.
        if (props["STATUS"]?.value?.trim()?.uppercase() == "CANCELLED") {
            return null
        }

        val summary = props["SUMMARY"]?.value
            ?.replace("\\n", " ")
            ?.replace("\\,", ",")
            ?.trim()
        if (summary.isNullOrEmpty()) {
            return null
        }

        val startProp = props["DTSTART"] ?: return null
        val endProp = props["DTEND"]
        val tzId = IcalTimeZones.resolveId(startProp.param("TZID"), vtimezones)
        val endTzId = endProp?.param("TZID")
            ?.let { IcalTimeZones.resolveId(it, vtimezones) }
            ?: tzId
        val allDay = startProp.param("VALUE") == "DATE" || startProp.value.length == 8

        val startMillis = if (allDay) {
            IcalDateTimes.parseDate(startProp.value, endOfDay = false, timeZoneId = tzId)
        } else {
            IcalDateTimes.parseDateTime(startProp.value, timeZoneId = tzId)
        }
        val endMillis = when {
            endProp != null && (endProp.param("VALUE") == "DATE" || endProp.value.length == 8) ->
                IcalDateTimes.parseDate(endProp.value, endOfDay = true, timeZoneId = endTzId)
            endProp != null ->
                IcalDateTimes.parseDateTime(endProp.value, timeZoneId = endTzId)
            allDay -> startMillis + 24 * 60 * 60 * 1000L
            else -> startMillis + 60 * 60 * 1000L
        }

        val location = props["LOCATION"]?.value
            ?.replace("\\n", " ")
            ?.replace("\\,", ",")
            ?.trim()
            .orEmpty()

        val rrule = props["RRULE"]?.value

        return CalendarEvent(
            title = summary,
            startMillis = startMillis,
            endMillis = endMillis,
            isAllDay = allDay,
            location = location,
            source = source,
            timeZoneId = tzId,
            rrule = rrule,
            busyStatus = parseBusyStatus(props),
            categories = parseCategories(props),
            onlineMeetingUrl = findMeetingUrl(props, location),
            organizer = parseOrganizer(props),
            colorHex = parseColor(props)
        )
    }

    private fun parseBusyStatus(props: Map<String, IcalProperty>): BusyStatus {
        when (props["X-MICROSOFT-CDO-BUSYSTATUS"]?.value?.trim()?.uppercase()) {
            "FREE" -> return BusyStatus.FREE
            "TENTATIVE" -> return BusyStatus.TENTATIVE
            "OOF" -> return BusyStatus.OOF
            "BUSY" -> return BusyStatus.BUSY
        }
        if (props["STATUS"]?.value?.trim()?.uppercase() == "TENTATIVE") {
            return BusyStatus.TENTATIVE
        }
        if (props["TRANSP"]?.value?.trim()?.uppercase() == "TRANSPARENT") {
            return BusyStatus.FREE
        }
        return BusyStatus.BUSY
    }

    private fun parseCategories(props: Map<String, IcalProperty>): List<String> {
        val raw = props["CATEGORIES"]?.value ?: return emptyList()
        return raw.split(',')
            .map { it.replace("\\,", ",").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun findMeetingUrl(props: Map<String, IcalProperty>, location: String): String? {
        props["X-MICROSOFT-SKYPETEAMSMEETINGURL"]?.value?.trim()
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }
        val haystacks = listOfNotNull(
            location,
            props["DESCRIPTION"]?.value,
            props["X-GOOGLE-CONFERENCE"]?.value,
            props["URL"]?.value
        )
        for (text in haystacks) {
            MEETING_URL_REGEX.find(text)?.let { return it.value.trimEnd('\\', '>', ')', '.') }
        }
        return null
    }

    private fun parseOrganizer(props: Map<String, IcalProperty>): String? {
        val prop = props["ORGANIZER"] ?: return null
        prop.param("CN")?.trim('"')?.takeIf { it.isNotBlank() }?.let { return it }
        return prop.value.removePrefix("mailto:").takeIf { it.isNotBlank() }
    }

    private fun parseColor(props: Map<String, IcalProperty>): String? {
        val raw = (props["X-APPLE-CALENDAR-COLOR"] ?: props["COLOR"])?.value?.trim()
            ?: return null
        // Accept #RRGGBB / #RRGGBBAA; CSS color names are not worth mapping.
        if (!raw.startsWith("#") || raw.length < 7) return null
        return raw.substring(0, 7)
    }

    private val MEETING_URL_REGEX = Regex(
        "https://(?:[\\w.-]*teams\\.microsoft\\.com/l/meetup-join|meet\\.google\\.com|[\\w.-]*zoom\\.us/j)[^\\s\"'<>]*"
    )

    private fun parseProperties(body: String): Map<String, IcalProperty> {
        val map = mutableMapOf<String, IcalProperty>()
        for (line in body.lines()) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val rawKey = line.substring(0, colon)
            val name = rawKey.substringBefore(';').uppercase()
            val params = mutableMapOf<String, String>()
            val semi = rawKey.indexOf(';')
            val paramPart = if (semi >= 0) rawKey.substring(semi + 1) else ""
            if (paramPart.isNotEmpty()) {
                for (segment in paramPart.split(';')) {
                    val eq = segment.indexOf('=')
                    if (eq > 0) {
                        params[segment.substring(0, eq).uppercase()] = segment.substring(eq + 1)
                    }
                }
            }
            val value = line.substring(colon + 1).trim()
            map[name] = IcalProperty(name, params, value)
        }
        return map
    }

    private fun unfold(ics: String): String {
        val lines = ics.replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = StringBuilder()
        for (line in lines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                out.append(line.trimStart())
            } else {
                if (out.isNotEmpty()) {
                    out.append('\n')
                }
                out.append(line)
            }
        }
        return out.toString()
    }
}
