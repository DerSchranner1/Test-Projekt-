# KlarTastatur

Eine dunkle, schlichte Android-Tastatur für Deutsch und Englisch. Sie bringt Tippen, Wischen, Diktieren, Emojis, GIFs und die Zwischenablage zusammen. Aussehen und Maße orientieren sich an einer kompakt eingestellten Gboard.

## Was sie kann

**Tippen**
- **Deutsch und Englisch gleichzeitig:** Die Tastatur erkennt an den letzten Wörtern, in welcher Sprache du schreibst, und schlägt passend vor.
- **Wortvorhersagen auf dem Handy, ohne Internet:** 115.000 Wörter und Wortfolgen aus rund 4 Millionen Sätzen. Nach „Vielen“ kommt „Dank“, nach „how are“ kommt „you“. Großschreibung und ß stimmen im Zusammenhang („Guten Morgen“, „beim Essen“, „WhatsApp“). Die Tastatur lernt deine eigenen Wörter dazu.
- **Unsichtbare Treffhilfe (wie beim iPhone):** Buchstaben, die als nächstes wahrscheinlich sind, haben eine größere Trefferfläche. Die Tasten sehen gleich aus, und die Mitte einer Taste trifft immer diese Taste. Die Stärke ist per Regler einstellbar.
- **Wischen zum Schreiben:** Finger über die Buchstaben ziehen. ⌫ löscht das ganze gewischte Wort.
- **Autokorrektur wählbar:** Standardmäßig aus, dann werden Tippfehler nur rot markiert und oben Korrekturen angeboten. Eingeschaltet ersetzt sie eindeutige Fehler, ⌫ macht das rückgängig.
- **Layouts:** QWERTZ (Umlaute per langem Druck auf a, o, u, s), QWERTZ mit Ä Ö Ü, QWERTY. Eine eigene ß-Taste und die Zahlenreihe sind zuschaltbar.

**Werkzeugleiste** (bei leerem Feld sichtbar, sonst über das Kachel-Symbol oben links)
- **Zwischenablage:** Verlauf mit Anheften. Passwörter werden nicht gespeichert.
- **GIFs und Sticker:** GIPHY (kostenloser Schlüssel nötig), eigene Sticker aus der Galerie, Emoji-Sticker.
- **Textbearbeitung:** Cursor-Pfeile, Auswählen, Kopieren, Einfügen, Ausschneiden, Rückgängig.
- **Einhandmodus:** schmale Tastatur links oder rechts.
- **Größe ändern:** oben ziehen ändert die Größe, unten ziehen den Abstand zum Rand.

**Diktieren**
- 🎤 antippen, sprechen, ■ antippen. Die Erkennung läuft über Whisper large-v3 bei Groq (kostenloser Schlüssel) und setzt Satzzeichen automatisch. Ohne Schlüssel nutzt die Tastatur die Android-Spracherkennung.

**Bedienung**
- Leertaste halten und wischen bewegt den Cursor.
- Auf ⌫ nach links wischen löscht ganze Wörter.
- Zweimal Leertaste setzt einen Punkt, zweimal ⇧ schaltet die Feststelltaste ein.

## Gemessene Qualität

Die Tests messen an Sätzen, die nicht in den Trainingsdaten waren (`./gradlew testDebugUnitTest`):

| | ohne Wortfolgen | mit Wortfolgen |
|---|---|---|
| Deutsch: nächstes Wort unter den 3 Vorschlägen | 6,6 % | 25,8 % |
| Deutsch: eingesparte Tastendrücke | 18,7 % | 29,9 % |
| Englisch: nächstes Wort unter den 3 Vorschlägen | 10,0 % | 35,5 % |
| Englisch: eingesparte Tastendrücke | 17,5 % | 33,8 % |

Treffhilfe bei simuliertem ungenauem Tippen: 6,5 % falsche Tasten ohne, 2,6 % bei Stärke 50 %, 1,9 % bei Stärke 100 %.

## Installieren

1. Auf dem Handy die neueste APK herunterladen: **Releases → „KlarTastatur – neueste Version“ → `KlarTastatur.apk`**. Jeder Push baut sie automatisch neu (GitHub Actions).
2. Die APK öffnen. Wenn Android fragt, die Installation aus dieser Quelle erlauben.
3. Die App „KlarTastatur“ öffnen und den drei Schritten folgen: Tastatur aktivieren, auswählen, Mikrofon erlauben.
4. Für die gute Spracherkennung auf [console.groq.com/keys](https://console.groq.com/keys) einen kostenlosen Schlüssel erstellen und in der App einfügen. Für GIFs entsprechend auf [developers.giphy.com](https://developers.giphy.com/dashboard/).

## Selbst bauen und testen

```
./gradlew assembleRelease        # APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # alle Tests
```

Die Tests bedienen die echte Tastatur ohne Handy (Robolectric): Sie tippen Tasten an, prüfen den Text im Textfeld und rendern Bilder nach `app/build/screenshots/`.

Die Wortdaten in `app/src/main/assets/` entstehen mit den Skripten in `tools/`:
1. `build_wordlist.py` erzeugt `words.tsv` (Wort, Häufigkeit Deutsch, Häufigkeit Englisch).
2. `build_ngrams.py` erzeugt `ngrams.tsv` (deutsche Wortfolgen) und korrigiert Schreibweisen.
3. `build_ngrams_en.py` erzeugt `ngrams_en.tsv` (englische Wortfolgen).

## Lizenzen der Wortdaten

- Worthäufigkeiten: [wordfreq](https://github.com/rspeer/wordfreq), Daten unter CC BY-SA 4.0
- Deutsche Rechtschreibung und Großschreibung: igerman98 / hunspell-de-de (GPL-2+)
- Wortfolgen: [Tatoeba](https://tatoeba.org) (CC BY 2.0 FR) und [Mozilla Common Voice](https://github.com/common-voice/common-voice) (CC0)
