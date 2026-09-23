# DashboardTasks security / push architecture

Production is configured so that no private server secret is stored in this repository.

## Push security

- Web Push subscriptions are stored per user with RLS.
- The VAPID private key is encrypted in Supabase Vault.
- The reminder Edge Function is callable only with a high-entropy secret generated inside Postgres and stored in Vault.
- The public Supabase publishable key is **not** treated as authentication for server jobs.
- The pg_cron job reads the secret from Vault and invokes `send-reminders` every minute.
- The retired `setup-push` function is disabled in production and intentionally absent from source.

## Data access

- Anonymous users have no table privileges.
- Authenticated users receive only the columns/actions needed by the UI.
- `user_id`, creation timestamps and server-maintained timestamps cannot be forged by the browser.
- RLS requires both ownership and membership in `app_private.allowed_users`.
- New Auth accounts are not automatically added to the allowlist.

## Browser

- Content Security Policy limits scripts, network access, workers and resources.
- Supabase JS is pinned to an exact version.
- The app does not expose account creation.
- Persisted sessions are validated against Supabase on startup.
- Task metadata is rendered with `textContent`, not dynamic HTML.

## Remaining dashboard controls

For maximum account security also enable Supabase Auth leaked-password protection and, if desired, passkeys/MFA in the Supabase Dashboard.
