package de.kardnic.dashboardtasks.widget

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DashboardApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Force singleton initialization while the application context is available.
        SupabaseProvider.client
        WidgetSyncScheduler.schedule(this)

        appScope.launch {
            runCatching {
                SupabaseProvider.client.auth.awaitInitialization()
                if (SupabaseProvider.client.auth.currentSessionOrNull() == null) {
                    SupabaseProvider.client.auth.loadFromStorage(autoRefresh = true)
                }
                if (SupabaseProvider.client.auth.currentSessionOrNull() != null) {
                    WidgetPreferences(this@DashboardApplication).setLoggedIn(true)
                    TaskRepository(this@DashboardApplication).refresh()
                }
                TaskWidget().updateAll(this@DashboardApplication)
            }
        }
    }
}
