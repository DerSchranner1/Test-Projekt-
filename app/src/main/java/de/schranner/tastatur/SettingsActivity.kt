package de.schranner.tastatur

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.textservice.TextServicesManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Einrichtung und Einstellungen auf einer Seite, in Karten gegliedert.
 * Schlüssel für Diktat und GIFs werden nach dem Kopieren automatisch aus der Zwischenablage übernommen.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private var card: LinearLayout? = null
    private val anchors = HashMap<String, View>()

    private var awaitingGroq = false
    private var awaitingGiphy = false
    /** Ergebnis der Schlüssel-Prüfung: true gültig, false ungültig, null unbekannt. */
    private var groqOk: Boolean? = null
    private var giphyOk: Boolean? = null
    private var haptics: Haptics? = null

    private val bg = Color.parseColor("#17181A")
    private val cardColor = Color.parseColor("#232427")
    private val textColor = Color.parseColor("#E8EAED")
    private val dim = Color.parseColor("#A0A4A9")
    private val accent = Color.parseColor("#A8C8FB")
    private val accentText = Color.parseColor("#062E6F")
    private val good = Color.parseColor("#81C995")
    private val bad = Color.parseColor("#F28B82")

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        haptics = Haptics(this)
        scroll = ScrollView(this).apply { setBackgroundColor(bg); fitsSystemWindows = true }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(20f), dp(16f), dp(40f))
        }
        scroll.addView(page)
        setContentView(scroll)
        build()
        intent.getStringExtra(EXTRA_PAGE)?.let { p ->
            scroll.post { anchors[p]?.let { scroll.scrollTo(0, (it.top - dp(8f)).coerceAtLeast(0)) } }
        }
        if (prefs.groqKey.isNotBlank()) check("groq")
        if (prefs.giphyKey.isNotBlank()) check("giphy")
        if (intent.getBooleanExtra(EXTRA_ASK_MIC, false)) askMic()
    }

    override fun onStart() {
        super.onStart()
        ModelDownload.onChange = { if (!isFinishing) build() }
    }

    override fun onStop() {
        super.onStop()
        ModelDownload.onChange = null
    }

    // ================================================================== Seite

    private fun build() {
        val y = scroll.scrollY
        page.removeAllViews()
        anchors.clear()
        card = null

        page.addView(TextView(this).apply {
            text = "KlarTastatur"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4f), 0, 0, dp(12f))
        })

        // ---- Einrichten
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "")
            .startsWith("$packageName/")
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (enabled && selected && mic) {
            card()
            status("✓  Die Tastatur ist eingerichtet", good)
        } else {
            card("Einrichten")
            step(1, "Tastatur aktivieren", enabled) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            step(2, "Als Tastatur auswählen", selected) { imm.showInputMethodPicker() }
            step(3, "Mikrofon erlauben (zum Diktieren)", mic) { askMic() }
        }

        // ---- Tippen
        card("Tippen")
        switch("Wischen zum Schreiben", prefs.gestureTyping) { prefs.gestureTyping = it }
        switch("Autokorrektur", prefs.autocorrect) { prefs.autocorrect = it }
        hint("Aus: Tippfehler werden nur rot markiert. An: eindeutige Fehler werden ersetzt, ⌫ macht es rückgängig.")
        switch("Tippfehler rot markieren", prefs.markTypos) { prefs.markTypos = it }
        switch("Zahlenreihe", prefs.numberRow) { prefs.numberRow = it }
        switch("Eigene ß-Taste", prefs.eszettKey) { prefs.eszettKey = it }
        label("Layout")
        val group = RadioGroup(this)
        val current = KeyLayout.of(prefs.layout)
        for (l in KeyLayout.entries) {
            group.addView(RadioButton(this).apply {
                id = 1000 + l.ordinal
                text = l.title
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                isChecked = l == current
            })
        }
        group.setOnCheckedChangeListener { _, id -> prefs.layout = KeyLayout.entries[id - 1000].id }
        card!!.addView(group)
        hint("Deutsch und Englisch gleichzeitig: Die Tastatur erkennt an den letzten Wörtern, in welcher Sprache du schreibst.")

        // ---- Tippgefühl
        card("Tippgefühl")
        slider("Vibration", prefs.vibration, { if (it == 0) "Aus" else "$it %" }) {
            prefs.vibration = it
            haptics?.strength = it
            haptics?.tap()
        }
        slider("Treffhilfe", prefs.touchAssist, { if (it == 0) "Aus" else "$it %" }) { prefs.touchAssist = it }
        hint("Wie beim iPhone: Buchstaben, die als nächstes wahrscheinlich sind, lassen sich leichter treffen. Die Tasten sehen dabei gleich aus.")
        hint("Größe, Einhandmodus und Textbearbeitung: in der Tastatur oben links auf das Kachel-Symbol tippen.")

        // ---- Diktieren
        anchors[PAGE_VOICE] = card("Diktieren")
        val groq = prefs.groqKey.isNotBlank()
        val whisper = OfflineWhisper(this)
        val model = whisper.installed()
        val canOffline = WhisperNative.ensureLoaded()
        val online = groq && (prefs.voiceEngine == "groq" || model == null || !canOffline)
        when {
            online && groqOk == false -> status("✗  Der Groq-Schlüssel funktioniert nicht", bad)
            online -> status("✓  Aktiv: Whisper über Groq (online, am genauesten)", good)
            model != null && canOffline -> status("✓  Aktiv: Offline-Erkennung „${model.title}“ – nichts verlässt das Handy", good)
            else -> status("Aktiv: Spracherkennung des Handys", dim)
        }

        label("Offline (privat)")
        val dl = ModelDownload
        when {
            !canOffline -> hint("Auf diesem Gerät nicht verfügbar (braucht einen 64-Bit-Prozessor).")
            dl.running -> {
                val m = dl.model!!
                body("Lädt „${m.title}“ … ${dl.done * 100 / m.bytes} %  (${dl.done / 1_000_000} von ${m.bytes / 1_000_000} MB)")
                button("Abbrechen") { dl.cancel() }
            }
            else -> {
                dl.message?.let { hint(it) }
                if (model == null) {
                    body("Die Spracherkennung läuft dann komplett auf dem Handy: kein Konto, kein Schlüssel, und deine Aufnahme verlässt das Gerät nicht. Dafür wird einmal ein Modell geladen (am besten im WLAN).")
                }
                for (m in OfflineWhisper.Model.entries) {
                    if (whisper.isInstalled(m)) {
                        button("„${m.title}“ löschen (${m.size} frei machen)") { whisper.delete(m); build() }
                    } else {
                        val what = if (m == OfflineWhisper.Model.SMALL) "erkennt deutlich besser, braucht ein paar Sekunden" else "schneller, macht mehr Fehler"
                        button("„${m.title}“ laden – ${m.size}", primary = model == null && m == OfflineWhisper.Model.SMALL) {
                            dl.start(this, m); build()
                        }
                        hint("${m.title}: $what.")
                    }
                }
            }
        }

        label("Online mit Groq (am genauesten)")
        if (!groq) {
            body("Noch genauer und schneller ist Whisper im Netz. Dafür brauchst du einmalig einen kostenlosen Schlüssel (etwa 1 Minute):")
            body("1. Unten auf den Knopf tippen und mit Google anmelden\n2. „Create API Key“ antippen und den Schlüssel kopieren\n3. Zurück in diese App wechseln – der Schlüssel wird automatisch erkannt")
            button("Schlüssel-Seite öffnen") {
                awaitingGroq = true
                open("https://console.groq.com/keys")
            }
            button("Kopierten Schlüssel einfügen") { if (!takeFromClipboard(force = "groq")) manualKey("groq") }
            hint("Mit Groq wird die Aufnahme zum Erkennen an diesen Dienst geschickt.")
        } else {
            if (model != null && canOffline) {
                switch("Groq statt Offline-Erkennung benutzen", prefs.voiceEngine == "groq") {
                    prefs.voiceEngine = if (it) "groq" else "auto"; build()
                }
            }
            button("Groq-Schlüssel entfernen") { prefs.groqKey = ""; groqOk = null; prefs.voiceEngine = "auto"; build() }
        }

        // ---- GIFs & Sticker
        anchors[PAGE_MEDIA] = card("GIFs & Sticker")
        val giphy = prefs.giphyKey.isNotBlank()
        when {
            giphy && giphyOk == false -> status("✗  Der Schlüssel funktioniert nicht", bad)
            giphy && giphyOk == true -> status("✓  GIFs von GIPHY sind aktiv", good)
            giphy -> status("GIPHY ist eingerichtet", good)
            else -> status("Aktiv: alle Emojis, animierte Emojis, Sticker", dim)
        }
        if (!giphy) {
            body("Emojis, animierte Emojis und Sticker funktionieren sofort. Für GIFs aus Filmen und Memes brauchst du einmalig einen kostenlosen GIPHY-Schlüssel:")
            body("1. Unten auf den Knopf tippen und ein Konto anlegen\n2. „Create an App“ → „API“ wählen → den „API Key“ kopieren\n3. Zurück in diese App wechseln – der Schlüssel wird automatisch erkannt")
            button("GIPHY-Seite öffnen", primary = true) {
                awaitingGiphy = true
                open("https://developers.giphy.com/dashboard/")
            }
            button("Kopierten Schlüssel einfügen") { if (!takeFromClipboard(force = "giphy")) manualKey("giphy") }
        } else {
            button("Schlüssel entfernen") { prefs.giphyKey = ""; giphyOk = null; build() }
        }
        val n = StickerStore(this).list().size
        button(if (n == 0) "Eigene Sticker aus der Galerie hinzufügen" else "Eigene Sticker hinzufügen ($n vorhanden)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, REQ_STICKERS)
        }

        // ---- Rechtschreibprüfung
        card("Rechtschreibprüfung")
        val spell = spellCheckerActive()
        if (spell) status("✓  Aktiv in allen Apps", good)
        else {
            body("Beim Tippen mit dieser Tastatur werden unbekannte Wörter rot unterstrichen. Damit Android das in allen Apps und auch nachträglich macht, wähle einmal „KlarTastatur Rechtschreibprüfung“ aus:")
            button("Rechtschreibprüfung auswählen", primary = true) { openSpellCheckerSettings() }
        }
        hint("Manche Apps zeichnen ihre Textfelder selbst und zeigen deshalb keine Unterstreichungen.")

        // ---- Datenschutz
        card("Datenschutz")
        body("Die Tastatur arbeitet auf dem Handy. Wortvorschläge, Rechtschreibung, Wischen, Emojis und die Offline-Spracherkennung brauchen kein Internet.")
        body("Das Handy verlässt nur etwas, wenn du es selbst einschaltest:\n" +
            "• Groq: deine Sprachaufnahme\n" +
            "• GIPHY: deine GIF-Suche\n" +
            "• Animierte Emojis: welches Emoji du auswählst (Google)\n" +
            "• Spracherkennung des Handys: je nach Handy die Aufnahme (Google)\n" +
            "• Modell laden: einmaliger Download")
        hint("Was du tippst, wird nie gesendet. Gelernte Wörter, Zwischenablage und Schlüssel liegen nur im privaten Speicher der App und sind von Backups ausgeschlossen. In Passwort-Feldern und Inkognito-Fenstern wird nichts gelernt.")
        switch("Aus meinem Schreiben lernen", prefs.learnFromTyping) { prefs.learnFromTyping = it }
        switch("Zwischenablage-Verlauf (1 Stunde)", prefs.clipboardHistory) { on ->
            prefs.clipboardHistory = on
            if (!on) ClipboardStore(this).clearUnpinned()
        }
        switch("Animierte Emojis aus dem Netz laden", prefs.onlineAnimated) { prefs.onlineAnimated = it }
        button("Gelernte Wörter löschen") {
            EngineHolder.get(this).clearUserData()
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putLong("user_data_cleared", System.currentTimeMillis()).apply()
            Toast.makeText(this, "Gelernte Wörter gelöscht", Toast.LENGTH_SHORT).show()
        }
        button("Zwischenablage-Verlauf löschen") {
            ClipboardStore(this).let { c -> c.items.toList().forEach { c.remove(it) } }
            Toast.makeText(this, "Zwischenablage-Verlauf gelöscht", Toast.LENGTH_SHORT).show()
        }

        // ---- Ausprobieren
        card("Ausprobieren")
        card!!.addView(EditText(this).apply {
            hint = "Hier tippen zum Testen …"
            setTextColor(textColor)
            setHintTextColor(dim)
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        })

        page.addView(TextView(this).apply {
            text = "Wortdaten: wordfreq (CC BY-SA 4.0), igerman98 (GPL), Tatoeba (CC BY 2.0 FR), Mozilla Common Voice (CC0). " +
                "Emojis: Reihenfolge von Google (Apache 2.0), Suchwörter Unicode CLDR, animierte Emojis Noto (CC BY 4.0). " +
                "Offline-Spracherkennung: whisper.cpp (MIT), Modelle von OpenAI (MIT). Englische Rechtschreibung: SCOWL."
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(6f), dp(16f), dp(6f), 0)
        })
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ================================================================== Schlüssel

    /**
     * Schlüssel aus der Zwischenablage übernehmen. Groq-Schlüssel sind eindeutig erkennbar („gsk_…“);
     * GIPHY-Schlüssel nur, wenn gerade die GIPHY-Seite geöffnet wurde oder der Knopf gedrückt wird.
     */
    private fun takeFromClipboard(force: String? = null): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (_: Exception) { null } ?: return false
        if (GROQ.matches(text) && force != "giphy" && text != prefs.groqKey && (force == "groq" || awaitingGroq || prefs.groqKey.isBlank())) {
            prefs.groqKey = text
            awaitingGroq = false
            Toast.makeText(this, "Groq-Schlüssel übernommen ✓", Toast.LENGTH_SHORT).show()
            build(); check("groq")
            return true
        }
        if (GIPHY.matches(text) && text != prefs.giphyKey && (force == "giphy" || awaitingGiphy)) {
            prefs.giphyKey = text
            awaitingGiphy = false
            Toast.makeText(this, "GIPHY-Schlüssel übernommen ✓", Toast.LENGTH_SHORT).show()
            build(); check("giphy")
            return true
        }
        return false
    }

    /** Kein passender Schlüssel in der Zwischenablage: von Hand eingeben. */
    private fun manualKey(kind: String) {
        val field = EditText(this).apply {
            hint = if (kind == "groq") "gsk_…" else "GIPHY API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Schlüssel eingeben")
            .setMessage("In der Zwischenablage ist kein passender Schlüssel. Du kannst ihn hier einfügen:")
            .setView(field)
            .setPositiveButton("Speichern") { _, _ ->
                val v = field.text.toString().trim()
                if (v.isNotEmpty()) {
                    if (kind == "groq") prefs.groqKey = v else prefs.giphyKey = v
                    build(); check(kind)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Prüft im Hintergrund, ob der Schlüssel angenommen wird. */
    private fun check(kind: String) {
        val key = if (kind == "groq") prefs.groqKey else prefs.giphyKey
        if (key.isBlank()) return
        Thread {
            val ok = try {
                val c = (if (kind == "groq") URL("https://api.groq.com/openai/v1/models")
                else URL("https://api.giphy.com/v1/gifs/trending?limit=1&api_key=" + Uri.encode(key))).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                if (kind == "groq") c.setRequestProperty("Authorization", "Bearer $key")
                when (c.responseCode) { 200 -> true; 401, 403 -> false; else -> null }
            } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (kind == "groq") groqOk = ok else giphyOk = ok
                build()
            }
        }.start()
    }

    // ================================================================== Ablauf

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Zurück aus dem Browser: Schlüssel automatisch übernehmen, Einrichtungs-Schritte neu prüfen
        if (!takeFromClipboard()) build()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_STICKERS || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        data.data?.let { if (it !in uris) uris += it }
        val store = StickerStore(this)
        Thread {
            val ok = uris.count { store.import(it) }
            runOnUiThread {
                Toast.makeText(this, "$ok Sticker hinzugefügt", Toast.LENGTH_SHORT).show()
                build()
            }
        }.start()
    }

    private fun askMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && intent.getBooleanExtra(EXTRA_ASK_MIC, false)) {
            Toast.makeText(this, "Mikrofon erlaubt ✓ – jetzt kannst du diktieren", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {
            Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun spellCheckerActive(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            val tsm = getSystemService(TextServicesManager::class.java)
            tsm != null && tsm.isSpellCheckerEnabled && tsm.currentSpellCheckerInfo?.packageName == packageName
        } else {
            (Settings.Secure.getString(contentResolver, "selected_spell_checker") ?: "").startsWith("$packageName/")
        }
    } catch (_: Exception) { false }

    private fun openSpellCheckerSettings() {
        val tries = listOf(
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity"),
            ),
            Intent("android.settings.SPELL_CHECKER_SETTINGS"),
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
        )
        for (i in tries) {
            try { startActivity(i); break } catch (_: Exception) { }
        }
        Toast.makeText(this, "„Rechtschreibprüfung“ öffnen und dort „KlarTastatur“ als Standard wählen", Toast.LENGTH_LONG).show()
    }

    // ================================================================== Bausteine

    private fun card(title: String? = null): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(18f).toFloat() }
        }
        page.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12f)
        })
        card = c
        if (title != null) c.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6f))
        })
        return c
    }

    private fun text(s: String, size: Float, color: Int, top: Float = 4f, bottom: Float = 4f) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(top), 0, dp(bottom))
    }.also { card!!.addView(it) }

    private fun body(s: String) = text(s, 15f, textColor)
    private fun hint(s: String) = text(s, 13f, dim, 2f, 6f)
    private fun label(s: String) = text(s, 16f, textColor, 10f, 0f)
    private fun status(s: String, color: Int) = text(s, 15f, color, 2f, 6f).apply { setTypeface(null, Typeface.BOLD) }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) {
        card!!.addView(TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) accentText else accent)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#55FFFFFF")),
                GradientDrawable().apply {
                    cornerRadius = dp(24f).toFloat()
                    if (primary) setColor(accent) else { setColor(Color.TRANSPARENT); setStroke(dp(1f), Color.parseColor("#5F6368")) }
                }, null,
            )
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })
    }

    /** Ein Einrichtungs-Schritt: erledigt = grüner Haken, sonst antippbar. */
    private fun step(number: Int, label: String, done: Boolean, onClick: () -> Unit) {
        if (done) { text("✓  $label", 15f, good, 6f, 6f); return }
        button("$number.  $label", primary = true, onClick = onClick)
    }

    @Suppress("DEPRECATION")
    private fun switch(s: String, value: Boolean, onChange: (Boolean) -> Unit) {
        card!!.addView(Switch(this).apply {
            text = s
            isChecked = value
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(9f), 0, dp(5f))
            setOnCheckedChangeListener { _: CompoundButton, c: Boolean -> onChange(c) }
        })
    }

    /** Regler 0..100 in 5er-Schritten mit Anzeige des Werts. */
    private fun slider(title: String, value: Int, show: (Int) -> String, onChange: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, 0) }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = TextView(this).apply {
            text = show(value)
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        row.addView(valueView)
        card!!.addView(row)
        card!!.addView(SeekBar(this).apply {
            max = 100
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var last = value
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (p + 2) / 5 * 5
                    if (v == last) return
                    last = v
                    valueView.text = show(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f); bottomMargin = dp(4f)
        })
    }

    companion object {
        const val EXTRA_ASK_MIC = "ask_mic"
        const val EXTRA_PAGE = "page"
        const val PAGE_VOICE = "voice"
        const val PAGE_MEDIA = "media"
        private const val REQ_STICKERS = 7
        val GROQ = Regex("^gsk_[A-Za-z0-9]{20,}$")
        val GIPHY = Regex("^[A-Za-z0-9]{32}$")
    }
}
