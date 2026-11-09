# Narrhalla Player (Draft)

Media-Player für Veranstaltungen der Narrhalla – als Ersatz für das bisherige Abspielen über den Lexis Audio Editor.
Reine Browser-App ohne Installation und ohne Build-Schritt.

## Starten

**Am Rechner:** `index.html` in Chrome, Edge oder Firefox öffnen (Doppelklick genügt). Läuft komplett offline.

**Als App auf Android und iPhone/iPad:** Der Player ist eine installierbare Web-App (PWA). Dafür muss er einmal
über HTTPS erreichbar sein, z. B. per GitHub Pages (Repo → Settings → Pages → Branch auswählen). Danach:

- Android (Chrome): Seite öffnen → Menü ⋮ → „App installieren“ bzw. „Zum Startbildschirm hinzufügen“
- iPhone/iPad (Safari): Seite öffnen → Teilen → „Zum Home-Bildschirm“

Nach dem ersten Öffnen läuft die App auch ohne Internet. Nach Änderungen am Code die Versionsnummer in `sw.js`
erhöhen, damit installierte Geräte die neue Fassung laden.

## Funktionen

- **Playback**: Play/Pause, Stopp, ±5 s, vorheriger/nächster Titel, Lautstärke, Fade-Out, Loop, optional Auto-Weiter
- **Replay**: vom Anfang (Cue-In), ab dem letzten Marker, oder den zuvor gespielten Titel nochmal; dazu ein Verlauf
- **Wellenform mit Markern** (SoundCloud-Stil): Klick springt, Doppelklick setzt einen Marker; Marker haben Farbe und Beschriftung
- **Farbgruppen**: Titel Gruppen zuordnen (Einmarsch, Garde, Tusch …), Liste nach Gruppe filtern, Farben und Gruppen frei anpassbar
- **Cue-In / Cue-Out**: Start- und Endpunkt je Titel, ohne die Datei zu schneiden
- **Setliste**: Reihenfolge per Drag & Drop, Suche, Export/Import als JSON (Zuordnung über den Titelnamen)

Tastenkürzel stehen in der App unter **?**.

## Speicherung

Audiodateien liegen in der IndexedDB des Browsers, Setliste, Marker und Gruppen im localStorage – alles bleibt lokal
auf dem Gerät und nach einem Neustart erhalten. Wird der Browser-Speicher gelöscht, sind die Daten weg; vor einer
Veranstaltung daher die Setliste exportieren und die Audiodateien griffbereit halten.
