package de.kardnic.dashboardtaskswidget

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseProvider {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_KEY
        ) {
            install(Auth) {
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
        }
    }

    suspend fun ensureSessionReady(): Boolean {
        client.auth.awaitInitialization()
        val session = client.auth.currentSessionOrNull() ?: return false
        return runCatching {
            client.auth.refreshCurrentSession()
            true
        }.getOrElse {
            session.accessToken.isNotBlank()
        }
    }
}
