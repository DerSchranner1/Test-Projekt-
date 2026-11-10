# KlarTastatur

Eine deutsche Android-Tastatur, die Tippen, Diktieren, Emojis und die Zwischenablage zusammenbringt.

## Was sie kann

- **Bessere Spracherkennung (kostenlos):** 🎤 antippen, sprechen, ■ antippen. Die Erkennung läuft über Whisper large-v3 bei Groq: Satzzeichen werden automatisch gesetzt, Namen aus deinem Wörterbuch werden mitgegeben, typische Whisper-Fehler bei Stille („Untertitel im Auftrag des ZDF“) werden herausgefiltert. Ohne Groq-Schlüssel nutzt sie die Android-Spracherkennung.
- **Wischen zum Schreiben:** Finger über die Buchstaben ziehen. Die Erkennung läuft auf dem Handy, oben erscheinen Alternativen, ⌫ löscht das ganze gewischte Wort. Ein- und ausschaltbar unter Einstellungen → Tippen & Korrektur.
- **Autokorrektur wählbar:** Standardmäßig aus. Eingeschaltet ersetzt sie nur eindeutige Tippfehler, ⌫ direkt danach macht die Korrektur rückgängig.
- **GIFs und Sticker:** Über ☺ erreichst du die Reiter GIF und Sticker. Es gibt Suche und Themen (Haha, Danke, Liebe …). Die GIFs kommen von GIPHY und brauchen einen kostenlosen Schlüssel. Dazu kommen eigene Sticker aus der Galerie und Emoji-Sticker. Alles wird als echtes Bild eingefügt, wenn die App das unterstützt.
- **Ohne Autokorrektur: Markieren und Vorschlagen:** Es wird nichts automatisch geändert. Unbekannte Wörter werden rot unterstrichen, oben erscheinen Korrekturen zum Antippen. Mit „+“ nimmst du ein Wort ins Wörterbuch auf.
- **Gute Wortvorschläge auf dem Handy, ohne Internet:** 105.000 Wörter (Deutsch und Englisch) mit richtiger Großschreibung, ß und Umlauten. Die Tastatur kennt Nachbartasten („hallp“ → „hallo“) und ähnlich klingende Buchstaben („bescheit“ → „Bescheid“). Sie lernt deine eigenen Wörter und sagt das nächste Wort voraus.
- **Zwischenablage-Verlauf:** 📋 zeigt alles, was du kopiert hast. 📌 heftet Einträge dauerhaft an. Passwörter werden nicht gespeichert. Frisch kopierte Codes, Links, E-Mails und Telefonnummern erscheinen direkt als Vorschlag zum Einfügen.
- **Emojis:** ☺ öffnet sieben Kategorien, zuletzt benutzte Emojis stehen vorne. Beim Tippen werden passende Emojis vorgeschlagen („danke“ → 🙏).
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
