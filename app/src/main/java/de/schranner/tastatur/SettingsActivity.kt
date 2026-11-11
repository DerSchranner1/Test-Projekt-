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

        pageMain()
        if (intent.getBooleanExtra(EXTRA_ASK_MIC, false)) askMic()
    }

    // ================================================================== Seiten

    private fun pageMain() {
        title("KlarTastatur")

        heading("Einrichten")
        stepEnable = button("Tastatur aktivieren") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        stepSelect = button("Als Tastatur auswählen") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        stepMic = button("Mikrofon erlauben") { askMic() }

        heading("Einstellungen")
        switch("Wischen zum Schreiben", prefs.gestureTyping) { prefs.gestureTyping = it }
        switch("Autokorrektur", prefs.autocorrect) { prefs.autocorrect = it }
        hint("Aus: Tippfehler werden nur rot markiert. An: eindeutige Fehler werden ersetzt, ⌫ macht es rückgängig.")
        switch("Zahlenreihe", prefs.numberRow) { prefs.numberRow = it }
        switch("Vibration", prefs.vibrate) { prefs.vibrate = it }

        heading("Spracheingabe")
        hint("Kostenloser Schlüssel von console.groq.com → „API Keys“. Ohne Schlüssel: Android-Spracherkennung.")
        link("Schlüssel holen") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
        keyField("Groq-Schlüssel (gsk_…)", prefs.groqKey) { prefs.groqKey = it }

        heading("GIFs & Sticker")
        hint("Für GIFs: kostenloser Schlüssel von developers.giphy.com → „Create an App“ → „API“.")
        link("Schlüssel holen") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.giphy.com/dashboard/"))) }
        keyField("GIPHY-Schlüssel", prefs.giphyKey) { prefs.giphyKey = it }
        stickerInfo = hint("")
        updateStickerInfo()
        button("Eigene Sticker aus Galerie hinzufügen") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, REQ_STICKERS)
        }

        heading("Ausprobieren")
        testField()
    }

    private fun link(label: String, onClick: () -> Unit) = col.addView(TextView(this).apply {
        text = label
        setTextColor(android.graphics.Color.parseColor("#A8C7FA"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(2f), 0, dp(6f))
        setOnClickListener { onClick() }
    })

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
        stickerInfo?.text = if (n == 0) "Noch keine eigenen Sticker." else "$n eigene Sticker (in der Tastatur lang drücken zum Löschen)."
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
