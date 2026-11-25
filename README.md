# KlarTastatur

Eine dunkle, schlichte Android-Tastatur für Deutsch und Englisch. Sie bringt Tippen, Wischen, Diktieren, Emojis, GIFs und die Zwischenablage zusammen. Die Maße orientieren sich an einer kompakt eingestellten Gboard, das Aussehen ist eigen: Petrol-Schwarz mit türkisem Akzent.

## Was sie kann

**Tippen**
- **Bairisch:** „i hob“, „des is ned“, „griaß di“, „pfiat di“ und über 40.000 bairische Wörter werden nicht als Fehler markiert, vorgeschlagen und bei der Treffhilfe mitgerechnet (abschaltbar). Hochdeutsch und Englisch gehen weiterhin.
- **Deutsch und Englisch gleichzeitig:** Die Tastatur erkennt an den letzten Wörtern, in welcher Sprache du schreibst, und schlägt passend vor.
- **Wortvorhersagen auf dem Handy, ohne Internet:** 115.000 Wörter und Wortfolgen aus rund 4 Millionen Sätzen. Nach „Vielen“ kommt „Dank“, nach „how are“ kommt „you“. Großschreibung und ß stimmen im Zusammenhang („Guten Morgen“, „beim Essen“, „WhatsApp“). Die Tastatur lernt deine eigenen Wörter dazu.
- **Unsichtbare Treffhilfe (wie beim iPhone):** Buchstaben, die als nächstes wahrscheinlich sind, haben eine größere Trefferfläche. Die Tasten sehen gleich aus, und die Mitte einer Taste trifft immer diese Taste. Die Stärke ist per Regler einstellbar.
- **Wischen zum Schreiben:** Finger über die Buchstaben ziehen. ⌫ löscht das ganze gewischte Wort.
- **Autokorrektur wählbar:** Standardmäßig aus, dann werden Tippfehler nur rot markiert und oben Korrekturen angeboten. Eingeschaltet ersetzt sie eindeutige Fehler, ⌫ macht das rückgängig.
- **Layouts:** QWERTZ (Umlaute per langem Druck auf a, o, u, s), QWERTZ mit Ä Ö Ü, QWERTY. Eine eigene ß-Taste und die Zahlenreihe sind zuschaltbar.

**Emojis, GIFs und Sticker**
- **Text zu Emoji, ohne Internet:** Passende Emojis erscheinen in der Vorschlagsleiste, auch für Wendungen („Gute Nacht“ → 😴, „Alles Gute zum Geburtstag“ → 🎂), gebeugte und zusammengesetzte Wörter („gefeiert“ → 🥳, „Lieblingspizza“ → 🍕) und Smileys aus Zeichen („:)“ → 🙂). Standardmäßig ersetzt das Emoji das Wort („Geige“ → 🎻, abschaltbar), bei Wendungen bleibt der Text stehen. Die Tastatur merkt sich, welches Emoji du nach einem Wort benutzt. Langer Druck auf das Emoji zeigt weitere passende.
- **Alle Emojis** in den Gruppen und der Reihenfolge von Gboard. Suche auf Deutsch und Englisch, Hauttöne per langem Druck (die Wahl wird gemerkt). Emojis, die das Handy nicht darstellen kann, werden ausgeblendet.
- **Animierte Emojis:** Die 60 beliebtesten stecken in der App, bewegen sich schon in der Übersicht und werden ohne Internet als GIF gesendet. Weitere werden nach Zustimmung geladen.
- **GIFs aus Filmen und Memes** kommen von GIPHY und brauchen einen kostenlosen Schlüssel. Mit Schlüssel werden die Trend-GIFs beim Öffnen des Emoji-Bereichs schon vorgeladen. Dazu eigene Sticker aus der Galerie und Emoji-Sticker.

**Werkzeugleiste** (bei leerem Feld sichtbar, sonst über das Kachel-Symbol oben links)
- **Zwischenablage:** Verlauf mit Anheften. Passwörter werden nicht gespeichert.
- **Textbearbeitung:** Cursor-Pfeile, Auswählen, Kopieren, Einfügen, Ausschneiden, Rückgängig.
- **Einhandmodus:** schmale Tastatur links oder rechts.
- **Größe ändern:** ziehen ändert die Größe, die Tastatur bleibt dabei unten. Der untere Griff hebt sie ein kleines Stück vom Rand an.

