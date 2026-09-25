package de.kardnic.dashboardtaskswidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
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
    private lateinit var workSection: LinearLayout
    private lateinit var privateSection: LinearLayout
    private lateinit var workHeader: TextView
    private lateinit var privateHeader: TextView
    private lateinit var locationStatusText: TextView
    private lateinit var locationModeAutoButton: Button
    private lateinit var locationModeWorkButton: Button
    private lateinit var locationModePrivateButton: Button
    private lateinit var workRadiusInput: EditText
    private lateinit var setWorkLocationButton: Button
    private lateinit var clearWorkLocationButton: Button

    private lateinit var statToday: TextView
    private lateinit var statOverdue: TextView
    private lateinit var statInbox: TextView
    private lateinit var statWaiting: TextView
    private lateinit var statWeek: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var updateAppButton: Button

    private var tasks: List<TaskRow> = emptyList()
    private var availableUpdate: AppUpdateInfo? = null
    private var activeFilter = "today"
    private var loadingDashboard = false

    private val locationPrefs by lazy {
        getSharedPreferences("location_context", Context.MODE_PRIVATE)
    }
    private var detectedArea: String? = null
    private var pendingLocationAction = LOCATION_ACTION_CHECK
    private val locationHandler = Handler(Looper.getMainLooper())
    private var locationListener: LocationListener? = null
    private var locationTimeout: Runnable? = null

    private val updatePrefs by lazy {
        getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    }
    private var updateReceiverRegistered = false
    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (completedId == pendingUpdateDownloadId()) {
                resumePendingUpdateInstall(openSettingsIfNeeded = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        updateTodayLabel()
        registerUpdateDownloadReceiver()

        lifecycleScope.launch {
            statusText.text = getString(R.string.checking_session)
            client.auth.awaitInitialization()
            if (client.auth.currentSessionOrNull() == null) showLogin() else showAfterFirstFactor()
        }
    }

    override fun onResume() {
        super.onResume()
        resumePendingUpdateInstall(openSettingsIfNeeded = false)
        if (::loggedInBox.isInitialized && loggedInBox.visibility == View.VISIBLE) {
            lifecycleScope.launch { loadDashboard(showStatus = false) }
            checkWorkLocation(force = false)
        }
    }

    override fun onDestroy() {
        if (updateReceiverRegistered) {
            runCatching { unregisterReceiver(updateDownloadReceiver) }
            updateReceiverRegistered = false
        }
        stopLocationRequest()
        super.onDestroy()
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
        workSection = findViewById(R.id.workSection)
        privateSection = findViewById(R.id.privateSection)
        workHeader = findViewById(R.id.workHeader)
        privateHeader = findViewById(R.id.privateHeader)
        locationStatusText = findViewById(R.id.locationStatusText)
        locationModeAutoButton = findViewById(R.id.locationModeAutoButton)
        locationModeWorkButton = findViewById(R.id.locationModeWorkButton)
        locationModePrivateButton = findViewById(R.id.locationModePrivateButton)
        workRadiusInput = findViewById(R.id.workRadiusInput)
        setWorkLocationButton = findViewById(R.id.setWorkLocationButton)
        clearWorkLocationButton = findViewById(R.id.clearWorkLocationButton)
        readWorkLocation()?.let { workRadiusInput.setText(it.radius.toString()) }
        statToday = findViewById(R.id.statToday)
        statOverdue = findViewById(R.id.statOverdue)
        statInbox = findViewById(R.id.statInbox)
        statWaiting = findViewById(R.id.statWaiting)
        statWeek = findViewById(R.id.statWeek)
        updateStatusText = findViewById(R.id.updateStatusText)
        updateAppButton = findViewById(R.id.updateAppButton)
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

        locationModeAutoButton.setOnClickListener { setAreaMode(AREA_MODE_AUTO) }
        locationModeWorkButton.setOnClickListener { setAreaMode(AREA_MODE_WORK) }
        locationModePrivateButton.setOnClickListener { setAreaMode(AREA_MODE_PRIVATE) }
        setWorkLocationButton.setOnClickListener { requestLocationPermission(LOCATION_ACTION_SAVE) }
        clearWorkLocationButton.setOnClickListener {
            clearWorkLocation()
            detectedArea = null
            applyAreaPreference()
        }
        workRadiusInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateSavedRadius()
        }

        updateAppButton.setOnClickListener {
            if (pendingUpdateDownloadId() >= 0L) {
                resumePendingUpdateInstall(openSettingsIfNeeded = true)
                return@setOnClickListener
            }

            val update = availableUpdate
            if (update != null) {
                downloadAndInstallUpdate(update)
            } else {
                checkForUpdates(showCurrent = true)
            }
        }

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
        applyAreaPreference()
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
        applyAreaPreference()
        checkWorkLocation(force = false)
        checkForUpdates(showCurrent = false)
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
        applyAreaPreference()
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

    private data class WorkLocation(
        val latitude: Double,
        val longitude: Double,
        val radius: Int
    )

    private fun currentAreaMode(): String =
        locationPrefs.getString(PREF_AREA_MODE, AREA_MODE_AUTO) ?: AREA_MODE_AUTO

    private fun setAreaMode(mode: String) {
        val normalized = if (mode in setOf(AREA_MODE_AUTO, AREA_MODE_WORK, AREA_MODE_PRIVATE)) mode else AREA_MODE_AUTO
        locationPrefs.edit().putString(PREF_AREA_MODE, normalized).apply()
        applyAreaPreference()
        if (normalized == AREA_MODE_AUTO) checkWorkLocation(force = true)
    }

    private fun readWorkLocation(): WorkLocation? {
        if (!locationPrefs.contains(PREF_WORK_LAT) || !locationPrefs.contains(PREF_WORK_LON)) return null
        val lat = java.lang.Double.longBitsToDouble(locationPrefs.getLong(PREF_WORK_LAT, 0L))
        val lon = java.lang.Double.longBitsToDouble(locationPrefs.getLong(PREF_WORK_LON, 0L))
        val radius = locationPrefs.getInt(PREF_WORK_RADIUS, DEFAULT_WORK_RADIUS)
        if (!lat.isFinite() || !lon.isFinite()) return null
        return WorkLocation(lat, lon, radius)
    }

    private fun saveWorkLocation(location: Location) {
        val radius = requestedRadius()
        val coarseLat = kotlin.math.round(location.latitude * 10_000.0) / 10_000.0
        val coarseLon = kotlin.math.round(location.longitude * 10_000.0) / 10_000.0
        locationPrefs.edit()
            .putLong(PREF_WORK_LAT, java.lang.Double.doubleToRawLongBits(coarseLat))
            .putLong(PREF_WORK_LON, java.lang.Double.doubleToRawLongBits(coarseLon))
            .putInt(PREF_WORK_RADIUS, radius)
            .putString(PREF_AREA_MODE, AREA_MODE_AUTO)
            .apply()
        detectedArea = AREA_MODE_WORK
        workRadiusInput.setText(radius.toString())
        applyAreaPreference()
        locationStatusText.text = "📍 Arbeitsort gespeichert · Automatik ist aktiv."
    }

    private fun clearWorkLocation() {
        locationPrefs.edit()
            .remove(PREF_WORK_LAT)
            .remove(PREF_WORK_LON)
            .remove(PREF_WORK_RADIUS)
            .apply()
        locationStatusText.text = "Gespeicherter Arbeitsort wurde gelöscht."
    }

    private fun requestedRadius(): Int =
        (workRadiusInput.text.toString().toIntOrNull() ?: DEFAULT_WORK_RADIUS).coerceIn(100, 2000)

    private fun updateSavedRadius() {
        val saved = readWorkLocation() ?: return
        val radius = requestedRadius()
        locationPrefs.edit().putInt(PREF_WORK_RADIUS, radius).apply()
        workRadiusInput.setText(radius.toString())
        if (currentAreaMode() == AREA_MODE_AUTO) checkWorkLocation(force = true)
    }

    private fun applyAreaPreference() {
        if (!::locationStatusText.isInitialized) return
        val mode = currentAreaMode()
        locationModeAutoButton.isEnabled = mode != AREA_MODE_AUTO
        locationModeWorkButton.isEnabled = mode != AREA_MODE_WORK
        locationModePrivateButton.isEnabled = mode != AREA_MODE_PRIVATE

        val preferred = when (mode) {
            AREA_MODE_WORK -> AREA_MODE_WORK
            AREA_MODE_PRIVATE -> AREA_MODE_PRIVATE
            else -> detectedArea
        }

        workSection.setBackgroundColor(getColor(if (preferred == AREA_MODE_WORK) R.color.work_preferred_background else R.color.card_background))
        privateSection.setBackgroundColor(getColor(if (preferred == AREA_MODE_PRIVATE) R.color.private_preferred_background else R.color.card_background))
        workHeader.text = workHeader.text.toString().removePrefix("⭐ ").let { if (preferred == AREA_MODE_WORK) "⭐ $it" else it }
        privateHeader.text = privateHeader.text.toString().removePrefix("⭐ ").let { if (preferred == AREA_MODE_PRIVATE) "⭐ $it" else it }
        reorderAreaSections(preferred)

        locationStatusText.text = when (mode) {
            AREA_MODE_WORK -> "Manuell auf Arbeit gestellt."
            AREA_MODE_PRIVATE -> "Manuell auf Privat gestellt."
            else -> when {
                readWorkLocation() == null -> "Arbeitsort noch nicht festgelegt. Tippe auf „Arbeitsort hier festlegen“."
                detectedArea == AREA_MODE_WORK -> "📍 Arbeitsort erkannt · Arbeit wird bevorzugt angezeigt."
                detectedArea == AREA_MODE_PRIVATE -> "📍 Nicht am Arbeitsort · Privat wird bevorzugt angezeigt."
                else -> "Arbeitsort gespeichert · Standort wird beim Öffnen geprüft."
            }
        }
    }

    private fun reorderAreaSections(preferred: String?) {
        val parent = workSection.parent as? LinearLayout ?: return
        val workIndex = parent.indexOfChild(workSection)
        val privateIndex = parent.indexOfChild(privateSection)
        if (workIndex < 0 || privateIndex < 0) return

        if (preferred == AREA_MODE_PRIVATE && privateIndex > workIndex) {
            parent.removeView(privateSection)
            parent.addView(privateSection, workIndex)
        } else if (preferred != AREA_MODE_PRIVATE && workIndex > privateIndex) {
            parent.removeView(workSection)
            parent.addView(workSection, privateIndex)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission(action: String) {
        pendingLocationAction = action
        if (hasLocationPermission()) {
            requestCurrentLocation(saveAsWork = action == LOCATION_ACTION_SAVE)
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            requestCurrentLocation(saveAsWork = pendingLocationAction == LOCATION_ACTION_SAVE)
        } else {
            locationStatusText.text = "Standortzugriff nicht erlaubt · manuelle Umschaltung bleibt verfügbar."
            applyAreaPreference()
        }
    }

    private fun checkWorkLocation(force: Boolean) {
        if (currentAreaMode() != AREA_MODE_AUTO) {
            applyAreaPreference()
            return
        }
        if (readWorkLocation() == null) {
            detectedArea = null
            applyAreaPreference()
            return
        }
        if (!hasLocationPermission()) {
            if (force) requestLocationPermission(LOCATION_ACTION_CHECK)
            else {
                detectedArea = null
                applyAreaPreference()
            }
            return
        }
        requestCurrentLocation(saveAsWork = false)
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(saveAsWork: Boolean) {
        stopLocationRequest()
        val manager = getSystemService(LocationManager::class.java)
        val preferredProviders = if (saveAsWork) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        val provider = preferredProviders.firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (provider == null) {
            locationStatusText.text = "Standortdienste sind deaktiviert."
            return
        }

        setWorkLocationButton.isEnabled = false
        if (saveAsWork) locationStatusText.text = "Arbeitsort wird ermittelt …"
        else locationStatusText.text = "Standort wird geprüft …"

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                stopLocationRequest()
                setWorkLocationButton.isEnabled = true
                if (saveAsWork) saveWorkLocation(location) else evaluateCurrentLocation(location)
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        locationListener = listener

        runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
            .onFailure {
                stopLocationRequest()
                setWorkLocationButton.isEnabled = true
                locationStatusText.text = "Standort konnte nicht ermittelt werden."
            }

        val timeout = Runnable {
            stopLocationRequest()
            setWorkLocationButton.isEnabled = true
            locationStatusText.text = "Standortabfrage hat zu lange gedauert."
        }
        locationTimeout = timeout
        locationHandler.postDelayed(timeout, 15_000L)
    }

    @SuppressLint("MissingPermission")
    private fun stopLocationRequest() {
        locationTimeout?.let { locationHandler.removeCallbacks(it) }
        locationTimeout = null
        val listener = locationListener ?: return
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        locationListener = null
    }

    private fun evaluateCurrentLocation(location: Location) {
        val work = readWorkLocation() ?: run {
            detectedArea = null
            applyAreaPreference()
            return
        }
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, work.latitude, work.longitude, result)
        val tolerance = location.accuracy.coerceAtMost(100f)
        detectedArea = if (result[0] <= work.radius + tolerance) AREA_MODE_WORK else AREA_MODE_PRIVATE
        applyAreaPreference()
    }

    private fun registerUpdateDownloadReceiver() {
        if (updateReceiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateDownloadReceiver, filter)
        }
        updateReceiverRegistered = true
    }

    private fun pendingUpdateDownloadId(): Long =
        updatePrefs.getLong(PREF_UPDATE_DOWNLOAD_ID, -1L)

    private fun clearPendingUpdateDownload() {
        updatePrefs.edit()
            .remove(PREF_UPDATE_DOWNLOAD_ID)
            .remove(PREF_UPDATE_VERSION)
            .apply()
    }

    private fun downloadAndInstallUpdate(update: AppUpdateInfo) {
        val apkUrl = update.apkUrl
        if (apkUrl.isNullOrBlank()) {
            updateStatusText.text = "APK nicht direkt verfügbar. Release-Seite wird geöffnet."
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
            return
        }

        val fileName = "Aufgaben-Dashboard-${update.versionName}-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Aufgaben Dashboard ${update.versionName}")
            .setDescription("Update wird heruntergeladen …")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)

        val manager = getSystemService(DownloadManager::class.java)
        runCatching { manager.enqueue(request) }
            .onSuccess { downloadId ->
                updatePrefs.edit()
                    .putLong(PREF_UPDATE_DOWNLOAD_ID, downloadId)
                    .putString(PREF_UPDATE_VERSION, update.versionName)
                    .apply()
                updateStatusText.text = "Update ${update.versionName} wird heruntergeladen …"
                updateAppButton.text = "Download läuft …"
                updateAppButton.isEnabled = false
            }
            .onFailure {
                updateStatusText.text = "Update konnte nicht heruntergeladen werden."
                updateAppButton.text = "Update ${update.versionName} erneut versuchen"
                updateAppButton.isEnabled = true
            }
    }

    private fun resumePendingUpdateInstall(openSettingsIfNeeded: Boolean) {
        val downloadId = pendingUpdateDownloadId()
        if (downloadId < 0L || !::updateStatusText.isInitialized) return

        val manager = getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = runCatching { manager.query(query) }.getOrNull() ?: return

        cursor.use {
            if (!it.moveToFirst()) {
                clearPendingUpdateDownload()
                return
            }

            when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val version = updatePrefs.getString(PREF_UPDATE_VERSION, null)
                    if (packageManager.canRequestPackageInstalls()) {
                        installDownloadedUpdate(manager, downloadId)
                    } else {
                        updateStatusText.text =
                            "Update${version?.let { v -> " $v" } ?: ""} ist geladen. Installation aus dieser Quelle erlauben."
                        updateAppButton.text = "Installation erlauben"
                        updateAppButton.isEnabled = true
                        if (openSettingsIfNeeded) openUnknownSourcesSettings()
                    }
                }

                DownloadManager.STATUS_FAILED -> {
                    clearPendingUpdateDownload()
                    updateStatusText.text = "Der Update-Download ist fehlgeschlagen."
                    updateAppButton.text = "Update erneut suchen"
                    updateAppButton.isEnabled = true
                }

                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    val version = updatePrefs.getString(PREF_UPDATE_VERSION, null)
                    updateStatusText.text =
                        "Update${version?.let { v -> " $v" } ?: ""} wird heruntergeladen …"
                    updateAppButton.text = "Download läuft …"
                    updateAppButton.isEnabled = false
                }
            }
        }
    }

    private fun installDownloadedUpdate(manager: DownloadManager, downloadId: Long) {
        val apkUri = manager.getUriForDownloadedFile(downloadId)
        if (apkUri == null) {
            clearPendingUpdateDownload()
            updateStatusText.text = "APK konnte nach dem Download nicht geöffnet werden."
            updateAppButton.text = "Update erneut suchen"
            updateAppButton.isEnabled = true
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching { startActivity(installIntent) }
            .onSuccess {
                clearPendingUpdateDownload()
                updateStatusText.text = "Android-Installation wurde geöffnet."
                updateAppButton.text = "Installation läuft …"
                updateAppButton.isEnabled = false
            }
            .onFailure {
                updateStatusText.text = "Installer konnte nicht geöffnet werden."
                updateAppButton.text = "Erneut versuchen"
                updateAppButton.isEnabled = true
            }
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
    }

    private fun checkForUpdates(showCurrent: Boolean) {
        if (pendingUpdateDownloadId() >= 0L) {
            resumePendingUpdateInstall(openSettingsIfNeeded = false)
            return
        }

        updateAppButton.isEnabled = false
        updateStatusText.text = "Suche nach neuer Version …"

        lifecycleScope.launch {
            runCatching { AppUpdateChecker.check() }
                .onSuccess { update ->
                    availableUpdate = update
                    if (update != null) {
                        updateStatusText.text =
                            "Update ${update.versionName} verfügbar · aktuell ${BuildConfig.VERSION_NAME}"
                        updateAppButton.text = "Update ${update.versionName} herunterladen"
                    } else {
                        updateStatusText.text =
                            if (showCurrent) "App ist aktuell · Version ${BuildConfig.VERSION_NAME}"
                            else "Version ${BuildConfig.VERSION_NAME}"
                        updateAppButton.text = "Nach Update suchen"
                    }
                }
                .onFailure {
                    availableUpdate = null
                    updateStatusText.text =
                        if (showCurrent) "Update-Prüfung derzeit nicht möglich."
                        else "Version ${BuildConfig.VERSION_NAME}"
                    updateAppButton.text = "Nach Update suchen"
                }
            updateAppButton.isEnabled = true
        }
    }

    private fun setBusy(busy: Boolean) {
        loginButton.isEnabled = !busy
        mfaButton.isEnabled = !busy
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val PREF_UPDATE_DOWNLOAD_ID = "update_download_id"
        const val PREF_UPDATE_VERSION = "update_version"
        const val PREF_WORK_LAT = "work_lat"
        const val PREF_WORK_LON = "work_lon"
        const val PREF_WORK_RADIUS = "work_radius"
        const val PREF_AREA_MODE = "area_mode"
        const val AREA_MODE_AUTO = "auto"
        const val AREA_MODE_WORK = "work"
        const val AREA_MODE_PRIVATE = "private"
        const val LOCATION_ACTION_CHECK = "check"
        const val LOCATION_ACTION_SAVE = "save"
        const val LOCATION_PERMISSION_REQUEST = 401
        const val DEFAULT_WORK_RADIUS = 300
    }
}
