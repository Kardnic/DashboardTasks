package de.kardnic.dashboardtasks.widget

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.decodeList
import io.github.jan.supabase.postgrest.from

class TaskRepository(private val context: Context) {
    private val client = SupabaseProvider.client
    private val widgetPreferences = WidgetPreferences(context)

    suspend fun ensureSession(): Boolean {
        client.auth.awaitInitialization()

        if (client.auth.currentSessionOrNull() == null) {
            runCatching { client.auth.loadFromStorage(autoRefresh = true) }
        }

        return client.auth.currentSessionOrNull() != null
    }

    suspend fun refresh(): Result<List<TaskDto>> {
        if (!ensureSession()) {
            widgetPreferences.markLoggedOut()
            return Result.failure(IllegalStateException("Nicht angemeldet"))
        }

        return runCatching {
            val tasks = client.from("tasks").select {
                filter {
                    eq("completed", false)
                }
            }.decodeList<TaskDto>()

            widgetPreferences.saveTasks(tasks)
            tasks
        }
    }

    suspend fun completeTask(taskId: String): Result<Unit> {
        if (!ensureSession()) {
            widgetPreferences.markLoggedOut()
            return Result.failure(IllegalStateException("Nicht angemeldet"))
        }

        return runCatching {
            client.from("tasks").update({
                set("completed", true)
            }) {
                filter {
                    eq("id", taskId)
                }
            }
            refresh().getOrThrow()
        }
    }
}
