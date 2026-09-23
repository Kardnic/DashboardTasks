# Echte Push-Erinnerungen

Die Push-Infrastruktur ist für dieses Projekt bereits eingerichtet.

## Aktiver Aufbau

- Browser/PWA registriert ein Web-Push-Abo in `push_subscriptions`.
- RLS schützt die Push-Abos pro Benutzer.
- Der private VAPID-Schlüssel liegt verschlüsselt in Supabase Vault.
- `send-reminders` läuft als Supabase Edge Function.
- `pg_cron` ruft die Funktion jede Minute auf.
- Abgelaufene Push-Abos werden automatisch entfernt.

## Smartphone aktivieren

1. Dashboard/PWA neu laden.
2. Oben auf **Push aktivieren** tippen.
3. Benachrichtigungen erlauben.
4. Eine Testaufgabe mit Erinnerung wenige Minuten in der Zukunft anlegen.
5. Die App schließen und auf die Push-Erinnerung warten.

Falls das Gerät noch ein Push-Abo mit einem älteren VAPID-Schlüssel besitzt, erkennt die App dies und registriert automatisch ein neues Abo.

## iPhone/iPad

Für Web Push die Webseite als Web-App zum Home-Bildschirm hinzufügen und von dort öffnen.
