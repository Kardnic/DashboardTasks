package de.kardnic.dashboardtaskswidget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

object SmartTaskParser {
    private val locale = Locale.GERMAN
    private val zone = ZoneId.systemDefault()

    fun parse(raw: String): TaskInsert {
        val original = raw.trim()
        val lower = original.lowercase(locale)

        var category = "Sonstiges"
        var priority = "normal"
        var waiting = false
        var recurrence = "none"

        if (Regex("\\b(js\\s*wenau|wenau|verein|fußball|fussball)\\b").containsMatchIn(lower)) {
            category = "JS Wenau"
        } else if (Regex("\\b(projekt|projekte)\\b").containsMatchIn(lower)) {
            category = "Projekte"
        } else if (Regex("\\b(arbeit|firma|betrieb|maschine|anlage|produktion|wartung|instandhaltung|sps|codesys|menke|hydraulik|audit|lieferant|kunde|schicht|werkzeug)\\b").containsMatchIn(lower)) {
            category = "Arbeit"
        } else if (Regex("\\b(privat|zuhause|haushalt|einkaufen|familie|garten|fahrrad|freizeit)\\b").containsMatchIn(lower)) {
            category = "Privat"
        }

        val explicitPrivate = Regex("\\b(privat|persönlich|persoenlich)\\b").containsMatchIn(lower)
        val explicitWork = Regex("\\b(arbeit|beruflich|dienstlich)\\b").containsMatchIn(lower)
        val workContext = Regex("\\b(firma|betrieb|maschine|anlage|produktion|wartung|instandhaltung|sps|codesys|menke|hydraulik|audit|lieferant|kunde|schicht|werkzeug)\\b").containsMatchIn(lower)
        val privateContext = Regex("\\b(zuhause|haushalt|einkaufen|familie|garten|fahrrad|freizeit|wenau|verein|fußball|fussball)\\b").containsMatchIn(lower)

        val area = when {
            explicitPrivate -> "Privat"
            explicitWork -> "Arbeit"
            workContext -> "Arbeit"
            privateContext || category == "JS Wenau" -> "Privat"
            category == "Arbeit" -> "Arbeit"
            else -> "Privat"
        }

        if (Regex("\\b(hohe?n?\\s+priorit[aä]t|priorit[aä]t\\s+hoch|dringend|sehr\\s+wichtig)\\b").containsMatchIn(lower)) {
            priority = "hoch"
        } else if (Regex("\\b(niedrige?n?\\s+priorit[aä]t|priorit[aä]t\\s+niedrig|nicht\\s+dringend)\\b").containsMatchIn(lower)) {
            priority = "niedrig"
        }

        waiting = Regex("\\b(warten\\s+auf|warte\\s+auf|rückmeldung\\s+von|antwort\\s+von)\\b").containsMatchIn(lower)

        recurrence = when {
            Regex("\\b(täglich|jeden\\s+tag)\\b").containsMatchIn(lower) -> "daily"
            Regex("\\b(wöchentlich|jede\\s+woche|jeden\\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag))\\b").containsMatchIn(lower) -> "weekly"
            Regex("\\b(monatlich|jeden\\s+monat)\\b").containsMatchIn(lower) -> "monthly"
            else -> "none"
        }

        val time = parseTime(lower)
        val date = parseDate(lower)
        val dueAt = if (date != null) {
            ZonedDateTime.of(date, time ?: LocalTime.NOON, zone).toInstant().toString()
        } else null

        var title = original
        val removalPatterns = listOf(
            "\\b(hohe?n?\\s+priorit[aä]t|priorit[aä]t\\s+hoch|dringend|sehr\\s+wichtig)\\b",
            "\\b(niedrige?n?\\s+priorit[aä]t|priorit[aä]t\\s+niedrig|nicht\\s+dringend)\\b",
            "\\b(arbeit|beruflich|dienstlich|privat|persönlich|persoenlich)\\b",
            "\\b(heute|morgen|übermorgen)\\b",
            "\\b(jeden\\s+tag|täglich|jede\\s+woche|wöchentlich|jeden\\s+monat|monatlich)\\b",
            "\\bjeden\\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\\b",
            "\\b(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\\b",
            "\\b(0?[1-9]|[12]\\d|3[01])\\.(0?[1-9]|1[0-2])\\.(\\d{2,4})\\b",
            "\\b(0?[1-9]|[12]\\d|3[01])\\.(0?[1-9]|1[0-2])\\.?\\b",
            "(?:\\bum\\s*)?\\b([01]?\\d|2[0-3])(?:[:.]([0-5]\\d))?\\s*uhr\\b"
        )
        removalPatterns.forEach { pattern ->
            title = title.replace(Regex(pattern, RegexOption.IGNORE_CASE), " ")
        }
        title = title
            .replace(Regex("\\s*[,;]+\\s*"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', ',', '.', ';', ':', '-')

        if (title.isBlank()) title = original
        title = title.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(locale) else ch.toString()
        }

        return TaskInsert(
            title = title,
            category = category,
            area = area,
            priority = priority,
            dueAt = dueAt,
            waitingFor = waiting,
            recurrence = recurrence,
            source = "text"
        )
    }

    private fun parseTime(lower: String): LocalTime? {
        val match = Regex("\\bum\\s+([01]?\\d|2[0-3])(?:[:.]([0-5]\\d))?(?:\\s*uhr)?\\b").find(lower)
            ?: Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*uhr)?\\b").find(lower)
            ?: Regex("\\b([01]?\\d|2[0-3])\\s*uhr\\b").find(lower)

        return match?.let {
            val minute = it.groupValues.getOrNull(2)?.takeIf { value -> value.isNotBlank() }?.toInt() ?: 0
            LocalTime.of(it.groupValues[1].toInt(), minute)
        }
    }

