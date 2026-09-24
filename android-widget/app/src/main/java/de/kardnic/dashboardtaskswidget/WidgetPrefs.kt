package de.kardnic.dashboardtaskswidget

import android.content.Context

object WidgetPrefs {
    private const val PREFS = "widget_preferences"
    private const val KEY_AREA = "area"

    const val AREA_ALL = "all"
    const val AREA_WORK = "work"
    const val AREA_PRIVATE = "private"

    fun getArea(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AREA, AREA_ALL) ?: AREA_ALL

    fun setArea(context: Context, area: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AREA, area)
            .apply()
    }
}
