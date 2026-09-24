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
                "reminder_at", "created_at"
            )
        ).decodeList<TaskRow>()
    }

    suspend fun addTask(task: TaskInsert) {
        check(SupabaseProvider.ensureSessionReady()) { "Nicht angemeldet" }
        client.from("tasks").insert(task)
        WidgetUpdateWorker.enqueue(context, replace = true)
    }

    suspend fun setCompleted(taskId: String, completed: Boolean) {
        check(SupabaseProvider.ensureSessionReady()) { "Nicht angemeldet" }
        client.from("tasks").update({
            set("completed", completed)
        }) {
            filter { eq("id", taskId) }
        }
        WidgetUpdateWorker.enqueue(context, replace = true)
    }

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