**Diktieren**
- 🎤 antippen und sprechen. Die Aufnahme endet von selbst, wenn du fertig bist.
- **Offline (privat):** Whisper läuft direkt auf dem Handy (whisper.cpp). Dafür wird in den Einstellungen einmal ein Modell geladen: „Genau“ (190 MB) oder „Schnell“ (60 MB). Danach braucht das Diktieren kein Internet und die Aufnahme verlässt das Handy nicht.
- **Ohne Modell** nutzt die Tastatur die Spracherkennung des Handys. Gesprochene Satzzeichen („Komma“, „Fragezeichen“, „neue Zeile“) werden umgewandelt.
- **Mit kostenlosem Groq-Schlüssel** läuft die Erkennung online über Whisper large-v3 (nur wenn du das einschaltest). Ohne Internet springt automatisch das Offline-Modell ein.

**Rechtschreibung (ohne Internet)**
- Die Tastatur kennt rund 500.000 gültige Wortformen. Unbekannte Wörter werden im Text rot markiert und in der Vorschlagsleiste rot unterkringelt, daneben stehen Korrekturen.
- Großschreibung im Zusammenhang: Nach „Das unterkringeln funktioniert“ wird „Unterkringeln“ vorgeschlagen.
- Die App bringt außerdem eine Rechtschreibprüfung für das ganze Handy mit, die in den Systemeinstellungen ausgewählt wird.

**Tippgefühl**
- Unterschiedliche Vibration für Buchstaben, Sondertasten, langes Drücken, Cursor-Schritte, Bestätigungen und Fehler. Die Stärke ist per Regler einstellbar.
- Leertaste lange halten macht die Tastatur zum Tastfeld: Finger bewegen verschiebt den Cursor in alle Richtungen.

**Bedienung**
- Auf der Leertaste wischen bewegt den Cursor.
- 🔍 im Emoji-Bereich: Treffer erscheinen schon beim Tippen in der Leiste.
- Auf ⌫ nach links wischen löscht ganze Wörter.
- Zweimal Leertaste setzt einen Punkt, zweimal ⇧ schaltet die Feststelltaste ein.

## Datenschutz

- Alles, was du tippst, bleibt auf dem Handy. Wortvorschläge, Rechtschreibung, Wischen und das Offline-Diktat arbeiten ohne Internet.
- Ins Internet geht die App nur, wenn du es auslöst: Modell-Download (huggingface.co), Groq-Diktat, GIPHY-Suche und animierte Emojis (jeweils abschaltbar bzw. erst nach Zustimmung).
- In Passwortfeldern und im Inkognito-Modus wird nichts gelernt. Kopiertes wird nach einer Stunde gelöscht, Passwörter gar nicht gespeichert.
- Gelernte Wörter und Zwischenablage lassen sich in den Einstellungen löschen. Cloud-Backups der App-Daten sind abgeschaltet.

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
4. Optional: Für die genauere Spracherkennung auf [console.groq.com/keys](https://console.groq.com/keys) einen kostenlosen Schlüssel erstellen und kopieren, für GIFs entsprechend auf [developers.giphy.com](https://developers.giphy.com/dashboard/). Zurück in der App wird der kopierte Schlüssel automatisch übernommen.

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
4. `build_bairisch.py` erzeugt `bairisch.tsv` und `ngrams_bar.tsv` aus `tools/bairisch_basis.txt` und der bairischen Wikipedia.
5. `build_emoji.py` erzeugt `emoji.tsv` und `emoji_words.tsv`.
6. `build_spell.py` erzeugt `valid.bloom` (gültige Wortformen) und `grammar_de.tsv` (Verbformen).

Das Offline-Diktat wird aus `app/src/main/cpp/` gebaut und braucht NDK 28.2.13676358 und CMake 3.22.1.

## Lizenzen der Wortdaten

- Worthäufigkeiten: [wordfreq](https://github.com/rspeer/wordfreq), Daten unter CC BY-SA 4.0
- Deutsche Rechtschreibung und Großschreibung: igerman98 / hunspell-de-de (GPL-2+)
- Bairisch: eigene Grundliste und Häufigkeiten aus der [bairischen Wikipedia](https://bar.wikipedia.org) (CC BY-SA 4.0)
- Wortfolgen: [Tatoeba](https://tatoeba.org) (CC BY 2.0 FR) und [Mozilla Common Voice](https://github.com/common-voice/common-voice) (CC0)
- Eingebaute animierte Emojis: verkleinert aus Noto Animated Emoji (CC BY 4.0)
- Emojis: Reihenfolge und Gruppen aus [googlefonts/emoji-metadata](https://github.com/googlefonts/emoji-metadata) (Apache 2.0), Suchwörter aus Unicode CLDR (Unicode-Lizenz), animierte Emojis von [Noto Animated Emoji](https://googlefonts.github.io/noto-emoji-animation/) (CC BY 4.0)
- Offline-Diktat: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT), Modelle von OpenAI Whisper (MIT)
- Englische Rechtschreibung: hunspell-en-us / SCOWL
