package de.kardnic.dashboardtasks.widget

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String = "Sonstiges",
    val area: String = "Privat",
    val priority: String = "normal",
    @SerialName("due_at") val dueAt: String? = null,
    val completed: Boolean = false,
    @SerialName("waiting_for") val waitingFor: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class WorkLocation(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 300f
)

enum class AreaMode {
    AUTO,
    WORK,
    PRIVATE
}

data class WidgetSnapshot(
    val loggedIn: Boolean,
    val mode: AreaMode,
    val effectiveArea: String,
    val todayCount: Int,
    val overdueCount: Int,
    val tasks: List<TaskDto>,
    val lastUpdatedMillis: Long
)
