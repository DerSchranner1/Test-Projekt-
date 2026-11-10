package de.schranner.tastatur

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Einrichtung und Einstellungen – Hauptseite mit Unterseiten. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private var stepEnable: Button? = null
    private var stepSelect: Button? = null
    private var stepMic: Button? = null
    private var stickerInfo: TextView? = null

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(24f), dp(20f), dp(40f))
        }
        scroll.addView(col)
        scroll.fitsSystemWindows = true
        setContentView(scroll)

        when (intent.getStringExtra(EXTRA_PAGE)) {
            PAGE_TYPING -> pageTyping()
            PAGE_VOICE -> pageVoice()
            PAGE_MEDIA -> pageMedia()
            PAGE_LOOK -> pageLook()
            PAGE_DICT -> pageDict()
            PAGE_TIPS -> pageTips()
            else -> pageMain()
        }
        if (intent.getBooleanExtra(EXTRA_ASK_MIC, false)) askMic()
    }

    // ================================================================== Seiten

    private fun pageMain() {
        title("KlarTastatur")
        text("Deutsche Tastatur mit Wischen, besserer Spracherkennung, Zwischenablage-Verlauf, Emojis, GIFs und Stickern.")

        heading("Einrichten")
        stepEnable = button("Tastatur aktivieren") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        stepSelect = button("Als Tastatur auswählen") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        stepMic = button("Mikrofon erlauben (zum Diktieren)") { askMic() }

        heading("Einstellungen")
        entry("⌨️  Tippen & Korrektur", "Wischen, Autokorrektur, Vorschläge, Großschreibung") { open(PAGE_TYPING) }
        entry("🎤  Spracheingabe", if (prefs.groqKey.isBlank()) "Noch kein Groq-Schlüssel – einrichten" else "Whisper aktiv ✓") { open(PAGE_VOICE) }
        entry("🎞️  GIFs & Sticker", if (prefs.giphyKey.isBlank()) "GIPHY-Schlüssel, eigene Sticker" else "GIPHY aktiv ✓ – eigene Sticker") { open(PAGE_MEDIA) }
        entry("🎨  Aussehen & Bedienung", "Zahlenreihe, Tastenhöhe, Vibration") { open(PAGE_LOOK) }
        entry("📖  Mein Wörterbuch", "Gelernte Wörter ansehen oder löschen") { open(PAGE_DICT) }
        entry("💡  Tipps", "Alle Gesten und Tricks") { open(PAGE_TIPS) }

        heading("Ausprobieren")
        testField()
    }

    private fun pageTyping() {
        title("Tippen & Korrektur")

        heading("Wischen zum Schreiben")
        switch("Wischen zum Schreiben", prefs.gestureTyping) { prefs.gestureTyping = it }
        hint("Finger von Buchstabe zu Buchstabe ziehen, ohne abzusetzen. Oben erscheinen Alternativen, ⌫ löscht das ganze gewischte Wort.")
        switch("Wischspur anzeigen", prefs.gestureTrail) { prefs.gestureTrail = it }

        heading("Korrektur")
        switch("Autokorrektur", prefs.autocorrect) { prefs.autocorrect = it }
        hint("Eindeutige Tippfehler werden bei Leertaste oder Satzzeichen automatisch ersetzt. ⌫ direkt danach macht die Korrektur rückgängig. Aus: nichts wird automatisch geändert.")
        switch("Tippfehler rot markieren", prefs.markTypos) { prefs.markTypos = it }
        hint("Unbekannte Wörter werden rot unterstrichen, Korrekturen erscheinen oben zum Antippen.")

        heading("Vorschläge")
        switch("Wortvorschläge anzeigen", prefs.showSuggestions) { prefs.showSuggestions = it }
        switch("Emoji-Vorschläge beim Tippen", prefs.emojiSuggestions) { prefs.emojiSuggestions = it }

        heading("Automatisch")
        switch("Großschreibung am Satzanfang", prefs.autoCaps) { prefs.autoCaps = it }
        switch("Zweimal Leertaste = Punkt", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }

        heading("Ausprobieren")
        testField()
    }

    private fun pageVoice() {
        title("Spracheingabe")
        text("Für die beste Erkennung nutzt die Tastatur Whisper (large-v3) über den Dienst Groq. Das ist kostenlos (großzügiges Tageslimit), du brauchst nur einen eigenen Schlüssel:\n\n" +
            "1. Auf console.groq.com mit Google-Konto anmelden\n" +
            "2. „API Keys“ → „Create API Key“\n" +
            "3. Schlüssel kopieren und unten einfügen\n\n" +
            "Ohne Schlüssel wird die normale Android-Spracherkennung verwendet. Beim Diktieren mit Schlüssel wird die Aufnahme an Groq geschickt.")
        button("console.groq.com öffnen") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }
        keyField("gsk_…", prefs.groqKey) { prefs.groqKey = it }
        heading("Optionen")
        stepMic = button("Mikrofon erlauben") { askMic() }
        switch("Sprache automatisch erkennen (statt nur Deutsch)", prefs.voiceLanguage == "auto") {
            prefs.voiceLanguage = if (it) "auto" else "de"
        }
    }

    private fun pageMedia() {
        title("GIFs & Sticker")
        heading("GIFs und GIPHY-Sticker")
        text("GIFs kommen von GIPHY (Googles Tenor wurde 2026 abgeschaltet). Dafür brauchst du einen kostenlosen Schlüssel:\n\n" +
            "1. developers.giphy.com öffnen und kostenloses Konto anlegen\n" +
            "2. „Create an App“ → „API“ wählen → Namen eingeben\n" +
            "3. Den „API Key“ kopieren und unten einfügen\n\n" +
            "Der kostenlose Schlüssel erlaubt 100 Suchen pro Stunde – für den Alltag genug.")
        button("developers.giphy.com öffnen") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.giphy.com/dashboard/")))
        }
        keyField("GIPHY API Key", prefs.giphyKey) { prefs.giphyKey = it }

        heading("Eigene Sticker")
        text("Nimm beliebige Bilder aus deiner Galerie als Sticker (auch animierte GIFs). Sie liegen nur auf deinem Handy. In der Tastatur: ☺ → Sticker → Eigene. Lang drücken löscht einen Sticker.")
        stickerInfo = text("")
        updateStickerInfo()
        button("Bilder als Sticker hinzufügen") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, REQ_STICKERS)
        }
        button("Alle eigenen Sticker löschen") {
            StickerStore(this).clear(); updateStickerInfo()
        }
        hint("Emoji-Sticker (große Emojis als Bild) gibt es ohne Einrichtung. Ob eine App GIFs oder Sticker als Bild annimmt, entscheidet die App – sonst wird ein Link bzw. das Emoji eingefügt.")
    }

    private fun pageLook() {
        title("Aussehen & Bedienung")
        switch("Zahlenreihe immer anzeigen", prefs.numberRow) { prefs.numberRow = it }
        switch("Vibration beim Tippen", prefs.vibrate) { prefs.vibrate = it }
        text("Tastenhöhe")
        col.addView(SeekBar(this).apply {
            max = 50
            progress = prefs.keyHeightPercent - 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { prefs.keyHeightPercent = 80 + p }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        hint("Helles und dunkles Design folgen automatisch der Systemeinstellung.")
        heading("Ausprobieren")
        testField()
    }

    private fun pageDict() {
        title("Mein Wörterbuch")
        text("Die Tastatur lernt Wörter, die du mindestens zweimal schreibst, und merkt sich, welches Wort du oft nach welchem schreibst. Alles bleibt auf deinem Handy.")
        val engine = SuggestionEngine(this)
        val dictInfo = text("Wird geladen …")
        Thread {
            engine.loadAsync()
            while (!engine.isReady()) Thread.sleep(50)
            val words = engine.userWordList()
            runOnUiThread {
                dictInfo.text = if (words.isEmpty()) "Noch keine eigenen Wörter gelernt."
                else "${words.size} eigene Wörter: " + words.take(150).joinToString(", ") + if (words.size > 150) " …" else ""
            }
        }.start()
        button("Gelernte Wörter löschen") {
            engine.clearUserData()
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putLong("user_data_cleared", System.currentTimeMillis()).apply()
            dictInfo.text = "Gelöscht."
        }
        text("\nWörterbuch: wordfreq (CC BY-SA 4.0) und igerman98 (GPL).").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
    }

    private fun pageTips() {
        title("Tipps")
        text("• Wischen: Finger über die Buchstaben ziehen\n" +
            "• Leertaste gedrückt halten und wischen: Cursor bewegen\n" +
            "• Auf ⌫ nach links wischen: ganze Wörter löschen\n" +
            "• Taste lang drücken: Umlaute, Ziffern, Sonderzeichen\n" +
            "• Zweimal Leertaste: Punkt\n" +
            "• Zweimal ⇧: Feststelltaste\n" +
            "• Rot markiertes Wort: Korrektur oben antippen – oder „+“ zum Wörterbuch hinzufügen\n" +
            "• Vorschlag lang drücken: Wort ins Wörterbuch aufnehmen\n" +
            "• 📋 oben links: Zwischenablage, 📌 heftet an\n" +
            "• ☺: Emojis, GIFs und Sticker\n" +
            "• ☺ lang drücken: Einstellungen\n" +
            "• Leertaste lang drücken: Tastatur wechseln")
    }

    // ================================================================== Ablauf

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSteps()
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
                updateStickerInfo()
            }
        }.start()
    }

    private fun updateStickerInfo() {
        val n = StickerStore(this).list().size
        stickerInfo?.text = if (n == 0) "Noch keine eigenen Sticker." else "$n eigene Sticker."
    }

    private fun refreshSteps() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        stepEnable?.let { mark(it, "Tastatur aktivieren", enabled) }
        stepSelect?.let { mark(it, "Als Tastatur auswählen", selected) }
        stepMic?.let { mark(it, "Mikrofon erlauben (zum Diktieren)", mic) }
    }

    private fun mark(b: Button, label: String, done: Boolean) {
        b.text = if (done) "✓  $label" else label
        b.alpha = if (done) 0.6f else 1f
    }

    private fun askMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSteps()
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && intent.getBooleanExtra(EXTRA_ASK_MIC, false)) {
            Toast.makeText(this, "Mikrofon erlaubt ✓ – jetzt kannst du diktieren", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun open(page: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page))
    }

    // ================================================================== Bausteine

    private fun title(s: String) = col.addView(TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8f))
    })

    private fun heading(s: String) = col.addView(TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(24f), 0, dp(8f))
    })

    private fun text(s: String): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(4f), 0, dp(4f))
        setLineSpacing(0f, 1.15f)
    }.also { col.addView(it) }

    private fun hint(s: String) = text(s).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        alpha = 0.7f
        setPadding(0, 0, 0, dp(8f))
    }

    private fun button(s: String, onClick: () -> Unit): Button = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }.also {
        col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Eintrag, der eine Unterseite öffnet. */
    private fun entry(label: String, sub: String, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
            isClickable = true
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        box.addView(TextView(this).apply {
            text = "$label  ›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        box.addView(TextView(this).apply {
            text = sub
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.65f
        })
        col.addView(box)
    }

    private fun keyField(hintText: String, value: String, save: (String) -> Unit) {
        val f = EditText(this).apply {
            hint = hintText
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        col.addView(f)
        button("Schlüssel speichern") {
            save(f.text.toString())
            Toast.makeText(this, if (f.text.isBlank()) "Schlüssel entfernt" else "Gespeichert ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testField() = col.addView(EditText(this).apply {
        hint = "Hier tippen zum Testen …"
        minLines = 3
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    })

    @Suppress("DEPRECATION")
    private fun switch(s: String, value: Boolean, onChange: (Boolean) -> Unit) {
        col.addView(Switch(this).apply {
            text = s
            isChecked = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(10f), 0, dp(4f))
            setOnCheckedChangeListener { _: CompoundButton, c: Boolean -> onChange(c) }
        })
    }

    companion object {
        const val EXTRA_ASK_MIC = "ask_mic"
        const val EXTRA_PAGE = "page"
        const val PAGE_TYPING = "typing"
        const val PAGE_VOICE = "voice"
        const val PAGE_MEDIA = "media"
        const val PAGE_LOOK = "look"
        const val PAGE_DICT = "dict"
        const val PAGE_TIPS = "tips"
        private const val REQ_STICKERS = 7
    }
}
