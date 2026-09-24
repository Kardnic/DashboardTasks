# DashboardTasks Android Widget

Kleine Android-Companion-App für das bestehende Dashboard.

## Funktionen

- echtes Android-Startbildschirm-Widget
- zeigt bis zu vier Aufgaben für Heute + Überfällig
- Filter Alle / Arbeit / Privat
- manuelle Aktualisierung
- periodische Aktualisierung über WorkManager
- Plus-Schaltfläche öffnet die bestehende Dashboard-Web-App
- Anmeldung mit demselben Supabase-Konto
- vorhandene TOTP-2FA wird respektiert
- kein Service-Role-Key in der App; nur der öffentliche Publishable Key

## Installation

Der GitHub-Workflow Android Widget APK baut bei Änderungen automatisch eine Debug-APK.
Nach Installation die Companion-App einmal öffnen, anmelden und anschließend das Widget über den Android-Startbildschirm hinzufügen.

## Datenmodell

Die App legt keine zweite Aufgaben-Datenbank an. Sie liest ausschließlich die vorhandene tasks-Tabelle über die bestehenden Supabase-RLS-Regeln.
