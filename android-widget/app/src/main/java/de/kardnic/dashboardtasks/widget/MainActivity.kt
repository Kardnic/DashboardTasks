package de.kardnic.dashboardtasks.widget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.glance.appwidget.updateAll
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client get() = SupabaseProvider.client
    private lateinit var prefs: WidgetPreferences

    private lateinit var status: TextView
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var loginButton: Button
    private lateinit var mfaCode: EditText
    private lateinit var mfaButton: Button
    private lateinit var setupBox: LinearLayout
    private lateinit var modeButton: Button
    private lateinit var radiusSpinner: Spinner

    private var locationActionPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = WidgetPreferences(this)
        setContentView(buildUi())
        initializeAuth()
    }

    override fun onResume() {
        super.onResume()
        prefs.workLocation()?.let { location -> GeofenceManager.register(this, location) }
        updateModeButton()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Aufgaben Widget"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "4×5 Startbildschirm-Widget für Samsung / Android"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(14))
        })

        status = TextView(this).apply {
            text = "Anmeldung wird geprüft …"
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(status)

        email = EditText(this).apply {
            hint = "E-Mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        root.addView(email, matchWidth())

        password = EditText(this).apply {
            hint = "Passwort"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(password, matchWidth())

        loginButton = Button(this).apply {
            text = "Anmelden"
            setOnClickListener { login() }
        }
        root.addView(loginButton, matchWidth())

        mfaCode = EditText(this).apply {
            hint = "6-stelliger Authenticator-Code"
            inputType = InputType.TYPE_CLASS_NUMBER
            visibility = View.GONE
            setSingleLine(true)
        }
        root.addView(mfaCode, matchWidth())

        mfaButton = Button(this).apply {
            text = "2FA bestätigen"
            visibility = View.GONE
            setOnClickListener { verifyMfa() }
        }
        root.addView(mfaButton, matchWidth())

        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(setupBox, matchWidth())

        setupBox.addView(sectionTitle("Widget"))
        setupBox.addView(Button(this).apply {
            text = "4×5 Widget zum Startbildschirm hinzufügen"
            setOnClickListener { pinWidget() }
        }, matchWidth())

        setupBox.addView(Button(this).apply {
            text = "Jetzt aktualisieren"
            setOnClickListener {
                WidgetSyncScheduler.refreshNow(this@MainActivity)
                status.text = "Widget-Aktualisierung gestartet."
            }
        }, matchWidth())

        modeButton = Button(this).apply {
            setOnClickListener {
                prefs.cycleMode()
                updateModeButton()
                scope.launch { TaskWidget().updateAll(this@MainActivity) }
            }
        }
        setupBox.addView(modeButton, matchWidth())

        setupBox.addView(sectionTitle("Arbeitsort"))
        radiusSpinner = Spinner(this)
        val radii = listOf("150 m", "300 m", "500 m", "800 m")
        radiusSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, radii)
        radiusSpinner.setSelection(1)
        prefs.workLocation()?.let {
            radiusSpinner.setSelection(when (it.radiusMeters.toInt()) {
                150 -> 0
                500 -> 2
                800 -> 3
                else -> 1
            })
        }
        setupBox.addView(radiusSpinner, matchWidth())

        setupBox.addView(Button(this).apply {
            text = "Diesen Standort als Arbeitsort speichern"
            setOnClickListener { setCurrentLocationAsWork() }
        }, matchWidth())

        setupBox.addView(Button(this).apply {
            text = "Standort-Berechtigung prüfen"
            setOnClickListener { openAppSettingsForLocation() }
        }, matchWidth())

        setupBox.addView(TextView(this).apply {
            text = "Für den automatischen Wechsel bei geschlossener App sollte unter Standort → Berechtigungen „Immer zulassen“ aktiviert sein. Der Arbeitsort bleibt nur lokal auf diesem Gerät."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        setupBox.addView(Button(this).apply {
            text = "Arbeitsort löschen"
            setOnClickListener {
                prefs.clearWorkLocation()
                GeofenceManager.unregister(this@MainActivity)
                status.text = "Arbeitsort gelöscht."
                scope.launch { TaskWidget().updateAll(this@MainActivity) }
            }
        }, matchWidth())

        setupBox.addView(sectionTitle("Dashboard"))
        setupBox.addView(Button(this).apply {
            text = "Web-Dashboard öffnen"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DASHBOARD_URL)))
            }
        }, matchWidth())

        setupBox.addView(Button(this).apply {
            text = "Abmelden"
            setOnClickListener { logout() }
        }, matchWidth())

        return scroll
    }

    private fun initializeAuth() {
        scope.launch {
            try {
                client.auth.awaitInitialization()
                if (client.auth.currentSessionOrNull() == null) {
                    client.auth.loadFromStorage(autoRefresh = true)
                }
                if (client.auth.currentSessionOrNull() != null) {
                    handleSignedInState()
                } else {
                    showLogin("Bitte mit einem der bestehenden Dashboard-Konten anmelden.")
                }
            } catch (error: Throwable) {
                showLogin("Anmeldung konnte nicht geladen werden: " + (error.message ?: "Unbekannter Fehler"))
            }
        }
    }

    private fun login() {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()
        if (mail.isBlank() || pass.isBlank()) {
            status.text = "Bitte E-Mail und Passwort eingeben."
            return
        }
        loginButton.isEnabled = false
        status.text = "Anmeldung läuft …"

        scope.launch {
            try {
                client.auth.signInWith(Email) {
                    this.email = mail
                    password = pass
                }
                handleSignedInState()
            } catch (_: Throwable) {
                showLogin("Anmeldung fehlgeschlagen. Bitte Zugangsdaten prüfen.")
            } finally {
                loginButton.isEnabled = true
            }
        }
    }

    private suspend fun handleSignedInState() {
        val (mfaEnabled, mfaActive) = client.auth.mfa.status
        if (mfaEnabled && !mfaActive) {
            showMfa()
            return
        }
        prefs.setLoggedIn(true)
        TaskRepository(this).refresh()
        TaskWidget().updateAll(this)
        WidgetSyncScheduler.schedule(this)
        showSetup()
    }

    private fun showMfa() {
        email.visibility = View.GONE
        password.visibility = View.GONE
        loginButton.visibility = View.GONE
        mfaCode.visibility = View.VISIBLE
        mfaButton.visibility = View.VISIBLE
        setupBox.visibility = View.GONE
        status.text = "2FA ist aktiv. Bitte den Code aus deiner Authenticator-App eingeben."
    }

    private fun verifyMfa() {
        val code = mfaCode.text.toString().trim()
        if (code.length != 6) {
            status.text = "Bitte den 6-stelligen Authenticator-Code eingeben."
            return
        }
        mfaButton.isEnabled = false
        scope.launch {
            try {
                val factors = client.auth.mfa.retrieveFactorsForCurrentUser()
                val factor = factors.firstOrNull() ?: error("Kein verifizierter MFA-Faktor gefunden")
                client.auth.mfa.createChallengeAndVerify(
                    factorId = factor.id,
                    code = code,
                    saveSession = true
                )
                handleSignedInState()
            } catch (_: Throwable) {
                status.text = "2FA-Code ungültig oder abgelaufen."
            } finally {
                mfaButton.isEnabled = true
            }
        }
    }

    private fun showLogin(message: String) {
        email.visibility = View.VISIBLE
        password.visibility = View.VISIBLE
        loginButton.visibility = View.VISIBLE
        mfaCode.visibility = View.GONE
        mfaButton.visibility = View.GONE
        setupBox.visibility = View.GONE
        status.text = message
    }

    private fun showSetup() {
        email.visibility = View.GONE
        password.visibility = View.GONE
        loginButton.visibility = View.GONE
        mfaCode.visibility = View.GONE
        mfaButton.visibility = View.GONE
        setupBox.visibility = View.VISIBLE
        status.text = "Angemeldet. Das Widget kann jetzt hinzugefügt werden."
        updateModeButton()
        prefs.workLocation()?.let { location -> GeofenceManager.register(this, location) }
    }

    private fun updateModeButton() {
        if (!::modeButton.isInitialized) return
        modeButton.text = when (prefs.mode()) {
            AreaMode.AUTO -> "Bereich: Auto (Standort)"
            AreaMode.WORK -> "Bereich: Arbeit"
            AreaMode.PRIVATE -> "Bereich: Privat"
        }
    }

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, TaskWidgetReceiver::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported) {
            val accepted = manager.requestPinAppWidget(provider, null, null)
            status.text = if (accepted) "Widget-Anfrage an den Samsung-Launcher gesendet." else "Bitte über Startbildschirm → Widgets hinzufügen."
        } else {
            status.text = "Bitte über Startbildschirm gedrückt halten → Widgets → Aufgaben Dashboard hinzufügen."
        }
    }

    private fun selectedRadius(): Float = when (radiusSpinner.selectedItemPosition) {
        0 -> 150f
        2 -> 500f
        3 -> 800f
        else -> 300f
    }

    private fun setCurrentLocationAsWork() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationActionPending = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }

        status.text = "Arbeitsort wird ermittelt …"
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        status.text = "Standort konnte nicht ermittelt werden."
                        return@addOnSuccessListener
                    }
                    fun coarse(value: Double) = kotlin.math.round(value * 10_000.0) / 10_000.0
                    val work = WorkLocation(coarse(location.latitude), coarse(location.longitude), selectedRadius())
                    prefs.saveWorkLocation(work)
                    prefs.setMode(AreaMode.AUTO)
                    prefs.setAutoArea("Arbeit")
                    updateModeButton()
                    GeofenceManager.register(this, work) { registered ->
                        status.text = if (registered) {
                            if (GeofenceManager.hasBackgroundLocation(this)) "Arbeitsort gespeichert. Automatischer Wechsel ist aktiv."
                            else "Arbeitsort gespeichert. Für Wechsel bei geschlossener App noch „Immer zulassen“ in den Standort-Berechtigungen aktivieren."
                        } else "Arbeitsort gespeichert, Geofence konnte aber noch nicht aktiviert werden."
                    }
                    scope.launch { TaskWidget().updateAll(this@MainActivity) }
                }
                .addOnFailureListener { status.text = "Standort konnte nicht ermittelt werden." }
        } catch (_: SecurityException) {
            status.text = "Standortberechtigung fehlt."
        }
    }

    private fun openAppSettingsForLocation() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        status.text = "Unter Berechtigungen → Standort bitte „Immer zulassen“ wählen, wenn der automatische Wechsel auch bei geschlossener App funktionieren soll."
    }

    private fun logout() {
        scope.launch {
            runCatching { client.auth.signOut() }
            prefs.markLoggedOut()
            TaskWidget().updateAll(this@MainActivity)
            showLogin("Abgemeldet.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && locationActionPending) {
            locationActionPending = false
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) setCurrentLocationAsWork()
            else status.text = "Ohne Standortfreigabe bleibt die manuelle Arbeit/Privat-Umschaltung verfügbar."
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_LOCATION = 2201
    }
}
