package de.kardnic.dashboardtasks.widget

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class WidgetPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("dashboardtasks_widget", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun mode(): AreaMode = runCatching {
        AreaMode.valueOf(prefs.getString(KEY_MODE, AreaMode.AUTO.name) ?: AreaMode.AUTO.name)
    }.getOrDefault(AreaMode.AUTO)

    fun setMode(mode: AreaMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun cycleMode(): AreaMode {
        val next = when (mode()) {
            AreaMode.AUTO -> AreaMode.WORK
            AreaMode.WORK -> AreaMode.PRIVATE
            AreaMode.PRIVATE -> AreaMode.AUTO
        }
        setMode(next)
        return next
    }

    fun setAutoArea(area: String) {
        prefs.edit().putString(KEY_AUTO_AREA, normalizeArea(area)).apply()
    }

    fun autoArea(): String = normalizeArea(prefs.getString(KEY_AUTO_AREA, "Privat"))

    fun saveWorkLocation(location: WorkLocation) {
        prefs.edit().putString(KEY_WORK_LOCATION, json.encodeToString(location)).apply()
    }

    fun workLocation(): WorkLocation? {
        val raw = prefs.getString(KEY_WORK_LOCATION, null) ?: return null
        return runCatching { json.decodeFromString<WorkLocation>(raw) }.getOrNull()
    }

    fun clearWorkLocation() {
        prefs.edit().remove(KEY_WORK_LOCATION).apply()
        setAutoArea("Privat")
    }

    fun saveTasks(tasks: List<TaskDto>) {
        prefs.edit()
            .putString(KEY_TASKS, json.encodeToString(tasks))
            .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
    }

    fun markLoggedOut() {
        prefs.edit().putBoolean(KEY_LOGGED_IN, false).apply()
    }

    fun setLoggedIn(value: Boolean) {
        prefs.edit().putBoolean(KEY_LOGGED_IN, value).apply()
    }

    fun cachedTasks(): List<TaskDto> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<TaskDto>>(raw) }.getOrDefault(emptyList())
    }

    fun snapshot(maxTasks: Int = 7): WidgetSnapshot {
        val mode = mode()
        val effectiveArea = when (mode) {
            AreaMode.WORK -> "Arbeit"
            AreaMode.PRIVATE -> "Privat"
            AreaMode.AUTO -> autoArea()
        }

        val open = cachedTasks()
            .filter { !it.completed && !it.waitingFor && normalizeArea(it.area) == effectiveArea }

        val now = Instant.now()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        fun dueInstant(task: TaskDto): Instant? = task.dueAt?.let { raw ->
            runCatching { OffsetDateTime.parse(raw).toInstant() }
                .recoverCatching { Instant.parse(raw) }
                .getOrNull()
        }

        val todayCount = open.count { task ->
            dueInstant(task)?.atZone(zone)?.toLocalDate() == today
        }

        val overdueCount = open.count { task ->
            val due = dueInstant(task) ?: return@count false
            due.atZone(zone).toLocalDate().isBefore(today)
        }

        val priorityOrder = mapOf("hoch" to 0, "normal" to 1, "niedrig" to 2)

        val sorted = open.sortedWith(
            compareBy<TaskDto> {
                val due = dueInstant(it)
                when {
                    due == null -> 3
                    due.isBefore(now) -> 0
                    due.atZone(zone).toLocalDate() == today -> 1
                    else -> 2
                }
            }.thenBy { priorityOrder[it.priority] ?: 1 }
                .thenBy { dueInstant(it) ?: Instant.MAX }
                .thenBy { it.createdAt ?: "" }
        ).take(maxTasks)

        return WidgetSnapshot(
            loggedIn = prefs.getBoolean(KEY_LOGGED_IN, false),
            mode = mode,
            effectiveArea = effectiveArea,
            todayCount = todayCount,
            overdueCount = overdueCount,
            tasks = sorted,
            lastUpdatedMillis = prefs.getLong(KEY_LAST_UPDATED, 0L)
        )
    }

    private fun normalizeArea(area: String?): String =
        if (area.equals("Arbeit", ignoreCase = true)) "Arbeit" else "Privat"

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_AUTO_AREA = "auto_area"
        private const val KEY_WORK_LOCATION = "work_location"
        private const val KEY_TASKS = "tasks"
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val KEY_LOGGED_IN = "logged_in"
    }
}