    private fun parseDate(lower: String): LocalDate? {
        val today = LocalDate.now(zone)

        when {
            Regex("\\bübermorgen\\b").containsMatchIn(lower) -> return today.plusDays(2)
            Regex("\\bmorgen\\b").containsMatchIn(lower) -> return today.plusDays(1)
            Regex("\\bheute\\b").containsMatchIn(lower) -> return today
        }

        Regex("\\b(0?[1-9]|[12]\\d|3[01])\\.(0?[1-9]|1[0-2])\\.(\\d{2,4})\\b")
            .find(lower)?.let { match ->
                val rawYear = match.groupValues[3].toInt()
                val year = if (rawYear < 100) 2000 + rawYear else rawYear
                return runCatching {
                    LocalDate.of(year, match.groupValues[2].toInt(), match.groupValues[1].toInt())
                }.getOrNull()
            }

        Regex("\\b(0?[1-9]|[12]\\d|3[01])\\.(0?[1-9]|1[0-2])\\.?\\b")
            .find(lower)?.let { match ->
                var date = runCatching {
                    LocalDate.of(today.year, match.groupValues[2].toInt(), match.groupValues[1].toInt())
                }.getOrNull()
                if (date != null && date.isBefore(today)) date = date.plusYears(1)
                return date
            }

        val weekdays = listOf(
            "montag" to DayOfWeek.MONDAY,
            "dienstag" to DayOfWeek.TUESDAY,
            "mittwoch" to DayOfWeek.WEDNESDAY,
            "donnerstag" to DayOfWeek.THURSDAY,
            "freitag" to DayOfWeek.FRIDAY,
            "samstag" to DayOfWeek.SATURDAY,
            "sonntag" to DayOfWeek.SUNDAY
        )

        weekdays.firstOrNull { pair ->
            Regex("\\b" + pair.first + "\\b").containsMatchIn(lower)
        }?.let { pair ->
            var date = today
            while (date.dayOfWeek != pair.second) date = date.plusDays(1)
            return date
        }

        return null
    }
}
