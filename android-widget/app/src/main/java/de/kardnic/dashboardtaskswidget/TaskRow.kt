package de.kardnic.dashboardtaskswidget

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskRow(
    val id: String,
    val title: String,
    val description: String? = null,
    val area: String? = null,
    val category: String? = null,
    val priority: String = "normal",
    @SerialName("due_at") val dueAt: String? = null,
    val completed: Boolean = false,
    @SerialName("waiting_for") val waitingFor: Boolean = false,
    val recurrence: String = "none",
    @SerialName("reminder_at") val reminderAt: String? = null,
    @SerialName("next_recurrence_created") val nextRecurrenceCreated: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class TaskInsert(
    val title: String,
    val description: String? = null,
    val category: String = "Sonstiges",
    val area: String = "Privat",
    val priority: String = "normal",
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("waiting_for") val waitingFor: Boolean = false,
    val recurrence: String = "none",
    @SerialName("reminder_at") val reminderAt: String? = null,
    val source: String = "text"
)
