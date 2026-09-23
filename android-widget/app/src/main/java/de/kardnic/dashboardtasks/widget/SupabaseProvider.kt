package de.kardnic.dashboardtasks.widget

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseProvider {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY
    ) {
        install(Auth) {
            alwaysAutoRefresh = true
            autoLoadFromStorage = true
            autoSaveToStorage = true
            enableLifecycleCallbacks = false
        }
        install(Postgrest) {
            requireValidSession = true
        }
    }
}
