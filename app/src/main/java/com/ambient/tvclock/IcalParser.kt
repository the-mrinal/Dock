package com.ambient.tvclock

object IcalParser {

    fun parse(icsBody: String, source: CalendarSource): List<CalendarEvent> {
        val unfolded = unfold(icsBody)
        val events = mutableListOf<CalendarEvent>()
        val blocks = unfolded.split("BEGIN:VEVENT")
        for (block in blocks.drop(1)) {
            val end = block.indexOf("END:VEVENT")
            val body = if (end >= 0) block.substring(0, end) else block
            try {
                parseEvent(body, source)?.let { events.add(it) }
            } catch (_: Exception) {
                // Skip malformed events; keep the rest of the feed.
            }
        }
        return events.sortedBy { it.startMillis }
    }

    private fun parseEvent(body: String, source: CalendarSource): CalendarEvent? {
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
        val tzId = startProp.param("TZID")
        val allDay = startProp.param("VALUE") == "DATE" || startProp.value.length == 8

        val startMillis = if (allDay) {
            IcalDateTimes.parseDate(startProp.value, endOfDay = false, timeZoneId = tzId)
        } else {
            IcalDateTimes.parseDateTime(startProp.value, timeZoneId = tzId)
        }
        val endMillis = when {
            endProp != null && (endProp.param("VALUE") == "DATE" || endProp.value.length == 8) ->
                IcalDateTimes.parseDate(endProp.value, endOfDay = true, timeZoneId = endProp.param("TZID") ?: tzId)
            endProp != null ->
                IcalDateTimes.parseDateTime(endProp.value, timeZoneId = endProp.param("TZID") ?: tzId)
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
