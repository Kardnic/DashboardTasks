package de.kardnic.dashboardtaskswidget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    val apkUrl: String?
)

object AppUpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/Kardnic/DashboardTasks/releases/latest"

    suspend fun check(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DashboardTasks-Android/${BuildConfig.VERSION_NAME}")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                return@withContext null
            }

            val tag = json.optString("tag_name").removePrefix("v").trim()
            if (tag.isBlank() || !isNewer(tag, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }

            val releaseUrl = json.optString("html_url").ifBlank {
                "https://github.com/Kardnic/DashboardTasks/releases"
            }

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        if (apkUrl != null) break
                    }
                }
            }

            AppUpdateInfo(
                versionName = tag,
                releaseUrl = releaseUrl,
                apkUrl = apkUrl
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = numericParts(remote)
        val currentParts = numericParts(current)
        val max = maxOf(remoteParts.size, currentParts.size)

        for (index in 0 until max) {
            val r = remoteParts.getOrElse(index) { 0 }
            val c = currentParts.getOrElse(index) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun numericParts(version: String): List<Int> =
        version.substringBefore('-')
            .split('.')
            .map { part -> part.toIntOrNull() ?: 0 }
}
