package de.kardnic.dashboardtaskswidget

import android.content.Context
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns

class DashboardRepository(private val context: Context) {
    private val client = SupabaseProvider.client

    suspend fun loadTasks(): List<TaskRow> {
        check(SupabaseProvider.ensureSessionReady()) { "Nicht angemeldet" }
        return client.from("tasks").select(
            columns = Columns.list(
                "id", "title", "description", "area", "category", "priority",
                "due_at", "completed", "waiting_for", "recurrence",
                "reminder_at", "next_recurrence_created", "created_at"
            )
        ).decodeList<TaskRow>()
    }

    suspend fun addTask(task: TaskInsert) {
        check(SupabaseProvider.ensureSessionReady()) { "Nicht angemeldet" }
        client.from("tasks").insert(task)
        WidgetUpdateWorker.enqueue(context, replace = true)
    }

    suspend fun setCompleted(task: TaskRow, completed: Boolean) {
        check(SupabaseProvider.ensureSessionReady()) { "Nicht angemeldet" }

        if (completed && !task.completed && task.recurrence != "none" && !task.nextRecurrenceCreated) {
            val next = nextRecurringTask(task)
            client.from("tasks").insert(next)
            client.from("tasks").update({
                set("completed", true)
                set("next_recurrence_created", true)
            }) {
                filter { eq("id", task.id) }
            }
        } else {
            client.from("tasks").update({
                set("completed", completed)
            }) {
                filter { eq("id", task.id) }
            }
        }

        WidgetUpdateWorker.enqueue(context, replace = true)
    }

    private fun nextRecurringTask(task: TaskRow): TaskInsert {
        val due = task.dueAt?.let { parseInstant(it) }
        val reminder = task.reminderAt?.let { parseInstant(it) }

        val nextDue = due?.let { instant ->
            val zoned = instant.atZone(java.time.ZoneId.systemDefault())
            when (task.recurrence) {
                "daily" -> zoned.plusDays(1)
                "weekly" -> zoned.plusWeeks(1)
                "monthly" -> zoned.plusMonths(1)
                else -> zoned
            }.toInstant()
        }

        val nextReminder = when {
            reminder != null && due != null && nextDue != null -> {
                val delta = java.time.Duration.between(reminder, due)
                nextDue.minus(delta)
            }
            reminder != null -> {
                val zoned = reminder.atZone(java.time.ZoneId.systemDefault())
                when (task.recurrence) {
                    "daily" -> zoned.plusDays(1)
                    "weekly" -> zoned.plusWeeks(1)
                    "monthly" -> zoned.plusMonths(1)
                    else -> zoned
                }.toInstant()
            }
            else -> null
        }

        return TaskInsert(
            title = task.title,
            description = task.description,
            category = task.category ?: "Sonstiges",
            area = task.area ?: "Privat",
            priority = task.priority,
            dueAt = nextDue?.toString(),
            waitingFor = false,
            recurrence = task.recurrence,
            reminderAt = nextReminder?.toString(),
            source = "text"
        )
    }

    private fun parseInstant(value: String): java.time.Instant =
        runCatching { java.time.Instant.parse(value) }
            .recoverCatching { java.time.OffsetDateTime.parse(value).toInstant() }
            .getOrThrow()

    suspend fun setWaiting(taskId: String, waiting: Boolean) {
        check(SupabaseProvider.ensureSessionReady()) { "Nicht angemeldet" }
        client.from("tasks").update({
            set("waiting_for", waiting)
        }) {
            filter { eq("id", taskId) }
        }
        WidgetUpdateWorker.enqueue(context, replace = true)
    }

    suspend fun deleteTask(taskId: String) {
        check(SupabaseProvider.ensureSessionReady()) { "Nicht angemeldet" }
        client.from("tasks").delete {
            filter { eq("id", taskId) }
        }
        WidgetUpdateWorker.enqueue(context, replace = true)
    }
}
