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
- **Medleys**: erkennt die einzelnen Lieder in einer geschnittenen Datei, jedes mit eigener Farbe, eigenem Tempo und eigener Zählung
- **Zählzeiten 1–8**: Tempo und „1“ werden automatisch erkannt (korrigierbar per Tap, ½/×2, „Hier ist die 1“);
  Raster mit Zahlen in der Detail-Wellenform, Mitzählen per Stimme oder Klick, Einrasten auf Zählzeiten
- **Wellenform mit Markern** (SoundCloud-Stil): Übersicht plus Detailansicht zum Schieben mit dem Finger (mit Ton);
  Marker sind Cue-Punkte mit Farbe und Beschriftung, Beschriftungen werden versetzt angezeigt
- **Farbgruppen**: Titel Gruppen zuordnen (Einmarsch, Garde, Tusch …), Liste nach Gruppe filtern, Name und Farbe per ✎
- **Titelinfos** aus ID3/MP4/Vorbis-Tags, Titel und Interpret umbenennbar
- **Cue-In / Cue-Out**: Start- und Endpunkt je Titel, ohne die Datei zu schneiden
- **Setliste**: Reihenfolge per Drag & Drop, Suche, Export/Import als JSON (Zuordnung über den Titelnamen)

Tastenkürzel stehen in der App unter **?**.

## Speicherung

Audiodateien liegen in der IndexedDB des Browsers, Setliste, Marker und Gruppen im localStorage – alles bleibt lokal
auf dem Gerät und nach einem Neustart erhalten. Wird der Browser-Speicher gelöscht, sind die Daten weg; vor einer
Veranstaltung daher die Setliste exportieren und die Audiodateien griffbereit halten.

## Android-App

Bei jedem Push baut GitHub Actions eine APK und stellt sie unter dem Release `apk-latest` bereit:
https://github.com/DerSchranner1/Test-Projekt-/releases/download/apk-latest/narrhalla-player.apk

Die Android-Hülle ist Capacitor (`npm run android:prepare`). Export nutzt dort die Plugins Filesystem und Share.
Die Analyse (Lieder, Schläge, „1“) steckt in `analysis.js`: spektraler Fluss, Tempo per Autokorrelation, Schlagverfolgung per dynamischer Programmierung, „1“ an Liedanfängen und Schnitten. Sie läuft im Browser in einem Web Worker und auch in Node.
Die Zählstimmen in `voices/` sind mit espeak-ng und MBROLA (de5, de7, de2, us1, us2) erzeugt; eine eigene Stimme kann in der App aufgenommen werden.
