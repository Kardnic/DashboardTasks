package de.kardnic.dashboardtaskswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class DashboardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetUpdateWorker.enqueue(context, replace = true)
        schedulePeriodicUpdates(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicUpdates(context)
        WidgetUpdateWorker.enqueue(context, replace = true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> WidgetUpdateWorker.enqueue(context, replace = true)
            ACTION_AREA_ALL -> {
                WidgetPrefs.setArea(context, WidgetPrefs.AREA_ALL)
                WidgetUpdateWorker.enqueue(context, replace = true)
            }
            ACTION_AREA_WORK -> {
                WidgetPrefs.setArea(context, WidgetPrefs.AREA_WORK)
                WidgetUpdateWorker.enqueue(context, replace = true)
            }
            ACTION_AREA_PRIVATE -> {
                WidgetPrefs.setArea(context, WidgetPrefs.AREA_PRIVATE)
                WidgetUpdateWorker.enqueue(context, replace = true)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "de.kardnic.dashboardtaskswidget.REFRESH"
        const val ACTION_AREA_ALL = "de.kardnic.dashboardtaskswidget.AREA_ALL"
        const val ACTION_AREA_WORK = "de.kardnic.dashboardtaskswidget.AREA_WORK"
        const val ACTION_AREA_PRIVATE = "de.kardnic.dashboardtaskswidget.AREA_PRIVATE"
        private const val PERIODIC_WORK = "dashboard-widget-periodic"

        fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, DashboardWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun schedulePeriodicUpdates(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                30,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
