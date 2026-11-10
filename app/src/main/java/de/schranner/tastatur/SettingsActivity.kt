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
import android.view.View
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

/** Einrichtung und Einstellungen – alles auf einer Seite. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private lateinit var stepEnable: Button
    private lateinit var stepSelect: Button
    private lateinit var stepMic: Button

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

        title("KlarTastatur")
        text("Deutsche Tastatur mit besserer Spracherkennung, Zwischenablage-Verlauf, Emojis und Tippfehler-Markierung. Es wird nichts automatisch geändert: Fehler werden rot markiert, Vorschläge erscheinen oben.")

        heading("1. Einrichten")
        stepEnable = button("Tastatur aktivieren") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        stepSelect = button("Als Tastatur auswählen") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        stepMic = button("Mikrofon erlauben (zum Diktieren)") { askMic() }

        heading("2. Bessere Spracherkennung (kostenlos)")
        text("Für die beste Erkennung nutzt die Tastatur Whisper von OpenAI über den Dienst Groq. Das ist kostenlos (großzügiges Tageslimit), du brauchst nur einen eigenen Schlüssel:\n\n" +
            "1. Auf console.groq.com mit Google-Konto anmelden\n" +
            "2. Links auf „API Keys“ → „Create API Key“\n" +
            "3. Schlüssel kopieren und unten einfügen\n\n" +
            "Ohne Schlüssel wird die normale Android-Spracherkennung verwendet. Beim Diktieren wird die Aufnahme an Groq geschickt.")
        button("console.groq.com öffnen") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }
        val keyField = EditText(this).apply {
            hint = "gsk_…"
            setText(prefs.groqKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        col.addView(keyField)
        button("Schlüssel speichern") {
            prefs.groqKey = keyField.text.toString()
            Toast.makeText(this, if (prefs.groqKey.isBlank()) "Schlüssel entfernt" else "Gespeichert ✓", Toast.LENGTH_SHORT).show()
        }
        switch("Sprache automatisch erkennen (statt nur Deutsch)", prefs.voiceLanguage == "auto") {
            prefs.voiceLanguage = if (it) "auto" else "de"
        }

        heading("3. Einstellungen")
        switch("Tippfehler rot markieren", prefs.markTypos) { prefs.markTypos = it }
        switch("Emoji-Vorschläge beim Tippen", prefs.emojiSuggestions) { prefs.emojiSuggestions = it }
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

        heading("Ausprobieren")
        col.addView(EditText(this).apply {
            hint = "Hier tippen zum Testen …"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        })

        heading("Tipps")
        text("• Leertaste gedrückt halten und wischen: Cursor bewegen\n" +
            "• Auf ⌫ nach links wischen: ganze Wörter löschen\n" +
            "• Taste lang drücken: Umlaute, Ziffern, Sonderzeichen\n" +
            "• Zweimal Leertaste: Punkt\n" +
            "• Zweimal ⇧: Feststelltaste\n" +
            "• Rot markiertes Wort: Korrektur oben antippen – oder „+“ zum Wörterbuch hinzufügen\n" +
            "• Leertaste lang drücken: Tastatur wechseln\n" +
            "• ☺ lang drücken: diese Einstellungen")

        heading("Mein Wörterbuch")
        val engine = SuggestionEngine(this)
        val dictInfo = text("Wird geladen …")
        Thread {
            engine.loadAsync()
            while (!engine.isReady()) Thread.sleep(50)
            val words = engine.userWordList()
            runOnUiThread {
                dictInfo.text = if (words.isEmpty()) "Noch keine eigenen Wörter gelernt."
                else "${words.size} eigene Wörter: " + words.take(80).joinToString(", ") + if (words.size > 80) " …" else ""
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

        if (intent.getBooleanExtra(EXTRA_ASK_MIC, false)) askMic()
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSteps()
    }

    private fun refreshSteps() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        mark(stepEnable, "Tastatur aktivieren", enabled)
        mark(stepSelect, "Als Tastatur auswählen", selected)
        mark(stepMic, "Mikrofon erlauben (zum Diktieren)", mic)
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

    // ---- kleine Bausteine ----

    private fun title(s: String) = col.addView(TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTypeface(null, Typeface.BOLD)
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

    private fun button(s: String, onClick: () -> Unit): Button = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }.also {
        col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    @Suppress("DEPRECATION")
    private fun switch(s: String, value: Boolean, onChange: (Boolean) -> Unit) {
        col.addView(Switch(this).apply {
            text = s
            isChecked = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(10f), 0, dp(10f))
            setOnCheckedChangeListener { _: CompoundButton, c: Boolean -> onChange(c) }
        })
    }

    companion object {
        const val EXTRA_ASK_MIC = "ask_mic"
    }
}
