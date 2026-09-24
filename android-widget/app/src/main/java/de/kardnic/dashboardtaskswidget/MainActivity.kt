package de.kardnic.dashboardtaskswidget

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val client get() = SupabaseProvider.client
    private val repository by lazy { DashboardRepository(this) }
    private val zone = ZoneId.systemDefault()

    private lateinit var loginBox: View
    private lateinit var mfaBox: View
    private lateinit var loggedInBox: View
    private lateinit var statusText: TextView
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var mfaInput: EditText
    private lateinit var loginButton: Button
    private lateinit var mfaButton: Button

    private lateinit var quickTaskInput: EditText
    private lateinit var quickPreviewText: TextView
    private lateinit var addQuickTaskButton: Button
    private lateinit var focusContainer: LinearLayout
    private lateinit var workTasksContainer: LinearLayout
    private lateinit var privateTasksContainer: LinearLayout
    private lateinit var workHeader: TextView
    private lateinit var privateHeader: TextView

    private lateinit var statToday: TextView
    private lateinit var statOverdue: TextView
    private lateinit var statInbox: TextView
    private lateinit var statWaiting: TextView
    private lateinit var statWeek: TextView

    private var tasks: List<TaskRow> = emptyList()
    private var activeFilter = "today"
    private var loadingDashboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        updateTodayLabel()

        lifecycleScope.launch {
            statusText.text = getString(R.string.checking_session)
            client.auth.awaitInitialization()
            if (client.auth.currentSessionOrNull() == null) showLogin() else showAfterFirstFactor()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::loggedInBox.isInitialized && loggedInBox.visibility == View.VISIBLE) {
            lifecycleScope.launch { loadDashboard(showStatus = false) }
        }
    }

    private fun bindViews() {
        loginBox = findViewById(R.id.loginBox)
        mfaBox = findViewById(R.id.mfaBox)
        loggedInBox = findViewById(R.id.loggedInBox)
        statusText = findViewById(R.id.statusText)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        mfaInput = findViewById(R.id.mfaInput)
        loginButton = findViewById(R.id.loginButton)
        mfaButton = findViewById(R.id.mfaButton)
        quickTaskInput = findViewById(R.id.quickTaskInput)
        quickPreviewText = findViewById(R.id.quickPreviewText)
        addQuickTaskButton = findViewById(R.id.addQuickTaskButton)
        focusContainer = findViewById(R.id.focusContainer)
        workTasksContainer = findViewById(R.id.workTasksContainer)
        privateTasksContainer = findViewById(R.id.privateTasksContainer)
        workHeader = findViewById(R.id.workHeader)
        privateHeader = findViewById(R.id.privateHeader)
        statToday = findViewById(R.id.statToday)
        statOverdue = findViewById(R.id.statOverdue)
        statInbox = findViewById(R.id.statInbox)
        statWaiting = findViewById(R.id.statWaiting)
        statWeek = findViewById(R.id.statWeek)
    }

    private fun bindActions() {
        loginButton.setOnClickListener { login() }
        mfaButton.setOnClickListener { verifyMfa() }

        quickTaskInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateQuickPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        addQuickTaskButton.setOnClickListener { addQuickTask() }

        filterButtons().forEach { (id, filter) ->
            findViewById<Button>(id).setOnClickListener {
                activeFilter = filter
                updateFilterButtons()
                renderDashboard()
            }
        }

        findViewById<Button>(R.id.refreshWidgetButton).setOnClickListener {
            lifecycleScope.launch {
                loadDashboard(showStatus = true)
                WidgetUpdateWorker.enqueue(this@MainActivity, replace = true)
            }
        }
        findViewById<Button>(R.id.openDashboardButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DASHBOARD_URL)))
        }
        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            lifecycleScope.launch {
                runCatching { client.auth.signOut() }
                tasks = emptyList()
                showLogin()
                WidgetUpdateWorker.enqueue(this@MainActivity, replace = true)
            }
        }
        updateFilterButtons()
    }

    private fun filterButtons() = mapOf(
        R.id.filterToday to "today",
        R.id.filterAll to "all",
        R.id.filterOverdue to "overdue",
        R.id.filterInbox to "inbox",
        R.id.filterWaiting to "waiting",
        R.id.filterWeek to "week",
        R.id.filterDone to "done"
    )

    private fun updateTodayLabel() {
        val text = LocalDate.now(zone).format(DateTimeFormatter.ofPattern("EEEE, dd. MMMM yyyy", Locale.GERMAN))
        findViewById<TextView>(R.id.todayText).text =
            text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.GERMAN) else it.toString() }
    }

    private fun login() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            statusText.text = getString(R.string.enter_credentials)
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                client.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
            }.onSuccess {
                passwordInput.text.clear()
                showAfterFirstFactor()
            }.onFailure {
                statusText.text = getString(R.string.login_failed)
                showLogin()
            }
            setBusy(false)
        }
    }

    private suspend fun showAfterFirstFactor() {
        val (currentLevel, nextLevel) = client.auth.mfa.getAuthenticatorAssuranceLevel()
        if (currentLevel != nextLevel) {
            loginBox.visibility = View.GONE
            mfaBox.visibility = View.VISIBLE
            loggedInBox.visibility = View.GONE
            statusText.text = getString(R.string.mfa_required)
        } else showLoggedIn()
    }

    private fun verifyMfa() {
        val code = mfaInput.text.toString().trim()
        if (!Regex("^\\d{6}$").matches(code)) {
            statusText.text = getString(R.string.invalid_mfa_code)
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                val factors = client.auth.mfa.retrieveFactorsForCurrentUser()
                val factor = factors.firstOrNull() ?: error("No verified MFA factor")
                client.auth.mfa.createChallengeAndVerify(factorId = factor.id, code = code)
            }.onSuccess {
                mfaInput.text.clear()
                showLoggedIn()
            }.onFailure {
                statusText.text = getString(R.string.mfa_failed)
            }
            setBusy(false)
        }
    }

    private fun showLogin() {
        loginBox.visibility = View.VISIBLE
        mfaBox.visibility = View.GONE
        loggedInBox.visibility = View.GONE
        statusText.text = getString(R.string.sign_in_hint)
    }

    private fun showLoggedIn() {
        loginBox.visibility = View.GONE
        mfaBox.visibility = View.GONE
        loggedInBox.visibility = View.VISIBLE
        statusText.text = "Dashboard wird geladen …"
        WidgetUpdateWorker.enqueue(this, replace = true)
        DashboardWidgetProvider.schedulePeriodicUpdates(this)
        lifecycleScope.launch { loadDashboard(showStatus = false) }
    }

    private suspend fun loadDashboard(showStatus: Boolean) {
        if (loadingDashboard) return
        loadingDashboard = true
        if (showStatus) statusText.text = "Aufgaben werden aktualisiert …"
        runCatching { repository.loadTasks() }
            .onSuccess {
                tasks = it
                renderDashboard()
                statusText.text = "Synchronisiert · ${tasks.count { task -> !task.completed }} Aufgaben offen"
            }
            .onFailure { statusText.text = "Aufgaben konnten nicht geladen werden." }
        loadingDashboard = false
    }

    private fun addQuickTask() {
        val raw = quickTaskInput.text.toString().trim()
        if (raw.isBlank()) {
            statusText.text = "Bitte zuerst eine Aufgabe eingeben."
            return
        }
        val parsed = SmartTaskParser.parse(raw)
        addQuickTaskButton.isEnabled = false
        statusText.text = "Aufgabe wird gespeichert …"
        lifecycleScope.launch {
            runCatching { repository.addTask(parsed) }
                .onSuccess {
                    quickTaskInput.text.clear()
                    quickPreviewText.text = ""
                    statusText.text = "Aufgabe gespeichert."
                    loadDashboard(showStatus = false)
                }
                .onFailure { statusText.text = "Aufgabe konnte nicht gespeichert werden." }
            addQuickTaskButton.isEnabled = true
        }
    }

    private fun updateQuickPreview() {
        val raw = quickTaskInput.text.toString().trim()
        if (raw.isBlank()) {
            quickPreviewText.text = ""
            return
        }
        val parsed = SmartTaskParser.parse(raw)
        val due = parsed.dueAt?.let { dueLabel(it) } ?: "Inbox"
        val recurrence = when (parsed.recurrence) {
            "daily" -> " · täglich"
            "weekly" -> " · wöchentlich"
            "monthly" -> " · monatlich"
            else -> ""
        }
        val waiting = if (parsed.waitingFor) " · Warten" else ""
        quickPreviewText.text =
            "Erkannt: ${if (parsed.area == "Arbeit") "💼 Arbeit" else "🏠 Privat"} · $due · ${parsed.priority}$recurrence$waiting"
    }

    private fun renderDashboard() {
        updateStats()
        renderFocus()
        val filtered = tasks.filter(::matchesFilter).sortedWith(
            compareBy<TaskRow> { priorityRank(it.priority) }.thenBy { it.dueAt ?: "9999" }
        )
        val work = filtered.filter { taskArea(it) == "Arbeit" }
        val privateTasks = filtered.filter { taskArea(it) == "Privat" }
        workHeader.text = "💼 Arbeit  ${work.size}"
        privateHeader.text = "🏠 Privat  ${privateTasks.size}"
        renderTaskList(workTasksContainer, work, "Keine Arbeitsaufgaben in dieser Ansicht.")
        renderTaskList(privateTasksContainer, privateTasks, "Keine privaten Aufgaben in dieser Ansicht.")
    }

    private fun updateStats() {
        statToday.text = "Heute\n${tasks.count(::isToday)}"
        statOverdue.text = "Überfällig\n${tasks.count(::isOverdue)}"
        statInbox.text = "Inbox\n${tasks.count { !it.completed && !it.waitingFor && it.dueAt == null }}"
        statWaiting.text = "Warten\n${tasks.count { !it.completed && it.waitingFor }}"
        statWeek.text = "7 Tage\n${tasks.count(::isWeek)}"
    }

    private fun renderFocus() {
        focusContainer.removeAllViews()
        val candidates = tasks
            .filter { !it.completed && !it.waitingFor && (isOverdueRaw(it) || isTodayRaw(it)) }
            .sortedWith(
                compareBy<TaskRow> { if (isOverdueRaw(it)) 0 else 10 }
                    .thenBy { priorityRank(it.priority) }
                    .thenBy { it.dueAt ?: "9999" }
            ).take(3)

        if (candidates.isEmpty()) {
            focusContainer.addView(simpleMutedText("Für heute ist aktuell nichts Dringendes offen."))
            return
        }

        candidates.forEachIndexed { index, task ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundColor(getColor(R.color.card_background))
            }
            box.addView(TextView(this).apply {
                text = "FOKUS ${index + 1}"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            })
            box.addView(TextView(this).apply {
                text = task.title
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = "${if (taskArea(task) == "Arbeit") "💼 Arbeit" else "🏠 Privat"} · ${dueLabel(task.dueAt)} · ${task.priority}"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            focusContainer.addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
    }

    private fun renderTaskList(container: LinearLayout, list: List<TaskRow>, emptyText: String) {
        container.removeAllViews()
        if (list.isEmpty()) {
            container.addView(simpleMutedText(emptyText))
            return
        }
        list.forEach { task ->
            container.addView(createTaskView(task), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun createTaskView(task: TaskRow): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundColor(getColor(R.color.app_background))
        }
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val check = CheckBox(this).apply {
            isChecked = task.completed
            setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    runCatching { repository.setCompleted(task, checked) }
                        .onSuccess { loadDashboard(showStatus = false) }
                        .onFailure {
                            statusText.text = "Status konnte nicht geändert werden."
                            isChecked = task.completed
                        }
                }
            }
        }
        val title = TextView(this).apply {
            text = task.title
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        firstRow.addView(check)
        firstRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(firstRow)

        val metaParts = mutableListOf<String>()
        task.category?.takeIf { it != "Sonstiges" && it != taskArea(task) }?.let(metaParts::add)
        metaParts.add(task.priority)
        metaParts.add(dueLabel(task.dueAt))
        if (task.waitingFor) metaParts.add("⏳ Warten")
        if (task.recurrence != "none") metaParts.add(when (task.recurrence) {
            "daily" -> "↻ täglich"
            "weekly" -> "↻ wöchentlich"
            "monthly" -> "↻ monatlich"
            else -> "↻"
        })

        root.addView(TextView(this).apply {
            text = metaParts.joinToString(" · ")
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(42), 0, 0, 0)
        })

        if (!task.description.isNullOrBlank()) {
            root.addView(TextView(this).apply {
                text = task.description
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(42), dp(4), 0, 0)
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(42), dp(5), 0, 0)
        }
        if (!task.completed) {
            actions.addView(Button(this).apply {
                text = if (task.waitingFor) "Aktivieren" else "Warten"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    lifecycleScope.launch {
                        runCatching { repository.setWaiting(task.id, !task.waitingFor) }
                            .onSuccess { loadDashboard(showStatus = false) }
                            .onFailure { statusText.text = "Aufgabe konnte nicht geändert werden." }
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        actions.addView(Button(this).apply {
            text = "Löschen"
            textSize = 11f
            isAllCaps = false
            setTextColor(getColor(R.color.danger))
            setOnClickListener { confirmDelete(task) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        root.addView(actions)
        return root
    }

    private fun confirmDelete(task: TaskRow) {
        AlertDialog.Builder(this)
            .setTitle("Aufgabe löschen?")
            .setMessage(task.title)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Löschen") { _, _ ->
                lifecycleScope.launch {
                    runCatching { repository.deleteTask(task.id) }
                        .onSuccess { loadDashboard(showStatus = false) }
                        .onFailure { statusText.text = "Aufgabe konnte nicht gelöscht werden." }
                }
            }.show()
    }

    private fun updateFilterButtons() {
        filterButtons().forEach { (id, filter) ->
            findViewById<Button>(id).isEnabled = activeFilter != filter
        }
    }

    private fun matchesFilter(task: TaskRow): Boolean = when (activeFilter) {
        "all" -> !task.completed
        "today" -> isToday(task)
        "overdue" -> isOverdue(task)
        "inbox" -> !task.completed && !task.waitingFor && task.dueAt == null
        "waiting" -> !task.completed && task.waitingFor
        "week" -> isWeek(task)
        "done" -> task.completed
        else -> true
    }

    private fun taskArea(task: TaskRow): String =
        if (task.area == "Arbeit" || task.category == "Arbeit") "Arbeit" else "Privat"

    private fun isTodayRaw(task: TaskRow): Boolean =
        !task.completed && task.dueAt != null && taskDate(task) == LocalDate.now(zone)

    private fun isToday(task: TaskRow): Boolean = !task.waitingFor && isTodayRaw(task)

    private fun isOverdueRaw(task: TaskRow): Boolean {
        if (task.completed || task.dueAt == null) return false
        val date = taskDate(task) ?: return false
        return date.isBefore(LocalDate.now(zone))
    }

    private fun isOverdue(task: TaskRow): Boolean = !task.waitingFor && isOverdueRaw(task)

    private fun isWeek(task: TaskRow): Boolean {
        if (task.completed || task.waitingFor || task.dueAt == null) return false
        val date = taskDate(task) ?: return false
        val today = LocalDate.now(zone)
        return !date.isBefore(today) && !date.isAfter(today.plusDays(7))
    }

    private fun taskDate(task: TaskRow): LocalDate? =
        task.dueAt?.let { parseInstant(it)?.atZone(zone)?.toLocalDate() }

    private fun dueLabel(value: String?): String {
        if (value.isNullOrBlank()) return "Inbox"
        val instant = parseInstant(value) ?: return "Termin"
        val dateTime = instant.atZone(zone)
        val today = LocalDate.now(zone)
        val date = dateTime.toLocalDate()
        val timeRelevant = !(dateTime.hour == 12 && dateTime.minute == 0)
        val dateLabel = when {
            date.isBefore(today) -> "Überfällig"
            date == today -> "Heute"
            date == today.plusDays(1) -> "Morgen"
            else -> date.format(DateTimeFormatter.ofPattern("dd.MM."))
        }
        return if (timeRelevant) "$dateLabel ${dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}" else dateLabel
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrNull()

    private fun priorityRank(priority: String): Int = when (priority) {
        "hoch" -> 0
        "normal" -> 1
        "niedrig" -> 2
        else -> 1
    }

    private fun simpleMutedText(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setBusy(busy: Boolean) {
        loginButton.isEnabled = !busy
        mfaButton.isEnabled = !busy
    }
}
