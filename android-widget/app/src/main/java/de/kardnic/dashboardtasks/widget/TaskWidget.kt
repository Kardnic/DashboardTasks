package de.kardnic.dashboardtasks.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TaskIdKey = ActionParameters.Key<String>("task_id")

class TaskWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 260.dp),
            DpSize(300.dp, 340.dp),
            DpSize(360.dp, 430.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetPreferences(context).snapshot(maxTasks = 7)
        provideContent {
            WidgetContent(snapshot)
        }
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}

@Composable
private fun WidgetContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    val maxTasks = when {
        size.height >= 400.dp -> 7
        size.height >= 320.dp -> 5
        else -> 3
    }
    val tasks = snapshot.tasks.take(maxTasks)
    val accent = if (snapshot.effectiveArea == "Arbeit") R.color.widget_work else R.color.widget_private

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_bg)
            .cornerRadius(22.dp)
            .appWidgetBackground()
            .padding(14.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "Aufgaben",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_text),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = areaLabel(snapshot),
                    modifier = GlanceModifier.clickable(actionRunCallback<CycleAreaModeAction>()),
                    style = TextStyle(
                        color = ColorProvider(accent),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            Text(
                text = "↻",
                modifier = GlanceModifier
                    .padding(8.dp)
                    .clickable(actionRunCallback<RefreshTaskWidgetAction>()),
                style = TextStyle(
                    color = ColorProvider(R.color.widget_accent),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(GlanceModifier.height(10.dp))

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StatCard(
                label = "Heute",
                value = snapshot.todayCount.toString(),
                modifier = GlanceModifier.defaultWeight()
            )
            Spacer(GlanceModifier.width(8.dp))
            StatCard(
                label = "Überfällig",
                value = snapshot.overdueCount.toString(),
                modifier = GlanceModifier.defaultWeight(),
                danger = snapshot.overdueCount > 0
            )
        }

        Spacer(GlanceModifier.height(10.dp))

        if (!snapshot.loggedIn) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(R.color.widget_surface)
                    .cornerRadius(14.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                Text(
                    text = "Anmeldung erforderlich",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_text),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                )
                Text(
                    text = "Tippen, um die Begleit-App zu öffnen.",
                    style = TextStyle(color = ColorProvider(R.color.widget_muted), fontSize = 12.sp)
                )
            }
        } else if (tasks.isEmpty()) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(R.color.widget_surface)
                    .cornerRadius(14.dp)
                    .padding(12.dp)
            ) {
                Text(
                    text = "Keine offenen Aufgaben",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_text),
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = if (snapshot.effectiveArea == "Arbeit") "Im Arbeitsbereich ist alles erledigt." else "Im Privatbereich ist alles erledigt.",
                    style = TextStyle(color = ColorProvider(R.color.widget_muted), fontSize = 12.sp)
                )
            }
        } else {
            tasks.forEachIndexed { index, task ->
                TaskRow(task)
                if (index != tasks.lastIndex) Spacer(GlanceModifier.height(5.dp))
            }
        }

        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.height(8.dp))

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(R.color.widget_surface)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "＋",
                style = TextStyle(
                    color = ColorProvider(R.color.widget_accent),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = "Aufgabe / Einstellungen",
                style = TextStyle(
                    color = ColorProvider(R.color.widget_text),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: GlanceModifier, danger: Boolean = false) {
    Column(
        modifier = modifier
            .background(R.color.widget_surface)
            .cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = value,
            style = TextStyle(
                color = ColorProvider(if (danger) R.color.widget_danger else R.color.widget_text),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        )
        Text(
            text = label,
            style = TextStyle(color = ColorProvider(R.color.widget_muted), fontSize = 11.sp)
        )
    }
}

@Composable
private fun TaskRow(task: TaskDto) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(R.color.widget_surface)
            .cornerRadius(12.dp)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "○",
            modifier = GlanceModifier
                .padding(end = 8.dp)
                .clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskIdKey to task.id))),
            style = TextStyle(
                color = ColorProvider(R.color.widget_accent),
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        )
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Text(
                text = task.title,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(R.color.widget_text),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            )
            Text(
                text = taskMeta(task),
                maxLines = 1,
                style = TextStyle(color = ColorProvider(R.color.widget_muted), fontSize = 10.sp)
            )
        }
    }
}

private fun areaLabel(snapshot: WidgetSnapshot): String {
    val prefix = when (snapshot.effectiveArea) {
        "Arbeit" -> "💼 Arbeit"
        else -> "🏠 Privat"
    }
    return when (snapshot.mode) {
        AreaMode.AUTO -> prefix + " · Auto"
        AreaMode.WORK -> "💼 Arbeit · Manuell"
        AreaMode.PRIVATE -> "🏠 Privat · Manuell"
    }
}

private fun taskMeta(task: TaskDto): String {
    val priority = if (task.priority == "hoch") " · Hoch" else ""
    return dueLabel(task.dueAt) + priority
}

private fun dueLabel(value: String?): String {
    if (value.isNullOrBlank()) return "Inbox"
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .getOrNull() ?: return "Termin"
    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date.isBefore(today) -> "Überfällig"
        date == today -> "Heute"
        date == today.plusDays(1) -> "Morgen"
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM."))
    }
}

class CycleAreaModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetPreferences(context).cycleMode()
        TaskWidget().updateAll(context)
    }
}

class RefreshTaskWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetSyncScheduler.refreshNow(context)
        TaskWidget().update(context, glanceId)
    }
}

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TaskIdKey] ?: return
        TaskRepository(context).completeTask(taskId)
        TaskWidget().updateAll(context)
    }
}
