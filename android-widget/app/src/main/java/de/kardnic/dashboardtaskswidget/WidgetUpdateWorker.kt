package de.kardnic.dashboardtaskswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(applicationContext, DashboardWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return Result.success()

        if (!SupabaseProvider.ensureSessionReady()) {
            ids.forEach { manager.updateAppWidget(it, signedOutViews()) }
            return Result.success()
        }

        return runCatching {
            val client = SupabaseProvider.client
            val (currentLevel, nextLevel) = client.auth.mfa.getAuthenticatorAssuranceLevel()
            if (currentLevel != nextLevel) {
                ids.forEach { manager.updateAppWidget(it, mfaRequiredViews()) }
                return Result.success()
            }

            val zone = ZoneId.systemDefault()
            val tomorrow = ZonedDateTime.now(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toString()

            val selectedArea = WidgetPrefs.getArea(applicationContext)
            val response = client.from("tasks").select(
                columns = Columns.list("id", "title", "area", "category", "priority", "due_at")
            ) {
                filter {
                    eq("completed", false)
                    eq("waiting_for", false)
                    lt("due_at", tomorrow)
                    when (selectedArea) {
                        WidgetPrefs.AREA_WORK -> eq("area", "Arbeit")
                        WidgetPrefs.AREA_PRIVATE -> eq("area", "Privat")
                        else -> Unit
                    }
                }
            }

            val tasks = response.decodeList<TaskRow>()
                .filter { it.dueAt != null }
                .sortedWith(
                    compareBy<TaskRow> { priorityRank(it.priority) }
                        .thenBy { it.dueAt }
                )
                .take(4)

            ids.forEach { manager.updateAppWidget(it, taskViews(tasks, selectedArea)) }
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    private fun signedOutViews(): RemoteViews =
        baseViews().apply {
            setTextViewText(R.id.widgetStatus, applicationContext.getString(R.string.widget_sign_in))
            hideTasks()
            setOnClickPendingIntent(R.id.widgetRoot, openCompanionIntent(1))
        }

    private fun mfaRequiredViews(): RemoteViews =
        baseViews().apply {
            setTextViewText(R.id.widgetStatus, applicationContext.getString(R.string.widget_mfa_required))
            hideTasks()
            setOnClickPendingIntent(R.id.widgetRoot, openCompanionIntent(2))
        }

    private fun taskViews(tasks: List<TaskRow>, selectedArea: String): RemoteViews =
        baseViews().apply {
            setTextViewText(
                R.id.widgetStatus,
                if (tasks.isEmpty()) applicationContext.getString(R.string.widget_no_tasks)
                else applicationContext.getString(R.string.widget_updated)
            )

            setTextColor(
                R.id.filterAll,
                if (selectedArea == WidgetPrefs.AREA_ALL) Color.WHITE else Color.LTGRAY
            )
            setTextColor(
                R.id.filterWork,
                if (selectedArea == WidgetPrefs.AREA_WORK) Color.WHITE else Color.LTGRAY
            )
            setTextColor(
                R.id.filterPrivate,
                if (selectedArea == WidgetPrefs.AREA_PRIVATE) Color.WHITE else Color.LTGRAY
            )

            val rowIds = intArrayOf(R.id.task1, R.id.task2, R.id.task3, R.id.task4)
            rowIds.forEachIndexed { index, viewId ->
                val task = tasks.getOrNull(index)
                if (task == null) {
                    setViewVisibility(viewId, View.GONE)
                } else {
                    setViewVisibility(viewId, View.VISIBLE)
                    val prefix = if (task.area == "Arbeit") "💼" else "🏠"
                    val line = prefix + " " + task.title + "\n" + dueLabel(task.dueAt) + " · " + task.priority
                    setTextViewText(viewId, line)
                    setOnClickPendingIntent(viewId, openDashboardIntent(20 + index))
                }
            }
        }

    private fun baseViews(): RemoteViews =
        RemoteViews(applicationContext.packageName, R.layout.widget_dashboard).apply {
            setOnClickPendingIntent(
                R.id.refreshButton,
                DashboardWidgetProvider.actionIntent(
                    applicationContext,
                    DashboardWidgetProvider.ACTION_REFRESH,
                    10
                )
            )
            setOnClickPendingIntent(
                R.id.filterAll,
                DashboardWidgetProvider.actionIntent(
                    applicationContext,
                    DashboardWidgetProvider.ACTION_AREA_ALL,
                    11
                )
            )
            setOnClickPendingIntent(
                R.id.filterWork,
                DashboardWidgetProvider.actionIntent(
                    applicationContext,
                    DashboardWidgetProvider.ACTION_AREA_WORK,
                    12
                )
            )
            setOnClickPendingIntent(
                R.id.filterPrivate,
                DashboardWidgetProvider.actionIntent(
                    applicationContext,
                    DashboardWidgetProvider.ACTION_AREA_PRIVATE,
                    13
                )
            )
            setOnClickPendingIntent(R.id.addButton, openDashboardIntent(14))
            setOnClickPendingIntent(R.id.widgetTitle, openCompanionIntent(15))
        }

    private fun RemoteViews.hideTasks() {
        intArrayOf(R.id.task1, R.id.task2, R.id.task3, R.id.task4)
            .forEach { setViewVisibility(it, View.GONE) }
    }

    private fun openCompanionIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            requestCode,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openDashboardIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            requestCode,
            Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DASHBOARD_URL)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun dueLabel(value: String?): String {
        if (value == null) return "Inbox"
        return runCatching {
            val zone = ZoneId.systemDefault()
            val due = Instant.parse(value).atZone(zone)
            val today = ZonedDateTime.now(zone).toLocalDate()
            when {
                due.toLocalDate().isBefore(today) -> "Überfällig"
                due.hour == 12 && due.minute == 0 -> "Heute"
                else -> String.format("Heute %02d:%02d", due.hour, due.minute)
            }
        }.getOrDefault("Heute")
    }

    private fun priorityRank(priority: String): Int =
        when (priority) {
            "hoch" -> 0
            "niedrig" -> 2
            else -> 1
        }

    companion object {
        private const val UNIQUE_WORK = "dashboard-widget-refresh"

        fun enqueue(context: Context, replace: Boolean) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
