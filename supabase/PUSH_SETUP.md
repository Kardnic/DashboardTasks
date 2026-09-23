# Echte Push-Erinnerungen einrichten

Die Web-App ist bereits für Web Push vorbereitet. Die folgenden Server-Schritte sind einmalig nötig.

## 1. Datenbank erweitern

Führe die aktuelle Datei `supabase/schema.sql` im Supabase SQL Editor erneut aus.
Sie ist idempotent und legt zusätzlich `push_subscriptions` samt RLS-Regeln an.

## 2. VAPID Private Key als Secret setzen

In Supabase:
**Edge Functions → Secrets**

Secret:
- Name: `VAPID_PRIVATE_KEY`
- Wert: **nicht in GitHub speichern**; den privaten Schlüssel nur im Supabase-Dashboard hinterlegen.

Der öffentliche VAPID-Schlüssel ist bereits in `app.js` und der Edge Function hinterlegt.

## 3. Edge Function deployen

Im Supabase Dashboard:
**Edge Functions → Deploy a new function → Via Editor**

Name:
`send-reminders`

Code:
`supabase/functions/send-reminders/index.ts`

Alternativ per CLI:
```bash
supabase functions deploy send-reminders --project-ref hfpryzswevnpmqdaidzj
```

## 4. Cron aktivieren

Nach erfolgreichem Deployment im SQL Editor:
`supabase/push_cron.sql`

Der Cron Job ruft die Edge Function jede Minute auf.

## 5. Smartphone

Dashboard/PWA öffnen und oben auf **Push aktivieren** tippen.
Die Push-Berechtigung des Browsers erlauben.

Auf iPhone/iPad muss die Webseite für Web Push als Web-App zum Home-Bildschirm hinzugefügt und von dort geöffnet werden.
