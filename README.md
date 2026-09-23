# DashboardTasks

Persönliches Aufgaben-Dashboard für Smartphone und PC.

## Technik
- GitHub Pages / statische Web-App
- Supabase Auth + PostgreSQL
- PWA-Grundgerüst für Installation auf dem Smartphone
- Responsive Dashboard für Desktop und Mobil

## Supabase
Die Datenbankstruktur liegt unter `supabase/schema.sql`.

Projekt-URL:
`https://hfpryzswevnpmqdaidzj.supabase.co`

Der im Frontend verwendete `sb_publishable_...`-Schlüssel ist ein öffentlicher Publishable Key. Die Zugriffe auf Aufgaben werden über Supabase Auth und Row Level Security abgesichert.

## Start
1. `supabase/schema.sql` im Supabase SQL Editor ausführen.
2. Unter Authentication einen Benutzer anlegen oder E-Mail/Passwort-Registrierung erlauben.
3. GitHub Pages für das Repository aktivieren und als Quelle **GitHub Actions** auswählen.
4. Danach die veröffentlichte Seite auf PC oder Smartphone öffnen.

## Funktionen der ersten Version
- E-Mail/Passwort-Login
- Aufgabe schnell erfassen
- Fälligkeit: Inbox, Heute, Morgen oder Datum
- Kategorien
- Prioritäten
- Aufgabe erledigen / wieder öffnen
- Aufgabe löschen
- Ansichten für Heute, Überfällig, Inbox, diese Woche und Erledigt
- Responsive Smartphone-Ansicht
- Installierbare PWA
