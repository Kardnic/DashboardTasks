package de.kardnic.dashboardtasks.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val prefs = WidgetPreferences(context)
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> prefs.setAutoArea("Arbeit")
            Geofence.GEOFENCE_TRANSITION_EXIT -> prefs.setAutoArea("Privat")
            else -> return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                TaskWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
