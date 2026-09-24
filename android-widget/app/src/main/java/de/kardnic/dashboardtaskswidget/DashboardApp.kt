package de.kardnic.dashboardtaskswidget

import android.app.Application

class DashboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SupabaseProvider.client
    }
}
