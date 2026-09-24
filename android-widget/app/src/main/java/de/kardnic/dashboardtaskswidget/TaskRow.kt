package de.kardnic.dashboardtaskswidget

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskRow(
    val id: String,
    val title: String,
    val area: String? = null,
    val category: String? = null,
    val priority: String = "normal",
    @SerialName("due_at") val dueAt: String? = null
)
