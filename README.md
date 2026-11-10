# KlarTastatur

Eine deutsche Android-Tastatur, die Tippen, Diktieren, Emojis und die Zwischenablage zusammenbringt.

## Was sie kann

- **Bessere Spracherkennung (kostenlos):** 🎤 antippen, sprechen, ■ antippen. Die Erkennung läuft über Whisper large-v3 bei Groq: Satzzeichen werden automatisch gesetzt, Namen aus deinem Wörterbuch werden mitgegeben, typische Whisper-Fehler bei Stille („Untertitel im Auftrag des ZDF“) werden herausgefiltert. Ohne Groq-Schlüssel nutzt sie die Android-Spracherkennung.
- **Keine Autokorrektur, sondern Markieren und Vorschlagen:** Es wird nichts automatisch geändert. Unbekannte Wörter werden rot unterstrichen, oben erscheinen Korrekturen zum Antippen. Mit „+“ nimmst du ein Wort ins Wörterbuch auf.
- **Gute Wortvorschläge auf dem Handy, ohne Internet:** 105.000 Wörter (Deutsch und Englisch) mit richtiger Großschreibung, ß und Umlauten. Die Tastatur kennt Nachbartasten („hallp“ → „hallo“) und ähnlich klingende Buchstaben („bescheit“ → „Bescheid“). Sie lernt deine eigenen Wörter und sagt das nächste Wort voraus.
- **Zwischenablage-Verlauf:** 📋 zeigt alles, was du kopiert hast. 📌 heftet Einträge dauerhaft an. Passwörter werden nicht gespeichert. Frisch kopierte Codes, Links, E-Mails und Telefonnummern erscheinen direkt als Vorschlag zum Einfügen.
- **Emojis:** ☺ öffnet sechs Kategorien, zuletzt benutzte Emojis stehen vorne. Beim Tippen werden passende Emojis vorgeschlagen („danke“ → 🙏).
- **Bedienung:**
  - Leertaste gedrückt halten und wischen bewegt den Cursor.
  - Auf ⌫ nach links wischen löscht ganze Wörter.
  - Lang drücken gibt Umlaute und Ziffern.
  - Zweimal Leertaste setzt einen Punkt.
  - Zweimal ⇧ schaltet die Feststelltaste ein.
  - Helles und dunkles Design wechseln automatisch mit dem System.

## Installieren

1. Auf dem Handy die neueste APK herunterladen: **Releases → „KlarTastatur – neueste Version“ → `KlarTastatur.apk`**. Jeder Push baut sie automatisch neu (GitHub Actions).
2. Die APK öffnen. Wenn Android fragt, die Installation aus dieser Quelle erlauben.
3. Die App „KlarTastatur“ öffnen und den drei Schritten folgen: Tastatur aktivieren, auswählen, Mikrofon erlauben.
4. Für die gute Spracherkennung auf [console.groq.com/keys](https://console.groq.com/keys) einen kostenlosen Schlüssel erstellen und in der App einfügen.

## Selbst bauen

```
./gradlew assembleRelease        # APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # Tests für Vorschläge und Tippfehler-Erkennung
```

Die Wortliste (`app/src/main/assets/words.tsv`) wird mit `tools/build_wordlist.py` erzeugt.

## Lizenzen der Wortdaten

- Worthäufigkeiten: [wordfreq](https://github.com/rspeer/wordfreq), Daten unter CC BY-SA 4.0
- Rechtschreibung und Großschreibung: igerman98 / hunspell-de-de (GPL-2+)
