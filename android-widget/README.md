# DashboardTasks Android Widget

Native Android companion app and 4×5 home-screen widget for the existing DashboardTasks web app.

## Widget

- Default target size: 4 columns × 5 rows.
- Resizable horizontally and vertically.
- Uses the full available widget width.
- Shows Arbeit or Privat based on Auto/manual mode.
- Auto mode can switch by a locally stored workplace geofence.
- Shows Today and Overdue counters.
- Shows 3, 5 or 7 tasks depending on widget height.
- Tap the circle next to a task to complete it.
- Tap the area label to cycle Auto → Arbeit → Privat.
- Tap refresh for an immediate sync.
- Tap the bottom action to open the companion app.

## Security

The APK contains only the public Supabase publishable key. It does not contain a service-role key or any Supabase secret.

Database access still goes through the existing RLS policies and account allowlist. If an account has verified TOTP MFA, the companion app requires the Authenticator code before it can access tasks.

The workplace coordinates are stored only in Android SharedPreferences on that device and are rounded to four decimal places.

## Install

GitHub Actions builds a debug APK named `DashboardTasks-Widget-debug`.

1. Install the APK on the Samsung phone.
2. Open **DashboardTasks Widget**.
3. Sign in with one of the existing DashboardTasks accounts.
4. If prompted, enter the 6-digit Authenticator code.
5. Optionally store the current location as workplace.
6. For automatic switching while the app is closed, grant location permission **Allow all the time** in Android settings.
7. Tap **4×5 Widget zum Startbildschirm hinzufügen** or use Samsung's home-screen widget picker.

## Background behaviour

WorkManager refreshes task data periodically. Android's minimum periodic interval is 15 minutes and the exact execution time is controlled by the operating system. Geofence enter/exit events update the preferred Work/Private area independently of the periodic sync.
