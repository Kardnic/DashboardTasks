package de.kardnic.dashboardtaskswidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val client get() = SupabaseProvider.client

    private lateinit var loginBox: View
    private lateinit var mfaBox: View
    private lateinit var loggedInBox: View
    private lateinit var statusText: TextView
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var mfaInput: EditText
    private lateinit var loginButton: Button
    private lateinit var mfaButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginBox = findViewById(R.id.loginBox)
        mfaBox = findViewById(R.id.mfaBox)
        loggedInBox = findViewById(R.id.loggedInBox)
        statusText = findViewById(R.id.statusText)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        mfaInput = findViewById(R.id.mfaInput)
        loginButton = findViewById(R.id.loginButton)
        mfaButton = findViewById(R.id.mfaButton)

        loginButton.setOnClickListener { login() }
        mfaButton.setOnClickListener { verifyMfa() }

        findViewById<Button>(R.id.refreshWidgetButton).setOnClickListener {
            WidgetUpdateWorker.enqueue(this, replace = true)
            statusText.text = getString(R.string.widget_refresh_started)
        }

        findViewById<Button>(R.id.openDashboardButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DASHBOARD_URL)))
        }

        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            lifecycleScope.launch {
                runCatching { client.auth.signOut() }
                showLogin()
                WidgetUpdateWorker.enqueue(this@MainActivity, replace = true)
            }
        }

        lifecycleScope.launch {
            statusText.text = getString(R.string.checking_session)
            client.auth.awaitInitialization()
            if (client.auth.currentSessionOrNull() == null) {
                showLogin()
            } else {
                showAfterFirstFactor()
            }
        }
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
        val levels = client.auth.mfa.getAuthenticatorAssuranceLevel()
        if (levels.first != levels.second) {
            loginBox.visibility = View.GONE
            mfaBox.visibility = View.VISIBLE
            loggedInBox.visibility = View.GONE
            statusText.text = getString(R.string.mfa_required)
        } else {
            showLoggedIn()
        }
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
                val factor = factors.firstOrNull()
                    ?: error("No verified MFA factor")
                client.auth.mfa.createChallengeAndVerify(
                    factorId = factor.id,
                    code = code
                )
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
        statusText.text = getString(R.string.signed_in)
        WidgetUpdateWorker.enqueue(this, replace = true)
        DashboardWidgetProvider.schedulePeriodicUpdates(this)
    }

    private fun setBusy(busy: Boolean) {
        loginButton.isEnabled = !busy
        mfaButton.isEnabled = !busy
    }
}
