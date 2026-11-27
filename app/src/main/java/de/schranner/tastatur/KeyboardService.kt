package de.schranner.tastatur

import android.Manifest
import android.content.ClipDescription
import android.graphics.Bitmap
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import java.io.File
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.SuggestionSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.BreakIterator
import java.util.concurrent.Executors

class KeyboardService : InputMethodService(), KeyboardView.Listener, VoiceInput.Callback, MediaPanel.Host {

    companion object {
        /** So viele Zeichen vor dem Cursor werden für Vorschläge angesehen. */
        private const val CONTEXT_CHARS = 120
        /** So weit zurück wird nach früheren Tippfehlern gesucht. */
        const val TYPO_SCAN_CHARS = 400
        /** Höhe der Leiste über den Tasten in dp (wie bei Gboard). */
        private const val BAR_DP = 43f
    }

    private enum class Panel { KEYBOARD, MEDIA, CLIPBOARD, EDIT }
    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD }

    /** Ein Eintrag in der Vorschlagsleiste. */
    private sealed class Sugg(val label: String) {
        class Word(val word: String, label: String = word) : Sugg(label)
        class Correction(val target: String, val replacement: String) : Sugg(replacement)
        class AddWord(val word: String) : Sugg(word)
        /** Blau unterkringelt: so stehen lassen (Hinweis verwerfen). */
        class Dismiss(val word: String) : Sugg(word)
        /** Rot unterkringelt: ein Tippfehler weiter vorne im Text – antippen zeigt Verbesserungen. */
        class EarlierTypo(val word: String) : Sugg(word)
        /** @param replace darf das Wort ersetzen (Emoji steht für genau dieses Wort, nicht für eine Wendung) */
        class Emoji(val emoji: String, val word: String? = null, val replace: Boolean = false) : Sugg(emoji)
        /** Smiley aus Zeichen („:)“) durch ein echtes Emoji ersetzen. */
        class EmojiReplace(val emoticon: String, val emoji: String) : Sugg(emoji)
        /** Auswahl nach langem Druck auf ein Emoji: ersetzen, dahinter setzen oder mehr Emojis. */
        class EmojiChoice(val emoji: String, val word: String, val replace: Boolean, label: String) : Sugg(label)
        class EmojiMore(val word: String) : Sugg("Mehr …")
        class Paste(val text: String) : Sugg(ClipboardStore.smartLabel(text))
    }

    private lateinit var prefs: Prefs
    private lateinit var theme: KeyboardTheme
    private lateinit var engine: SuggestionEngine
    private lateinit var clip: ClipboardStore
    private lateinit var voice: VoiceInput
    private lateinit var haptics: Haptics
    private lateinit var decoder: GestureDecoder
    private lateinit var giphy: GiphyClient
    private lateinit var stickers: StickerStore
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var suggestToken = 0
    /** Selbst benutzte Emojis nach Wörtern („pizza“ -> 🍕🤤). */
    private var emojiLearned: Map<String, String> = emptyMap()

    // Views
    private lateinit var keyboard: KeyboardView
    private lateinit var content: FrameLayout
    private lateinit var suggRow: LinearLayout
    private lateinit var voiceRow: LinearLayout
    private lateinit var voiceStatus: TextView
    private lateinit var levelView: LevelView
    private val slots = arrayOfNulls<TextView>(3)
    private var shown = arrayOfNulls<Sugg>(3)
    private var mediaPanel: MediaPanel? = null
    private lateinit var searchRow: LinearLayout
    private lateinit var searchHits: LinearLayout
    private lateinit var searchHitsScroll: android.widget.HorizontalScrollView
    private lateinit var searchButton: TextView
    private lateinit var searchText: TextView
    private var clipPanel: View? = null
    private var clipAdapter: ClipAdapter? = null
    private var editPanel: View? = null
    private var selectCell: TextView? = null
    private var resizeOverlay: View? = null
    private lateinit var root: LinearLayout
    private lateinit var mainCol: LinearLayout
    private lateinit var bar: View
    private lateinit var sideLeft: View
    private lateinit var sideRight: View
    private lateinit var toolRow: LinearLayout
    private lateinit var resizeRow: LinearLayout
    private lateinit var resizeLabel: TextView
    private var panel = Panel.KEYBOARD

    // Zustand
    private var mode = Mode.LETTERS
    private var field = FieldKind.TEXT
    private var suggestionsOn = true
    private var marksInText = true
    private val composing = StringBuilder()
    private var composingPrev: String? = null
    private var composingPrev2: String? = null
    /** Werkzeugleiste statt Vorschlägen: null = automatisch (leeres Feld), sonst vom Nutzer gewählt. */
    private var toolbarForced: Boolean? = null
    private var fieldEmpty = true
    private var voiceUi = false
    private var resizing = false
    private var navInset = 0
    private var selecting = false
    private var assistToken = 0
    private var composingSentenceStart = false
    private var correctionTarget: String? = null
        set(v) { field = v; correctionEarlier = false; if (v == null) correctionFix = null }
    /** Der Fehler steht weiter vorne im Text (nicht direkt vor dem Cursor). */
    private var correctionEarlier = false
    /** Fehler, die in diesem Feld bewusst stehen bleiben sollen (langer Druck). */
    private val ignoredTypos = HashSet<String>()
    /** Bei einem Grammatik-Hinweis: die richtige Schreibweise des früheren Worts. */
    private var correctionFix: String? = null
    private var correctionPrev: String? = null
    private var lastAutoSpace = false
    private var lastSpaceTime = 0L
    private var lastShiftTime = 0L
    private var lastEditTime = 0L
    private var engineCreatedAt = System.currentTimeMillis()
    /** Alternativen zum zuletzt gewischten Wort. */
    private var gestureAlts: List<String>? = null
    private var lastWasGesture = false
    /** Letzte Autokorrektur (Original, Korrektur) – ⌫ macht sie rückgängig. */
    private var lastAutocorrect: Pair<String, String>? = null
    /** GIF-/Sticker-Suche: Tastatur tippt in das Suchfeld statt in die App. */
    private var searchTarget: MediaPanel.Tab? = null
    private val searchQuery = StringBuilder()

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ================================================================== Lebenszyklus

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        engine = EngineHolder.get(this)
        haptics = Haptics(this)
        // Emoji-Daten im Hintergrund vorbereiten (für Vorschläge und den Emoji-Bereich)
        worker.execute { EmojiData.ensureLoaded(this) }
        clip = ClipboardStore(this)
        clip.onNewClip = { main.post { if (composing.isEmpty()) refreshSuggestions() } }
        clip.start()
        voice = VoiceInput(this, prefs).also { it.callback = this }
        decoder = GestureDecoder(engine)
        giphy = GiphyClient(prefs)
        stickers = StickerStore(this)
    }

    override fun onDestroy() {
        clip.stop()
        voice.cancel()
        engine.save()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        theme = KeyboardTheme(this)
        mediaPanel = null
        clipPanel = null
        editPanel = null
        resizeOverlay = null
        resizing = false
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
        }
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mainCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = buildBar()
        mainCol.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_DP)))
        keyboard = KeyboardView(this, theme).also { it.listener = this }
        content = FrameLayout(this)
        content.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mainCol.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Einhandmodus: schmale Tastatur links oder rechts, daneben ein Streifen mit zwei Knöpfen
        sideLeft = buildSide(left = true)
        sideRight = buildSide(left = false)
        hRow.addView(sideLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 18f))
        hRow.addView(mainCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 82f))
        hRow.addView(sideRight, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 18f))
        root.addView(hRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Platz für die Navigationsleiste lassen (sonst liegen deren Knöpfe auf der untersten Tastenreihe)
        root.setOnApplyWindowInsetsListener { _, insets ->
            navInset = bottomInset(insets)
            applyBottomPadding()
            insets
        }
        applyOneHanded()
        applyBottomPadding()
        tintNavigationBar()
        return root
    }

    private fun bottomInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).bottom
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }

    private fun applyBottomPadding() {
        if (!::root.isInitialized) return
        root.setPadding(0, 0, 0, navInset + dp(prefs.bottomPadDp.toFloat()))
    }

    @Suppress("DEPRECATION")
    private fun tintNavigationBar() {
        try { window?.window?.navigationBarColor = theme.background } catch (_: Exception) { }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        correctionTarget = null
        lastAutoSpace = false
        ignoredTypos.clear()
        gestureAlts = null
        lastWasGesture = false
        lastAutocorrect = null
        endSearch()
        endResize()
        toolbarForced = null
        selecting = false
        haptics.strength = prefs.vibration
        keyboard.haptics = haptics
        // „Bairisch verstehen“ umgeschaltet? Dann das passende Wörterbuch nehmen
        EngineHolder.get(this).let { e -> if (e !== engine) { engine = e; decoder = GestureDecoder(e) } }
        // Datenschutz: nicht lernen, wenn abgeschaltet oder die App darum bittet (z. B. Inkognito-Fenster)
        val incognito = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        engine.learning = prefs.learnFromTyping && !incognito
        clip.enabled = prefs.clipboardHistory
        keyboard.keyHeightPercent = prefs.keyHeightPercent
        keyboard.spaceLabel = "DE · EN"
        keyboard.debugTouch = prefs.debugTouch
        applyOneHanded()
        applyBottomPadding()
        tintNavigationBar()

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        field = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
        if (cls == InputType.TYPE_CLASS_NUMBER && info.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0) {
            field = FieldKind.NUMBER
        }
        // Viele Webseiten melden „keine Vorschläge“ (z. B. autocomplete=off). Wie Gboard trotzdem anbieten,
        // wenn gewünscht – nie aber in Passwort-, E-Mail- oder Adressfeldern.
        val appWantsNone = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        suggestionsOn = cls == InputType.TYPE_CLASS_TEXT && field == FieldKind.TEXT &&
            (!appWantsNone || prefs.suggestEverywhere)
        // Markierungen im Text nur, wo die App Rechtschreibhilfe nicht abgeschaltet hat
        marksInText = !appWantsNone
        mode = if (field == FieldKind.NUMBER || field == FieldKind.PHONE) Mode.NUMPAD else Mode.LETTERS
        applyLayout()
        keyboard.enterLabel = enterLabel(info)
        // In den Einstellungen wurde „Gelernte Wörter löschen“ gedrückt?
        if (getSharedPreferences("settings", MODE_PRIVATE).getLong("user_data_cleared", 0L) > engineCreatedAt) {
            engine.clearUserData()
            EmojiData.forgetLearned(this)
            engineCreatedAt = System.currentTimeMillis()
        }
        voice.vocabulary = engine.topUserWords(60)
        emojiLearned = EmojiData.learned(this)
        clip.capture()
        showPanel(Panel.KEYBOARD)
        updateAutoShift()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (voice.isActive) voice.cancel()
        engine.save()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        composing.setLength(0)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Verspätete Meldungen direkt nach eigenen Eingaben ignorieren
        val stale = candidatesStart == -1 && SystemClock.uptimeMillis() - lastEditTime < 300
        if (composing.isNotEmpty() && !stale &&
            (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            // Cursor wurde vom Benutzer versetzt: Wort abschließen
            composing.setLength(0)
            currentInputConnection?.finishComposingText()
            correctionTarget = null
            refreshSuggestions()
        }
        if (composing.isEmpty() && newSelStart != oldSelStart) {
            updateAutoShift()
            if (!voice.isActive && newSelStart != oldSelStart + 1) {
                // Vom Nutzer versetzt (nicht durch eigenes Tippen): Wort am Cursor erneut prüfen
                if (newSelStart == newSelEnd && SystemClock.uptimeMillis() - lastEditTime > 300) {
                    correctionTarget = null
                    checkWordAtCursor()
                }
                refreshSuggestions()
            }
        }
    }

    private fun applyLayout() {
        val layout = KeyLayout.of(prefs.layout)
        val eszett = prefs.eszettKey
        keyboard.rows = when (mode) {
            Mode.LETTERS -> Layouts.letters(prefs.numberRow, field, layout, eszett)
            Mode.SYMBOLS -> Layouts.symbols()
            Mode.SYMBOLS2 -> Layouts.symbols2()
            Mode.NUMPAD -> Layouts.numpad(field == FieldKind.PHONE)
        }
        keyboard.referenceRows =
            if (mode == Mode.NUMPAD) null else Layouts.letters(prefs.numberRow, field, layout, eszett)
        SuggestionEngine.setKeyRows(Layouts.letterRows(layout, eszett))
        if (mode != Mode.LETTERS) keyboard.shift = ShiftState.OFF
        keyboard.gestureEnabled = prefs.gestureTyping && mode == Mode.LETTERS && (suggestionsOn || searchTarget != null)
        keyboard.gestureTrail = prefs.gestureTrail
    }

    private fun enterLabel(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "⏎"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_GO -> "➜"
            EditorInfo.IME_ACTION_NEXT -> "⇥"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "⏎"
        }
    }

    // ================================================================== Tastatur-Ereignisse

    override fun onChar(text: String) {
        if (searchTarget != null) {
            searchQuery.append(text)
            updateSearchUi()
            if (keyboard.shift == ShiftState.ON) keyboard.shift = ShiftState.OFF
            return
        }
        val ic = currentInputConnection ?: return
        keepVoicePartial()
        lastEditTime = SystemClock.uptimeMillis()
        lastAutocorrect = null
        toolbarForced = null
        if (lastWasGesture && composing.isNotEmpty() && text.singleOrNull()?.isLetter() == true) {
            // Nach einem gewischten Wort beginnt ein neues Wort
            commitWord()
            ic.commitText(" ", 1)
        }
        gestureAlts = null
        lastWasGesture = false
        val ch = text.singleOrNull()
        val wordChar = ch != null && (ch.isLetterOrDigit() || ((ch == '\'' || ch == '-') && composing.isNotEmpty()))
        if (wordChar && suggestionsOn) {
            if (composing.isEmpty()) {
                correctionTarget = null
                val ctx = contextBefore()
                composingPrev = ctx.prev
                composingPrev2 = ctx.prev2
                composingSentenceStart = ctx.start
                engine.setRecentWords(ctx.recent)
            }
            composing.append(text)
            ic.setComposingText(composing, 1)
            lastAutoSpace = false
        } else {
            commitWord(autocorrect = ch != null && ch in ".,!?;:)")
            val punct = ch != null && ch in ".,!?;:"
            val before = ic.getTextBeforeCursor(1, 0)
            if (punct && lastAutoSpace && before?.toString() == " ") {
                // „Hallo .“ -> „Hallo. “
                ic.deleteSurroundingText(1, 0)
                ic.commitText("$text ", 1)
                lastAutoSpace = true
            } else {
                ic.commitText(text, 1)
                lastAutoSpace = false
            }
        }
        lastSpaceTime = 0L
        if (keyboard.shift == ShiftState.ON) keyboard.shift = ShiftState.OFF
        if (!wordChar || !suggestionsOn) {
            updateAutoShift()
        }
        refreshSuggestions()
    }

    override fun onAction(type: KeyType) {
        if (searchTarget != null) {
            when (type) {
                KeyType.DELETE -> {
                    if (searchQuery.isNotEmpty()) searchQuery.setLength(searchQuery.length - 1)
                    updateSearchUi(); return
                }
                KeyType.SPACE -> { searchQuery.append(' '); updateSearchUi(); return }
                KeyType.ENTER -> { runSearch(); return }
                KeyType.EMOJI -> return
                else -> {}
            }
        }
        if (type != KeyType.SHIFT) keepVoicePartial()
        if (type != KeyType.DELETE && type != KeyType.SHIFT) lastAutocorrect = null
        if (type != KeyType.DELETE && type != KeyType.SHIFT) { lastWasGesture = false; gestureAlts = null }
        when (type) {
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                keyboard.shift = when (keyboard.shift) {
                    ShiftState.OFF -> ShiftState.ON
                    ShiftState.ON -> if (now - lastShiftTime < 400) ShiftState.LOCK else ShiftState.OFF
                    ShiftState.LOCK -> ShiftState.OFF
                }
                lastShiftTime = now
            }
            KeyType.DELETE -> handleDelete()
            KeyType.ENTER -> handleEnter()
            KeyType.SPACE -> handleSpace()
            KeyType.SYMBOLS -> { mode = Mode.SYMBOLS; applyLayout() }
            KeyType.SYMBOLS2 -> { mode = Mode.SYMBOLS2; applyLayout() }
            KeyType.LETTERS -> {
                mode = Mode.LETTERS
                applyLayout(); updateAutoShift()
            }
            KeyType.EMOJI -> showPanel(Panel.MEDIA)
            KeyType.CHAR -> {}
        }
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        lastEditTime = SystemClock.uptimeMillis()
        val now = SystemClock.uptimeMillis()
        if (composing.isEmpty() && suggestionsOn && prefs.doubleSpacePeriod && now - lastSpaceTime < 600) {
            // Doppelte Leertaste -> Punkt
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTime = 0L
                lastAutoSpace = true
                updateAutoShift(); refreshSuggestions()
                return
            }
        }
        commitWord(autocorrect = true)
        ic.commitText(" ", 1)
        lastSpaceTime = now
        lastAutoSpace = false
        if (mode == Mode.SYMBOLS || mode == Mode.SYMBOLS2) { mode = Mode.LETTERS; applyLayout() }
        updateAutoShift()
        refreshSuggestions()
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        commitWord(autocorrect = true)
        lastAutocorrect = null
        val ei = currentInputEditorInfo
        val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendKeyChar('\n')
        }
        lastAutoSpace = false
        correctionTarget = null
        updateAutoShift()
        refreshSuggestions()
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        lastEditTime = SystemClock.uptimeMillis()
        correctionTarget = null
        lastAutoSpace = false
        lastSpaceTime = 0L
        val undo = lastAutocorrect
        lastAutocorrect = null
        if (undo != null && composing.isEmpty() && undoAutocorrect(undo)) {
            updateAutoShift(); refreshSuggestions(); return
        }
        if (lastWasGesture && composing.isNotEmpty()) {
            // Gewischtes Wort komplett löschen
            composing.setLength(0)
            ic.commitText("", 1)
            lastWasGesture = false
            gestureAlts = null
            updateAutoShift(); refreshSuggestions(); return
        }
        gestureAlts = null
        if (composing.isNotEmpty()) {
            // Ganzes Zeichen löschen (auch Emoji / zusammengesetzte Zeichen)
            composing.setLength(composing.length - lastGraphemeLength(composing.toString()))
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
        } else {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(16, 0)?.toString() ?: ""
                if (before.isEmpty()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                } else {
                    val n = lastGraphemeLength(before)
                    val deleted = before.takeLast(n)
                    ic.deleteSurroundingText(n, 0)
                    if (suggestionsOn && deleted.isBlank()) recomposeWordBeforeCursor()
                }
            }
        }
        updateAutoShift()
        refreshSuggestions()
    }

    /** Nach dem Löschen eines Leerzeichens das Wort davor wieder bearbeitbar machen. */
    private fun recomposeWordBeforeCursor() {
        val ic = currentInputConnection ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.isNotEmpty() && after[0].isLetterOrDigit()) return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: return
        val m = Regex("[\\p{L}\\p{N}'-]+$").find(before) ?: return
        val word = m.value
        if (word.length > 30) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.setComposingText(word, 1)
        ic.endBatchEdit()
        composing.setLength(0)
        composing.append(word)
        val ctx = contextBefore(before.dropLast(word.length))
        composingPrev = ctx.prev
        composingPrev2 = ctx.prev2
        composingSentenceStart = ctx.start
        engine.setRecentWords(ctx.recent)
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
            val m = Regex("(\\S+\\s*|\\s+)$").find(before)
            if (m != null) ic.deleteSurroundingText(m.value.length, 0)
        }
        correctionTarget = null
        updateAutoShift()
        refreshSuggestions()
    }

    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            ic.finishComposingText()
        }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onCursorMoveVertical(steps: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            ic.finishComposingText()
        }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onEmojiLongPress() = openSettings(null, false)

    // ================================================================== Wörter & Vorschläge

    /**
     * Fertiges Wort übernehmen. Unbekannte Wörter werden rot markiert.
     * Ist die Autokorrektur eingeschaltet, werden eindeutige Tippfehler bei Leertaste/Satzzeichen ersetzt.
     */
    private fun commitWord(autocorrect: Boolean = false) {
        if (composing.isEmpty()) return
        val ic = currentInputConnection ?: return
        val w = composing.toString()
        composing.setLength(0)
        val prev = composingPrev
        val fromGesture = lastWasGesture
        lastWasGesture = false
        gestureAlts = null
        if (autocorrect && prefs.autocorrect && !fromGesture) {
            val fix = engine.autocorrectFor(w, prev, composingPrev2)
            if (fix != null && fix != w) {
                ic.commitText(fix, 1)
                lastAutocorrect = w to fix
                engine.learn(fix, prev, composingSentenceStart)
                return
            }
        }
        val known = engine.isKnown(w)
        val cap = if (prefs.markTypos && known) engine.capitalHint(composingPrev2, prev, w) else null
        if (prefs.markTypos && w.length > 1 && w.any { it.isLetter() } && !known) {
            val corr = engine.correctionsFor(w, prev)
            ic.commitText(marked(w, corr, grammar = false), 1)
            if (corr.isNotEmpty()) {
                correctionTarget = w
                correctionPrev = prev
            }
        } else if (cap != null && prefs.autocorrect) {
            // Autokorrektur an: gleich richtig groß schreiben
            ic.commitText(cap, 1)
            lastAutocorrect = w to cap
        } else if (cap != null) {
            // Blau: das Wort gibt es, aber hier gehört es groß
            ic.commitText(marked(w, listOf(cap), grammar = true), 1)
            correctionTarget = w
            correctionPrev = prev
            correctionFix = cap
        } else {
            ic.commitText(w, 1)
        }
        engine.learn(w, prev, composingSentenceStart)
        // „Das unterkringeln funktioniert“: das Wort davor war ein Hauptwort und gehört groß
        val fixed = if (cap == null) engine.nounFromVerb(composingPrev2, prev, w) else null
        if (fixed != null && prev != null) {
            if (prefs.autocorrect) replaceEarlierWord(prev, fixed)
            else {
                markEarlierWord(prev, fixed)
                correctionTarget = prev; correctionPrev = composingPrev2; correctionFix = fixed
            }
        }
    }

    /**
     * Wort mit Markierung des Systems: rot (unbekannt) oder blau (Grammatik, ab Android 12).
     * Apps, die Markierungen nicht zeigen, ignorieren sie – oben in der Leiste steht der Hinweis trotzdem.
     */
    private fun marked(w: String, suggestions: List<String>, grammar: Boolean): CharSequence {
        if (!marksInText) return w
        val flag = when {
            !grammar -> SuggestionSpan.FLAG_MISSPELLED
            Build.VERSION.SDK_INT >= 31 -> SuggestionSpan.FLAG_GRAMMAR_ERROR
            else -> return w
        }
        val sp = SpannableString(w)
        try {
            sp.setSpan(SuggestionSpan(this, suggestions.take(5).toTypedArray(), flag), 0, w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (_: Exception) { }
        return sp
    }

    /** Ein früheres Wort vor dem Cursor blau markieren (z. B. „Das unterkringeln funktioniert“). */
    private fun markEarlierWord(target: String, fix: String) {
        if (!marksInText || Build.VERSION.SDK_INT < 31) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: return
        val m = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(target) + "(?![\\p{L}\\p{N}])").findAll(before).lastOrNull() ?: return
        val tail = before.substring(m.range.last + 1)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - m.range.first, 0)
        ic.commitText(SpannableStringBuilder(marked(target, listOf(fix), grammar = true)).append(tail), 1)
        ic.endBatchEdit()
    }

    /**
     * Cursor wurde hinter ein Wort gesetzt (z. B. zum Nachbessern): das Wort erneut prüfen und
     * rot/blau mit Verbesserungen oben in der Leiste anzeigen.
     */
    private fun checkWordAtCursor() {
        if (!prefs.markTypos || !suggestionsOn || composing.isNotEmpty()) return
        val ic = currentInputConnection ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.isNotEmpty() && after[0].isLetterOrDigit()) return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: return
        val m = Regex("([\\p{L}][\\p{L}'’-]*)$").find(before) ?: return
        val w = m.groupValues[1]
        if (w.length < 2) return
        val ctx = TextContext.parse(before.substring(0, m.range.first), complete = before.length < CONTEXT_CHARS)
        correctionFix = null
        if (!engine.isKnown(w)) {
            correctionTarget = w; correctionPrev = ctx.prev
        } else {
            val cap = engine.capitalHint(ctx.prev2, ctx.prev, w) ?: return
            correctionTarget = w; correctionPrev = ctx.prev; correctionFix = cap
        }
    }

    /**
     * Letzter Tippfehler weiter vorne im Text (nicht das Wort direkt am Cursor), oder null.
     * Links, E-Mail-Adressen, Zahlen und bewusst ignorierte Wörter zählen nicht.
     */
    private fun findEarlierTypo(before: String): String? {
        if (before.isEmpty() || before.last().isLetterOrDigit()) return null
        val tokens = Regex("\\S+").findAll(before).map { it.value }.toList()
        for (raw in tokens.asReversed().take(60)) {
            if (raw.contains('@') || raw.contains('/') || raw.contains("www") || raw.any { it.isDigit() }) continue
            val w = raw.trim { !it.isLetter() }
            if (w.length < 3 || w.any { !it.isLetter() && it != '-' && it != '\'' && it != '’' }) continue
            if (w.all { it.isUpperCase() }) continue          // Abkürzungen
            if (w in ignoredTypos || engine.isKnown(w)) continue
            return w
        }
        return null
    }

    /** Ein früheres Wort vor dem Cursor ersetzen; alles danach bleibt stehen. */
    private fun replaceEarlierWord(target: String, replacement: String, window: Int = CONTEXT_CHARS) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(window, 0)?.toString() ?: return
        val m = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(target) + "(?![\\p{L}\\p{N}])").findAll(before).lastOrNull() ?: return
        val tail = before.substring(m.range.last + 1)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - m.range.first, 0)
        ic.commitText(replacement + tail, 1)
        ic.endBatchEdit()
    }

    /** Die Wörter vor dem Cursor (letztes Wort, das davor, Satzanfang?, Sprache). */
    private fun contextBefore(text: String? = null): TextContext {
        val before = text ?: currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
        return TextContext.parse(before, complete = before.length < CONTEXT_CHARS)
    }

    private fun refreshSuggestions() {
        if (!::keyboard.isInitialized || voice.isActive) return
        if (refining) {
            // Diktat wird gerade verbessert: Hinweis stehen lassen, solange der Text unverändert ist
            val old = lastVoiceInsert
            val before = old?.let { currentInputConnection?.getTextBeforeCursor(it.length, 0)?.toString() }
            if (composing.isEmpty() && before == old) return
            refining = false
        }
        fieldEmpty = composing.isEmpty() && (currentInputConnection?.getTextBeforeCursor(1, 0)?.isEmpty() ?: true)
        updateBar()
        updateTouchAssist()
        val token = ++suggestToken
        val typed = composing.toString()
        val target = correctionTarget
        val targetPrev = correctionPrev
        val fix = correctionFix
        val clipText = recentClip()

        if (searchTarget != null) return
        if (!suggestionsOn || !prefs.showSuggestions) {
            show(listOfNotNull(clipText?.let { Sugg.Paste(it) }))
            return
        }
        val alts = gestureAlts
        if (alts != null && typed.isNotEmpty()) {
            val others = alts.filter { !it.equals(typed, true) }
            show(listOfNotNull(others.getOrNull(0)?.let { Sugg.Word(it) }, Sugg.Word(typed), others.getOrNull(1)?.let { Sugg.Word(it) }))
            return
        }
        if (typed.isEmpty() && target == null) {
            val fullBefore = currentInputConnection?.getTextBeforeCursor(TYPO_SCAN_CHARS, 0)?.toString() ?: ""
            val before = fullBefore.takeLast(CONTEXT_CHARS)
            val ctx = contextBefore(before)
            engine.setRecentWords(ctx.recent)
            worker.execute {
                val preds = engine.predictNext(ctx.prev, ctx.start, ctx.prev2)
                val emo = emojiAfter(before)
                val earlier = if (prefs.markTypos) findEarlierTypo(fullBefore) else null
                main.post {
                    if (token != suggestToken) return@post
                    val items = mutableListOf<Sugg>()
                    if (clipText != null) items += Sugg.Paste(clipText)
                    // Tippfehler weiter vorne: links rot unterkringelt (auch in Apps, die im Text nichts markieren)
                    if (earlier != null && clipText == null) items += Sugg.EarlierTypo(earlier)
                    items += preds.map { Sugg.Word(it) }
                    when (emo) {
                        is Sugg.EmojiReplace -> if (items.size >= 2) items.add(1, emo) else items += emo
                        is Sugg.Emoji -> if (items.size >= 3) items[2] = emo else items += emo
                        else -> {}
                    }
                    show(items.take(3), centerFirst = earlier != null && items.size < 3)
                }
            }
            return
        }
        val prev = composingPrev
        val prev2 = composingPrev2
        val atStart = composingSentenceStart
        val beforeNow = if (typed.isEmpty()) currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: "" else ""
        worker.execute {
            val items = mutableListOf<Sugg>()
            if (typed.isNotEmpty()) {
                val list = engine.suggest(typed, prev, prev2, atStart)
                val known = engine.isKnown(typed)
                val best = list.firstOrNull()
                val rest = list.filter { it != typed }
                if (best == null || best == typed || (known && best.equals(typed, true))) {
                    // Getipptes Wort passt: in die Mitte, Alternativen links und rechts
                    rest.getOrNull(0)?.let { items += Sugg.Word(it) }
                    items += Sugg.Word(typed, if (known) typed else "„$typed“")
                    rest.getOrNull(1)?.let { items += Sugg.Word(it) }
                } else {
                    // Bester Vorschlag in die Mitte, das Getippte bleibt links wählbar
                    items += Sugg.Word(typed, if (known) typed else "„$typed“")
                    items += Sugg.Word(best)
                    rest.firstOrNull { it != best }?.let { items += Sugg.Word(it) }
                }
                if (prefs.emojiSuggestions) {
                    val ws = listOfNotNull(prev2?.takeIf { it != SuggestionEngine.START }, prev, typed)
                    EmojiData.predict(ws, emojiLearned, 1).firstOrNull()?.let { e ->
                        val emo = Sugg.Emoji(e, typed, emojiStandsFor(typed, e))
                        if (items.size >= 3) items[2] = emo else items += emo
                    }
                }
            } else if (target != null && fix != null) {
                // Blauer Hinweis: links so lassen (blau unterkringelt), daneben die richtige Schreibweise
                items += Sugg.Dismiss(target)
                items += Sugg.Correction(target, fix)
                // Passt ein Emoji („gute nacht“ -> 😴), kommt es rechts dazu
                emojiAfter(beforeNow)?.let { items += it }
            } else if (target != null) {
                val corr = engine.correctionsFor(target, targetPrev)
                items += Sugg.AddWord(target)
                items += corr.take(2).map { Sugg.Correction(target, it) }
            }
            main.post { if (token == suggestToken) show(items, centerFirst = typed.isEmpty() && target != null) }
        }
    }

    /**
     * Emoji-Vorschlag nach einem fertigen Wort: „Gute Nacht “ -> 😴, „:) “ -> 🙂.
     * Läuft komplett auf dem Handy.
     */
    private fun emojiAfter(before: String): Sugg? {
        if (!prefs.emojiSuggestions || before.isBlank()) return null
        EmojiData.emoticonAtEnd(before)?.let { (k, v) -> return Sugg.EmojiReplace(k, v) }
        val t = before.trimEnd()
        // Steht schon ein Emoji da, nichts mehr vorschlagen
        val last = t.codePointBefore(t.length)
        if (!Character.isLetterOrDigit(last) && t.last() !in ".!?") return null
        val words = TextContext.parse(t).recent
        if (words.isEmpty()) return null
        val e = EmojiData.predict(words, emojiLearned, 1).firstOrNull() ?: return null
        // Ersetzen nur direkt nach dem Wort (nicht nach einem Satzzeichen)
        val w = words.last()
        return Sugg.Emoji(e, w, Character.isLetterOrDigit(last) && emojiStandsFor(w, e))
    }

    /** „Geige“ -> 🎻: das Wort (gerade getippt oder direkt davor) durch das Emoji ersetzen. */
    private fun replaceWordWithEmoji(word: String, emoji: String): Boolean {
        val ic = currentInputConnection ?: return false
        if (composing.isNotEmpty()) {
            if (!composing.toString().equals(word, ignoreCase = true)) return false
            if (engine.learning) { EmojiData.learn(this, word, emoji); emojiLearned = emojiLearned + (word.lowercase() to emoji) }
            composing.setLength(0)
            ic.commitText("$emoji ", 1)   // ersetzt den unterstrichenen Wortteil
            return true
        }
        val before = ic.getTextBeforeCursor(word.length + 4, 0)?.toString() ?: return false
        val trimmed = before.trimEnd(' ')
        if (!trimmed.endsWith(word)) return false
        val start = trimmed.length - word.length
        if (start > 0 && Character.isLetterOrDigit(trimmed[start - 1])) return false
        if (engine.learning) { EmojiData.learn(this, word, emoji); emojiLearned = emojiLearned + (word.lowercase() to emoji) }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - start, 0)
        ic.commitText("$emoji ", 1)
        ic.endBatchEdit()
        return true
    }

    /** Steht dieses Emoji für genau dieses Wort (und nicht für eine ganze Wendung)? */
    private fun emojiStandsFor(word: String, e: String): Boolean =
        emojiLearned[word.lowercase()] == e || EmojiData.lookupWord(word) == e

    /** Merkt sich, welches Emoji du nach einem Wort benutzt (nur auf dem Handy, nicht im Inkognito-Modus). */
    private fun learnEmojiAfterWord(e: String) {
        if (!engine.learning) return
        val before = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString() ?: return
        val m = Regex("(\\p{L}{2,})\\s*$").find(before) ?: return
        val w = m.groupValues[1]
        if (EmojiData.lookupWord(w) == e && emojiLearned[w.lowercase()] == null) return
        EmojiData.learn(this, w, e)
        emojiLearned = emojiLearned + (w.lowercase() to e)
    }

    private fun recentClip(): String? {
        val t = clip.lastNewClipTime
        if (t == 0L || System.currentTimeMillis() - t > 90_000) return null
        return clip.items.firstOrNull()?.text
    }

    private fun show(items: List<Sugg>, centerFirst: Boolean = false) {
        shown = arrayOfNulls(3)
        when {
            items.size == 1 -> shown[1] = items[0]
            items.size == 2 -> { shown[0] = items[0]; shown[1] = items[1] }
            else -> for (i in 0 until minOf(3, items.size)) shown[i] = items[i]
        }
        for (i in 0..2) {
            val tv = slots[i] ?: continue
            val s = shown[i]
            tv.text = s?.label ?: ""
            // Unbekanntes Wort: rot unterkringelt (sichtbar auch in Apps, die im Text nichts markieren)
            (tv as? SquiggleTextView)?.let {
                it.squiggle = s is Sugg.AddWord || s is Sugg.Dismiss || s is Sugg.EarlierTypo
                it.squiggleColor = if (s is Sugg.Dismiss) theme.grammar else theme.typo
            }
            // Emojis etwas größer, damit man sie gut erkennt
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, when (s) {
                is Sugg.Emoji, is Sugg.EmojiReplace -> 23f
                is Sugg.EmojiChoice -> if (s.replace) 23f else 17f
                else -> 16f
            })
            tv.setTypeface(null, if (i == 1 && s is Sugg.Word) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(
                when (s) {
                    is Sugg.Correction -> theme.accent
                    is Sugg.AddWord -> theme.barText
                    else -> theme.barText
                },
            )
            if (s is Sugg.Correction && (i == 1 || centerFirst && i == 1)) tv.setTypeface(null, Typeface.BOLD)
        }
    }

    private fun pickSuggestion(i: Int) {
        val s = shown.getOrNull(i) ?: return
        val ic = currentInputConnection ?: return
        lastEditTime = SystemClock.uptimeMillis()
        when (s) {
            is Sugg.Word -> {
                if (composing.isNotEmpty()) {
                    val typed = composing.toString()
                    composing.setLength(0)
                    ic.commitText(s.word, 1)
                    engine.learn(s.word, composingPrev, composingSentenceStart)
                    if (s.word == typed && !engine.isKnown(typed)) engine.addWord(typed)
                } else {
                    // Vorhersage: Wort einfügen
                    val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
                    val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
                    ic.commitText(prefix + s.word, 1)
                    val (prev, start) = contextBefore()
                    engine.learn(s.word, prev, start)
                }
                ic.commitText(" ", 1)
                lastAutoSpace = true
                correctionTarget = null
            }
            is Sugg.Correction -> {
                if (correctionEarlier) {
                    replaceEarlierWord(s.target, s.replacement, TYPO_SCAN_CHARS)
                    engine.learn(s.replacement, null, false)
                    correctionTarget = null
                } else if (correctionFix != null) {
                    replaceEarlierWord(s.target, s.replacement)
                    correctionTarget = null
                } else {
                    applyCorrection(s.target, s.replacement)
                    engine.forget(s.target)
                }
            }
            is Sugg.EarlierTypo -> {
                // Verbesserungen für das frühere Wort zeigen
                correctionTarget = s.word
                correctionPrev = null
                correctionEarlier = true
            }
            is Sugg.Dismiss -> {
                correctionTarget = null
            }
            is Sugg.AddWord -> {
                engine.addWord(s.word)
                correctionTarget = null
                removeTypoMark(s.word)
                Toast.makeText(this, "„${s.word}“ ins Wörterbuch aufgenommen", Toast.LENGTH_SHORT).show()
            }
            is Sugg.Emoji -> {
                if (s.replace && prefs.emojiReplacesWord && s.word != null && replaceWordWithEmoji(s.word, s.emoji)) {
                    addRecentEmoji(s.emoji)
                    lastAutoSpace = true
                    correctionTarget = null
                    if (keyboard.shift == ShiftState.ON) keyboard.shift = ShiftState.OFF
                    updateAutoShift()
                    refreshSuggestions()
                    return
                }
                commitWord()
                learnEmojiAfterWord(s.emoji)
                val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
                ic.commitText(prefix + s.emoji + " ", 1)
                addRecentEmoji(s.emoji)
                lastAutoSpace = true
                correctionTarget = null
            }
            is Sugg.EmojiChoice -> {
                if (!(s.replace && replaceWordWithEmoji(s.word, s.emoji))) {
                    commitWord()
                    learnEmojiAfterWord(s.emoji)
                    val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
                    val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
                    ic.commitText(prefix + s.emoji + " ", 1)
                }
                addRecentEmoji(s.emoji)
                lastAutoSpace = true
                correctionTarget = null
            }
            is Sugg.EmojiMore -> {
                startSearch(MediaPanel.Tab.EMOJI)
                searchQuery.append(s.word.lowercase())
                updateSearchUi()
                return
            }
            is Sugg.EmojiReplace -> {
                val before = ic.getTextBeforeCursor(s.emoticon.length + 4, 0)?.toString() ?: ""
                val trailing = before.length - before.trimEnd(' ').length
                ic.beginBatchEdit()
                ic.deleteSurroundingText(s.emoticon.length + trailing, 0)
                ic.commitText(s.emoji + " ", 1)
                ic.endBatchEdit()
                addRecentEmoji(s.emoji)
                lastAutoSpace = true
            }
            is Sugg.Paste -> {
                commitWord()
                ic.commitText(s.text, 1)
                clip.markUsed()
            }
        }
        if (keyboard.shift == ShiftState.ON) keyboard.shift = ShiftState.OFF
        updateAutoShift()
        refreshSuggestions()
    }

    /** Ersetzt das zuletzt markierte Wort – Satzzeichen/Leerzeichen danach bleiben erhalten. */
    private fun applyCorrection(target: String, replacement: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(target.length + 8, 0)?.toString() ?: return
        val idx = before.lastIndexOf(target)
        if (idx < 0) { correctionTarget = null; return }
        val tail = before.substring(idx + target.length)
        if (tail.any { it.isLetterOrDigit() }) { correctionTarget = null; return }
        // Leerzeichen nur anhängen, wenn danach nicht schon Leerzeichen oder Satzzeichen kommen
        val next = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        val space = if (tail.isEmpty() && (next.isEmpty() || next[0].isLetterOrDigit())) " " else ""
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - idx, 0)
        ic.commitText(replacement + tail + space, 1)
        ic.endBatchEdit()
        engine.learn(replacement, correctionPrev, false)
        correctionTarget = null
        lastAutoSpace = tail.isEmpty() || tail == " "
    }

    /** Rote Markierung entfernen, nachdem ein Wort ins Wörterbuch aufgenommen wurde. */
    private fun removeTypoMark(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(word.length + 8, 0)?.toString() ?: return
        val idx = before.lastIndexOf(word)
        if (idx < 0) return
        val tail = before.substring(idx + word.length)
        if (tail.any { it.isLetterOrDigit() }) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - idx, 0)
        ic.commitText(word + tail, 1)
        ic.endBatchEdit()
    }

    private fun updateAutoShift() {
        if (!::keyboard.isInitialized || mode != Mode.LETTERS || keyboard.shift == ShiftState.LOCK) return
        if (composing.isNotEmpty()) return
        if (!prefs.autoCaps) { keyboard.shift = ShiftState.OFF; return }
        val ei = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = if (field == FieldKind.TEXT && suggestionsOn) ic.getCursorCapsMode(ei.inputType) else 0
        keyboard.shift = if (caps != 0) ShiftState.ON else ShiftState.OFF
    }

    private fun lastGraphemeLength(s: String): Int {
        if (s.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) 1 else (end - start).coerceAtLeast(1)
    }

    // ================================================================== Diktieren

    private fun onMicTap() {
        if (voice.isActive) { voice.stop(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bitte Mikrofon erlauben", Toast.LENGTH_LONG).show()
            openSettings(SettingsActivity.PAGE_VOICE, true)
            return
        }
        commitWord()
        voice.vocabulary = engine.topUserWords(60)
        voice.start()
    }

    override fun onListening(engineName: String) {
        voiceUi = true
        updateBar()
        voiceStatus.text = when {
            engineName != "Whisper" -> "Ich höre zu …"
            voice.engine() == VoiceInput.Engine.OFFLINE -> "Ich höre zu (offline) … ■ oder kurz still sein"
            else -> "Ich höre zu … ■ oder kurz still sein"
        }
        levelView.level = 0f
    }

    override fun onLevel(level: Float) { levelView.level = level }

    // Diktierter Text erscheint schon beim Sprechen im Textfeld (als vorläufiger Text).
    private var voiceLive = false
    private var voicePrefix = ""
    private var voiceSentenceStart = false

    private fun beginVoiceSegment() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        voicePrefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        voiceSentenceStart = contextBefore().start
        voiceLive = true
    }

    private fun shapeVoice(text: String): String =
        VoiceText.fitToContext(VoiceText.spokenPunctuation(text), voiceSentenceStart) { engine.dictionaryForm(it) }

    /** Vorläufigen Diktat-Text stehen lassen (z. B. wenn der Nutzer dazwischen tippt). */
    private fun keepVoicePartial() {
        if (!voiceLive) return
        voiceLive = false
        currentInputConnection?.finishComposingText()
    }

    override fun onPartial(text: String) {
        voiceStatus.text = text
        val ic = currentInputConnection ?: return
        if (text.isBlank()) return
        if (!voiceLive) beginVoiceSegment()
        ic.setComposingText(voicePrefix + shapeVoice(text), 1)
    }

    /** Zuletzt eingefügter Diktat-Text – wird ersetzt, wenn das genaue Ergebnis kommt. */
    private var lastVoiceInsert: String? = null
    private var lastVoicePrefix = ""
    private var lastVoiceStart = false

    private fun commitVoice(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isBlank()) { keepVoicePartial(); return }
        if (!voiceLive) beginVoiceSegment()
        val t = shapeVoice(text)
        val inserted = voicePrefix + t + if (t.endsWith("\n")) "" else " "
        ic.commitText(inserted, 1)
        lastVoiceInsert = inserted
        lastVoicePrefix = voicePrefix
        lastVoiceStart = voiceSentenceStart
        voiceLive = false
        lastAutoSpace = true
        // Diktierte Wörter für Vorhersagen lernen
        var prev: String? = null
        for (w in Regex("[\\p{L}'-]+").findAll(t).map { it.value }) {
            engine.learn(w, prev, prev == null); prev = w
        }
    }

    override fun onSegment(text: String) {
        commitVoice(text)
        voiceStatus.text = "Ich höre weiter zu … tippe ■ zum Beenden"
    }

    override fun onProcessing() {
        voiceStatus.text = "Wird erkannt …"
        levelView.level = 0f
    }

    override fun onResult(text: String) {
        commitVoice(text)
        endVoiceUi()
        updateAutoShift()
        refreshSuggestions()
    }

    override fun onRefining() {
        // Der schnelle Text steht schon da; Hinweis, dass er noch verbessert wird
        suggestToken++   // noch ausstehende Vorschläge nicht mehr anzeigen
        show(emptyList())
        slots[1]?.apply {
            text = "✨ wird genauer …"
            setTextColor(theme.hint)
            setTypeface(null, Typeface.NORMAL)
        }
        refining = true
    }

    private var refining = false

    override fun onRefined(text: String?) {
        if (!refining) return
        refining = false
        val old = lastVoiceInsert
        val ic = currentInputConnection
        if (text != null && old != null && ic != null) {
            val before = ic.getTextBeforeCursor(old.length, 0)?.toString()
            // Nur ersetzen, wenn seitdem nichts getippt wurde
            if (before == old) {
                voiceSentenceStart = lastVoiceStart
                val t = shapeVoice(text)
                val better = lastVoicePrefix + t + if (t.endsWith("\n")) "" else " "
                if (better != old) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(old.length, 0)
                    ic.commitText(better, 1)
                    ic.endBatchEdit()
                    lastVoiceInsert = better
                }
            }
        }
        updateAutoShift()
        refreshSuggestions()
    }

    override fun onError(message: String) {
        keepVoicePartial()
        endVoiceUi()
        toolbarForced = false
        updateBar()
        show(emptyList())
        slots[1]?.apply {
            text = message
            setTextColor(theme.hint)
            setTypeface(null, Typeface.NORMAL)
        }
    }

    override fun onIdle() {
        keepVoicePartial()
        endVoiceUi()
        updateAutoShift()
        refreshSuggestions()
    }

    private fun endVoiceUi() {
        voiceUi = false
        updateBar()
    }

    private fun openSettings(page: String?, askMic: Boolean) {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (page != null) putExtra(SettingsActivity.EXTRA_PAGE, page)
            if (askMic) putExtra(SettingsActivity.EXTRA_ASK_MIC, true)
        })
    }

    // ================================================================== UI-Aufbau

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(theme.barText)
        background = rippleBg()
        setOnClickListener { onClick() }
    }

    private fun iconButton(res: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(theme.text)
        scaleType = ImageView.ScaleType.CENTER
        background = rippleBg()
        setOnClickListener { onClick() }
    }

    /** Mikrofon im Kreis (wie bei Gboard). */
    private fun micView(): View = FrameLayout(this).apply {
        val circle = ImageView(this@KeyboardService).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(theme.text)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.key) }
        }
        addView(circle, FrameLayout.LayoutParams(dp(36f), dp(36f), Gravity.CENTER))
        setOnClickListener { onMicTap() }
    }

    /** Welche Zeile der Leiste ist sichtbar: Werkzeuge, Vorschläge, Diktat, Suche oder Größe. */
    private fun updateBar() {
        if (!::suggRow.isInitialized) return
        val tools = toolbarForced ?: (composing.isEmpty() && correctionTarget == null && recentClip() == null &&
            (fieldEmpty || !suggestionsOn || !prefs.showSuggestions))
        val row = when {
            resizing -> resizeRow
            searchTarget != null -> searchRow
            voiceUi -> voiceRow
            tools -> toolRow
            else -> suggRow
        }
        for (r in listOf(toolRow, suggRow, voiceRow, searchRow, resizeRow)) {
            r.visibility = if (r === row) View.VISIBLE else View.GONE
        }
    }

    private fun rippleBg() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(theme.keyPressed), null,
        GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(8f).toFloat() },
    )

    private fun buildBar(): View {
        val frame = FrameLayout(this)
        suggRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        suggRow.addView(iconButton(R.drawable.ic_logo) { toolbarForced = true; updateBar() },
            LinearLayout.LayoutParams(dp(46f), ViewGroup.LayoutParams.MATCH_PARENT))
        for (i in 0..2) {
            val tv = SquiggleTextView(this, theme.typo).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(theme.barText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4f), 0, dp(4f), 0)
                background = rippleBg()
                setOnClickListener { pickSuggestion(i) }
                setOnLongClickListener {
                    val s = shown[i]
                    if (s is Sugg.EarlierTypo) {
                        // Langer Druck: so lassen und in diesem Feld nicht mehr anzeigen
                        ignoredTypos += s.word
                        haptics.play(Haptics.Kind.CONFIRM)
                        refreshSuggestions()
                        return@setOnLongClickListener true
                    }
                    if (s is Sugg.Emoji && s.replace && s.word != null) {
                        // Langer Druck: selbst wählen – „Geige 🎻“, „🎻“ oder mehr Emojis
                        haptics.play(Haptics.Kind.POPUP)
                        val w = s.word
                        val shownWord = if (composing.isNotEmpty()) composing.toString() else w
                        show(listOf(
                            Sugg.EmojiChoice(s.emoji, w, false, "$shownWord ${s.emoji}"),
                            Sugg.EmojiChoice(s.emoji, w, true, s.emoji),
                            Sugg.EmojiMore(w),
                        ))
                        return@setOnLongClickListener true
                    }
                    if (s is Sugg.Emoji || s is Sugg.EmojiReplace) {
                        // Mehr passende Emojis: Suche mit dem Wort öffnen
                        val w = (s as? Sugg.Emoji)?.word ?: ""
                        haptics.play(Haptics.Kind.POPUP)
                        startSearch(MediaPanel.Tab.EMOJI)
                        searchQuery.append(w.lowercase())
                        updateSearchUi()
                        return@setOnLongClickListener true
                    }
                    if (s is Sugg.Word && composing.isNotEmpty() && s.word == composing.toString()) {
                        engine.addWord(s.word)
                        Toast.makeText(this@KeyboardService, "„${s.word}“ ins Wörterbuch aufgenommen", Toast.LENGTH_SHORT).show()
                        refreshSuggestions(); true
                    } else false
                }
            }
            slots[i] = tv
            suggRow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        suggRow.addView(micView(), LinearLayout.LayoutParams(dp(50f), ViewGroup.LayoutParams.MATCH_PARENT))

        // Werkzeugleiste (bei leerem Feld oder über das K-Zeichen oben links)
        toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        // Lang drücken zeigt, was ein Symbol macht
        fun tool(v: View, tip: String) {
            v.tooltipText = tip
            v.contentDescription = tip
            toolRow.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        tool(iconButton(R.drawable.ic_logo) { toolbarForced = false; updateBar(); refreshSuggestions() }, "Wortvorschläge")
        tool(iconButton(R.drawable.ic_clipboard) { togglePanel(Panel.CLIPBOARD) }, "Zwischenablage")
        tool(barButton("GIF") { openMedia(MediaPanel.Tab.GIF) }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(null, Typeface.BOLD)
        }, "GIFs")
        tool(iconButton(R.drawable.ic_sticker) { openMedia(MediaPanel.Tab.STICKER) }, "Sticker")
        tool(iconButton(R.drawable.ic_edit) { togglePanel(Panel.EDIT) }, "Text bearbeiten")
        tool(iconButton(R.drawable.ic_onehand) { toggleOneHanded() }, "Einhandmodus")
        tool(iconButton(R.drawable.ic_resize) { startResize() }, "Größe ändern")
        tool(iconButton(R.drawable.ic_settings) { openSettings(null, false) }, "Einstellungen")
        tool(micView().apply { tooltipText = "Diktieren"; contentDescription = "Diktieren" }, "Diktieren")

        // Zeile beim Ändern der Größe
        resizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        resizeRow.addView(barButton("Zurücksetzen") {
            setKeyboardSize(100); setBottomPad(0)
        }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }, LinearLayout.LayoutParams(dp(120f), ViewGroup.LayoutParams.MATCH_PARENT))
        resizeLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        resizeRow.addView(resizeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        resizeRow.addView(barButton("Fertig") { endResize() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(theme.accent); setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(90f), ViewGroup.LayoutParams.MATCH_PARENT))

        voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        voiceRow.addView(barButton("✕") {
            if (voiceLive) { voiceLive = false; currentInputConnection?.commitText("", 1) }
            voice.cancel()
        }, LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.MATCH_PARENT))
        levelView = LevelView(this, theme.recording)
        voiceRow.addView(levelView, LinearLayout.LayoutParams(dp(36f), dp(28f)))
        voiceStatus = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.barText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        voiceRow.addView(voiceStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        voiceRow.addView(barButton("■") { voice.stop() }.apply { setTextColor(theme.recording) },
            LinearLayout.LayoutParams(dp(52f), ViewGroup.LayoutParams.MATCH_PARENT))

        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        searchRow.addView(barButton("✕") { endSearch(); showPanel(Panel.MEDIA) },
            LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.MATCH_PARENT))
        searchText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply { setColor(theme.key); cornerRadius = dp(18f).toFloat() }
        }
        searchRow.addView(searchText, LinearLayout.LayoutParams(0, dp(36f), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        // Emoji-Suche: Treffer erscheinen schon beim Tippen direkt in der Leiste
        searchHits = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchHitsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(searchHits, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        searchRow.addView(searchHitsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f))
        searchButton = barButton("Suchen") { runSearch() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(theme.accent); setTypeface(null, Typeface.BOLD)
        }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(76f), ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(searchRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(suggRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(toolRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(resizeRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(voiceRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun panelHeight(): Int {
        if (keyboard.height <= 0) {
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return maxOf(keyboard.measuredHeight, dp(170f))
        }
        return maxOf(keyboard.height, dp(170f))
    }

    private fun togglePanel(p: Panel) = showPanel(if (panel == p) Panel.KEYBOARD else p)

    private fun openMedia(tab: MediaPanel.Tab) {
        showPanel(Panel.MEDIA)
        mediaPanel?.select(tab)
    }

    private fun showPanel(p: Panel) {
        if (!::content.isInitialized) return
        if (p != Panel.KEYBOARD) { commitWord(); endResize() }
        // Emojis, Zwischenablage und Textbearbeitung nutzen auch den Platz der Vorschlagsleiste –
        // die Gesamthöhe bleibt gleich, es springt nichts.
        val h = panelHeight() + dp(BAR_DP)
        panel = p
        bar.visibility = if (p == Panel.KEYBOARD) View.VISIBLE else View.GONE
        keyboard.visibility = if (p == Panel.KEYBOARD) View.VISIBLE else View.GONE
        mediaPanel?.visibility = View.GONE
        clipPanel?.visibility = View.GONE
        editPanel?.visibility = View.GONE
        fun attach(v: View) {
            if (v.parent == null) content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            v.layoutParams.height = h
            v.visibility = View.VISIBLE
        }
        when (p) {
            Panel.MEDIA -> {
                val v = mediaPanel ?: MediaPanel(this, theme, giphy, stickers, this).also { mediaPanel = it }
                attach(v)
                v.refreshRecents()
                v.prefetch()
            }
            Panel.CLIPBOARD -> {
                clip.capture()
                attach(clipPanel ?: buildClipPanel().also { clipPanel = it })
                clipAdapter?.notifyDataSetChanged()
            }
            Panel.EDIT -> {
                selecting = false
                attach(editPanel ?: buildEditPanel().also { editPanel = it })
                styleSelectCell()
            }
            Panel.KEYBOARD -> {}
        }
        content.requestLayout()
    }

    // ---------------------------------------------------------------- Treffhilfe

    /**
     * Unsichtbare Treffhilfe: berechnet, welche Buchstaben als nächstes wahrscheinlich sind.
     * Bis das Ergebnis da ist, gilt die normale Trefferfläche.
     */
    private fun updateTouchAssist() {
        keyboard.touchWeights = emptyMap()
        val strength = prefs.touchAssist / 100f
        keyboard.touchAssist = strength
        if (strength <= 0f || !suggestionsOn || mode != Mode.LETTERS || searchTarget != null) return
        var typed = composing.toString()
        var prev = composingPrev
        var prev2 = composingPrev2
        var start = composingSentenceStart
        if (typed.isEmpty()) {
            val ctx = contextBefore()
            prev = ctx.prev; prev2 = ctx.prev2; start = ctx.start
        } else if (lastWasGesture) {
            // Nach einem gewischten Wort beginnt mit dem nächsten Buchstaben ein neues Wort
            prev2 = prev; prev = typed; typed = ""; start = false
        }
        // Leertaste zählt mit: ist das Wort fertig, wird sie leichter getroffen
        val keys = keyboard.letterKeys() + ' '
        val token = ++assistToken
        worker.execute {
            val weights = TouchModel.weights(engine.nextLetters(typed, prev, prev2, start), keys)
            main.post { if (token == assistToken) keyboard.touchWeights = weights }
        }
    }

    // ---------------------------------------------------------------- Größe ändern

    private fun startResize() {
        if (resizing || !::content.isInitialized) return
        showPanel(Panel.KEYBOARD)
        resizing = true
        val overlay = ResizeOverlay(this)
        resizeOverlay = overlay
        content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateResizeLabel()
        updateBar()
    }

    private fun endResize() {
        if (!resizing) return
        resizing = false
        resizeOverlay?.let { content.removeView(it) }
        resizeOverlay = null
        updateBar()
    }

    private fun setKeyboardSize(percent: Int) {
        prefs.keyHeightPercent = percent
        keyboard.keyHeightPercent = prefs.keyHeightPercent
        updateResizeLabel()
    }

    private fun setBottomPad(dpValue: Int) {
        prefs.bottomPadDp = dpValue
        applyBottomPadding()
        updateResizeLabel()
    }

    private fun updateResizeLabel() {
        if (!::resizeLabel.isInitialized) return
        val pad = prefs.bottomPadDp
        resizeLabel.text = "Größe ${prefs.keyHeightPercent} %" + if (pad > 0) " · Abstand $pad" else ""
    }

    /** Liegt beim Ändern der Größe über der Tastatur: oben ziehen = Größe, unten ziehen = Abstand zum Rand. */
    private inner class ResizeOverlay(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2 * d; color = theme.accent
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.text; textAlign = Paint.Align.CENTER; textSize = 15 * d
        }
        private var dragMode = 0
        private var startRaw = 0f
        private var startPercent = 100
        private var startPad = 0
        private var heightAt100 = 1f

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xB3111214.toInt())
            val m = 3 * d
            canvas.drawRoundRect(m, m, width - m, height - m, 12 * d, 12 * d, frame)
            // Griffe oben und unten
            val gw = 28 * d
            val gh = 3 * d
            canvas.drawRoundRect(width / 2f - gw, m - gh, width / 2f + gw, m + gh, gh, gh, fill)
            canvas.drawRoundRect(width / 2f - gw, height - m - gh, width / 2f + gw, height - m + gh, gh, gh, fill)
            canvas.drawText("↕  Ziehen: Größe", width / 2f, height * 0.42f, text)
            canvas.drawText("Unterer Griff: Abstand zum Rand", width / 2f, height - 24 * d, text)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Nur der untere Griff verschiebt die Tastatur, alles andere ändert die Größe –
                    // die Tastatur bleibt unten am Rand
                    dragMode = if (e.y > height - 36 * d) 2 else 1
                    startRaw = e.rawY
                    startPercent = prefs.keyHeightPercent
                    startPad = prefs.bottomPadDp
                    heightAt100 = (keyboard.height * 100f / startPercent).coerceAtLeast(1f)
                }
                MotionEvent.ACTION_MOVE -> {
                    val up = startRaw - e.rawY
                    if (dragMode == 1) setKeyboardSize(Math.round(startPercent + up / heightAt100 * 100f))
                    else if (dragMode == 2) setBottomPad(Math.round(startPad + up / d))
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- Einhandmodus

    private fun buildSide(left: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        // Pfeil: Tastatur auf diese Seite holen · Doppelpfeil: wieder volle Breite
        addView(iconButton(if (left) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right) {
            prefs.oneHanded = if (left) "left" else "right"; applyOneHanded()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)))
        addView(iconButton(R.drawable.ic_resize) {
            prefs.oneHanded = "off"; applyOneHanded()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)))
    }

    private fun applyOneHanded() {
        if (!::sideLeft.isInitialized) return
        val m = prefs.oneHanded
        // Tastatur rechts -> freier Streifen links (und umgekehrt)
        sideLeft.visibility = if (m == "right") View.VISIBLE else View.GONE
        sideRight.visibility = if (m == "left") View.VISIBLE else View.GONE
    }

    private fun toggleOneHanded() {
        prefs.oneHanded = if (prefs.oneHanded == "off") "right" else "off"
        applyOneHanded()
    }

    // ---------------------------------------------------------------- Textbearbeitung

    private fun sendKey(code: Int, meta: Int = 0, action: Int? = null) {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        fun ev(a: Int) = KeyEvent(
            t, t, a, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD,
        )
        if (action == null) { ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN)); ic.sendKeyEvent(ev(KeyEvent.ACTION_UP)) }
        else ic.sendKeyEvent(ev(action))
    }

    /** Cursor bewegen – im Auswahlmodus mit gehaltener Umschalttaste (markiert Text). */
    private fun moveCursor(code: Int) {
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            currentInputConnection?.finishComposingText()
        }
        correctionTarget = null
        if (selecting) {
            val m = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, m, KeyEvent.ACTION_DOWN)
            sendKey(code, m)
            sendKey(KeyEvent.KEYCODE_SHIFT_LEFT, 0, KeyEvent.ACTION_UP)
        } else sendKey(code)
    }

    private fun menuAction(id: Int, done: String? = null) {
        commitWord()
        currentInputConnection?.performContextMenuAction(id)
        if (done != null) toast(done)
        if (id != android.R.id.selectAll) { selecting = false; styleSelectCell() }
    }

    private fun styleSelectCell() {
        val c = selectCell ?: return
        c.background = GradientDrawable().apply {
            setColor(if (selecting) theme.accent else theme.specialKey); cornerRadius = dp(8f).toFloat()
        }
        c.setTextColor(if (selecting) theme.accentText else theme.text)
    }

    /** Knopf, der beim Gedrückthalten wiederholt (Pfeile, Löschen). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun repeating(v: View, action: () -> Unit) {
        val r = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 60) }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; haptic(view); action(); main.postDelayed(r, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; main.removeCallbacks(r) }
            }
            true
        }
    }

    private fun buildEditPanel(): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        fun bg(color: Int) = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(theme.keyPressed),
            GradientDrawable().apply { setColor(color); cornerRadius = dp(8f).toFloat() }, null,
        )
        fun label(text: String, size: Float = 14f, color: Int = theme.key) = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            maxLines = 1
            background = bg(color)
        }
        fun icon(res: Int, color: Int = theme.key) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(theme.text)
            scaleType = ImageView.ScaleType.CENTER
            background = bg(color)
        }
        fun row(vararg cells: Pair<View, Float>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((v, w) in cells) {
                r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w).apply {
                    setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
                })
            }
            grid.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        fun tap(v: View, action: () -> Unit) = v.apply { setOnClickListener { haptic(it); action() } }
        fun hold(v: View, action: () -> Unit) = v.apply { repeating(this, action) }
        val f = theme.specialKey

        val select = label("Auswählen", 13f, f)
        selectCell = select
        tap(select) { selecting = !selecting; styleSelectCell() }

        row(
            tap(label("Anfang", 13f, f)) { moveCursor(KeyEvent.KEYCODE_MOVE_HOME) } to 1f,
            hold(label("↑", 20f)) { moveCursor(KeyEvent.KEYCODE_DPAD_UP) } to 1f,
            tap(label("Ende", 13f, f)) { moveCursor(KeyEvent.KEYCODE_MOVE_END) } to 1f,
            tap(label("Alles auswählen", 13f, f)) { menuAction(android.R.id.selectAll) } to 1.5f,
        )
        row(
            hold(label("←", 20f)) { moveCursor(KeyEvent.KEYCODE_DPAD_LEFT) } to 1f,
            select to 1f,
            hold(label("→", 20f)) { moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT) } to 1f,
            tap(label("Kopieren", 13f, f)) { menuAction(android.R.id.copy, "Kopiert") } to 1.5f,
        )
        row(
            tap(icon(R.drawable.ic_undo, f)) { menuAction(android.R.id.undo) } to 1f,
            hold(label("↓", 20f)) { moveCursor(KeyEvent.KEYCODE_DPAD_DOWN) } to 1f,
            tap(icon(R.drawable.ic_redo, f)) { menuAction(android.R.id.redo) } to 1f,
            tap(label("Einfügen", 13f, f)) { menuAction(android.R.id.paste) } to 1.5f,
        )
        row(
            tap(label("ABC", 14f, f)) { showPanel(Panel.KEYBOARD) } to 1f,
            tap(label("Ausschneiden", 13f, f)) { menuAction(android.R.id.cut, "Ausgeschnitten") } to 2f,
            hold(icon(R.drawable.ic_backspace, f)) { handleDelete() } to 1.5f,
        )
        return grid
    }

    // ---------------------------------------------------------------- Emoji, GIFs, Sticker

    private fun addRecentEmoji(e: String) {
        val sp = getSharedPreferences("emoji", MODE_PRIVATE)
        val old = sp.getString("recent", "")!!.split('\u0001').filter { it.isNotEmpty() && it != e }
        sp.edit().putString("recent", (listOf(e) + old).take(32).joinToString("\u0001")).apply()
    }

    override fun commitEmoji(e: String) {
        commitWord()
        learnEmojiAfterWord(e)
        currentInputConnection?.commitText(e, 1)
        addRecentEmoji(e)
    }

    override fun deleteChar() = handleDelete()

    override fun backToKeyboard() = showPanel(Panel.KEYBOARD)

    override fun haptic(v: View) {
        haptics.tap()
    }

    override fun openSettings(page: String) = openSettings(page, false)

    /** Unterstützt das Eingabefeld Bilder dieses Typs? */
    private fun supportsMime(mime: String): Boolean {
        val types = currentInputEditorInfo?.contentMimeTypes ?: return false
        return types.any { ClipDescription.compareMimeTypes(mime, it) }
    }

    /** Bild als echtes Bild (nicht als Link) in die App einfügen. */
    private fun commitImage(uri: android.net.Uri, mime: String, desc: String, link: String?): Boolean {
        val ic = currentInputConnection ?: return false
        if (!supportsMime(mime)) return false
        commitWord()
        val info = InputContentInfo(uri, ClipDescription(desc, arrayOf(mime)), link?.let { android.net.Uri.parse(it) })
        return try {
            ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        } catch (_: Exception) { false }
    }

    private fun shareDir() = File(cacheDir, "share").apply { mkdirs() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun commitGif(item: GiphyClient.Item, sticker: Boolean) {
        if (!supportsMime("image/gif")) {
            // App kann keine GIFs empfangen: Link einfügen
            commitWord()
            currentInputConnection?.commitText(item.pageUrl + " ", 1)
            toast("Diese App kann keine GIFs empfangen – Link eingefügt")
            return
        }
        toast(if (sticker) "Sticker wird gesendet …" else "GIF wird eingefügt …")
        ImageLoader.run {
            try {
                val bytes = ImageLoader.cached(item.fullUrl) ?: Net.get(item.fullUrl)
                // Alte Dateien aufräumen
                shareDir().listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3600_000 }?.forEach { it.delete() }
                val f = File(shareDir(), "giphy_${item.id.filter { it.isLetterOrDigit() }}.gif")
                f.writeBytes(bytes)
                main.post {
                    if (!commitImage(MediaProvider.uriFor("share", f.name), "image/gif", "GIF", item.pageUrl)) {
                        currentInputConnection?.commitText(item.pageUrl + " ", 1)
                    }
                }
            } catch (_: Exception) {
                main.post { toast("GIF konnte nicht geladen werden") }
            }
        }
    }

    /** Animiertes Emoji (Noto Animated Emoji von Google, CC BY 4.0) als GIF senden – ohne Schlüssel. */
    override fun commitAnimatedEmoji(e: String) {
        EmojiData.bundledAsset(e)?.let { asset ->
            // In der App eingebaut: kein Internet nötig
            if (!supportsMime("image/gif")) {
                commitEmoji(e)
                toast("Diese App nimmt keine animierten Bilder an – Emoji eingefügt")
                return
            }
            val f = File(shareDir(), "klar_" + asset.substringAfter('/'))
            try {
                if (!f.exists() || f.length() == 0L) assets.open(asset).use { input -> f.outputStream().use { input.copyTo(it) } }
            } catch (_: Exception) { commitEmoji(e); return }
            if (commitImage(MediaProvider.uriFor("share", f.name), "image/gif", "Animiertes Emoji", null)) addRecentEmoji(e)
            else commitEmoji(e)
            return
        }
        if (!prefs.onlineAnimated) {
            // Datenschutz: erst fragen, bevor etwas aus dem Netz geladen wird
            mediaPanel?.askConsent(
                "Animierte Emojis werden von einem Google-Server geladen. Dabei wird übertragen, welches Emoji du auswählst. Tippe hier, um das zu erlauben.",
            ) { prefs.onlineAnimated = true; commitAnimatedEmoji(e) }
            return
        }
        if (!supportsMime("image/gif")) {
            commitEmoji(e)
            toast("Diese App nimmt keine animierten Bilder an – Emoji eingefügt")
            return
        }
        toast("Animation wird geladen …")
        ImageLoader.run {
            val hex = e.codePoints().toArray().map { Integer.toHexString(it) }
            // Erst die volle Zeichenfolge, dann ohne das unsichtbare Darstellungszeichen
            val names = listOf(hex.joinToString("_"), hex.filter { it != "fe0f" }.joinToString("_")).distinct()
            var file: File? = null
            for (n in names) {
                val f = File(shareDir(), "noto_$n.gif")
                if (f.exists() && f.length() > 0) { file = f; break }
                try {
                    f.writeBytes(Net.get("https://fonts.gstatic.com/s/e/notoemoji/latest/$n/512.gif"))
                    file = f
                    break
                } catch (_: Exception) { f.delete() }
            }
            main.post {
                val f = file
                if (f == null) {
                    commitEmoji(e)
                    toast("Animation nicht verfügbar – Emoji eingefügt")
                } else if (commitImage(MediaProvider.uriFor("share", f.name), "image/gif", "Animiertes Emoji", null)) {
                    addRecentEmoji(e)
                } else commitEmoji(e)
            }
        }
    }

    override fun commitStickerFile(f: File) {
        val mime = MediaProvider.mimeOf(f.name)
        if (!commitImage(MediaProvider.uriFor("stickers", f.name), mime, "Sticker", null)) {
            toast("Diese App kann keine Sticker/Bilder empfangen")
        }
    }

    override fun commitEmojiSticker(e: String) {
        val (fmt, ext, mime) = when {
            supportsMime("image/png") -> Triple(Bitmap.CompressFormat.PNG, "png", "image/png")
            supportsMime("image/webp") -> Triple(Bitmap.CompressFormat.WEBP_LOSSLESS, "webp", "image/webp")
            else -> {
                commitEmoji(e)
                toast("Diese App kann keine Sticker empfangen – Emoji eingefügt")
                return
            }
        }
        val name = "emoji_" + e.codePoints().toArray().joinToString("_") { Integer.toHexString(it) } + "." + ext
        val f = File(shareDir(), name)
        if (!f.exists()) StickerStore.renderEmoji(e, fmt, f)
        if (!commitImage(MediaProvider.uriFor("share", f.name), mime, "Sticker", null)) commitEmoji(e)
        else addRecentEmoji(e)
    }

    // ---------------------------------------------------------------- Suche (GIF/Sticker)

    override fun startSearch(tab: MediaPanel.Tab) {
        searchTarget = tab
        searchQuery.setLength(0)
        showPanel(Panel.KEYBOARD)
        mode = Mode.LETTERS
        applyLayout()
        keyboard.shift = ShiftState.OFF
        updateBar()
        updateSearchUi()
    }

    private fun updateSearchUi() {
        val target = when (searchTarget) {
            MediaPanel.Tab.STICKER -> "Sticker"
            MediaPanel.Tab.EMOJI -> "Emojis"
            else -> "GIFs"
        }
        searchText.text = if (searchQuery.isEmpty()) "🔍 $target suchen …" else "🔍 $searchQuery▏"
        searchText.setTextColor(if (searchQuery.isEmpty()) theme.hint else theme.text)
        updateSearchHits()
    }

    /** Emoji-Treffer zur aktuellen Eingabe; Antippen fügt das Emoji sofort ein. */
    private fun updateSearchHits() {
        val emoji = searchTarget == MediaPanel.Tab.EMOJI
        val q = searchQuery.toString().trim()
        val found = if (emoji && q.isNotEmpty()) {
            EmojiData.ensureLoaded(this)
            EmojiData.search(q, 30)
        } else emptyList()
        searchHits.removeAllViews()
        for (e in found) {
            searchHits.addView(TextView(this).apply {
                text = e.text
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(theme.text)
                background = rippleBg()
                setOnClickListener { haptics.tap(); commitEmoji(e.text) }
            }, LinearLayout.LayoutParams(dp(42f), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (emoji && q.isNotEmpty() && found.isEmpty()) {
            searchHits.addView(TextView(this).apply {
                text = "nichts gefunden"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(theme.hint)
                setPadding(dp(8f), 0, dp(8f), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        searchHitsScroll.scrollTo(0, 0)
        searchHitsScroll.visibility = if (emoji && q.isNotEmpty()) View.VISIBLE else View.GONE
        searchButton.visibility = if (emoji && q.isNotEmpty()) View.GONE else View.VISIBLE
    }

    private fun runSearch() {
        val t = searchTarget ?: return
        val q = searchQuery.toString().trim()
        endSearch()
        showPanel(Panel.MEDIA)
        mediaPanel?.search(t, q)
    }

    private fun endSearch() {
        if (searchTarget == null) return
        searchTarget = null
        searchQuery.setLength(0)
        updateBar()
        applyLayout()
    }

    // ---------------------------------------------------------------- Wischen zum Schreiben

    override fun onGesture(path: List<GestureDecoder.Pt>) {
        val centers = keyboard.letterCenters()
        val keyW = keyboard.letterKeyWidth()
        val ctx = contextBefore()
        engine.setRecentWords(ctx.recent)
        val prev = ctx.prev
        val prev2 = ctx.prev2
        worker.execute {
            val words = decoder.decode(path, centers, keyW, prev, prev2)
            val cased = words.map { engine.displayForm(it, prev, prev2) }
            main.post { applyGesture(cased) }
        }
    }

    private fun applyGesture(words: List<String>) {
        if (words.isEmpty()) {
            haptics.play(Haptics.Kind.REJECT)
            toast("Wort nicht erkannt")
            return
        }
        if (searchTarget != null) {
            if (searchQuery.isNotEmpty() && !searchQuery.endsWith(" ")) searchQuery.append(' ')
            searchQuery.append(words[0])
            updateSearchUi()
            return
        }
        val ic = currentInputConnection ?: return
        lastEditTime = SystemClock.uptimeMillis()
        lastAutocorrect = null
        correctionTarget = null
        commitWord()
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in "([{\"'„‚«") {
            ic.commitText(" ", 1)
        }
        val ctx = contextBefore()
        composingPrev = ctx.prev
        composingPrev2 = ctx.prev2
        composingSentenceStart = ctx.start
        engine.setRecentWords(ctx.recent)
        fun cased(w: String) = when {
            keyboard.shift == ShiftState.LOCK -> w.uppercase()
            keyboard.shift == ShiftState.ON || ctx.start -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }
        val list = words.map { cased(it) }.distinct()
        composing.setLength(0)
        composing.append(list[0])
        ic.setComposingText(composing, 1)
        gestureAlts = list
        lastWasGesture = true
        lastAutoSpace = false
        if (keyboard.shift == ShiftState.ON) keyboard.shift = ShiftState.OFF
        refreshSuggestions()
    }

    /** Autokorrektur rückgängig machen (⌫ direkt danach). */
    private fun undoAutocorrect(undo: Pair<String, String>): Boolean {
        val ic = currentInputConnection ?: return false
        val (orig, fix) = undo
        val before = ic.getTextBeforeCursor(fix.length + 2, 0)?.toString() ?: return false
        val idx = before.lastIndexOf(fix)
        if (idx < 0) return false
        val tail = before.substring(idx + fix.length)
        if (tail.length > 1 || tail.any { it.isLetterOrDigit() }) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - idx, 0)
        ic.commitText(orig, 1)
        ic.endBatchEdit()
        engine.forget(fix)
        engine.learn(orig, null, false)
        toast("Autokorrektur rückgängig gemacht")
        return true
    }

    // ---------------------------------------------------------------- Zwischenablage

    private inner class ClipAdapter : BaseAdapter() {
        override fun getCount() = clip.items.size
        override fun getItem(position: Int) = clip.items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = clip.items[position]
            val row = LinearLayout(this@KeyboardService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
            }
            val text = TextView(this@KeyboardService).apply {
                this.text = item.text
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply { setColor(theme.key); cornerRadius = dp(10f).toFloat() }
                setOnClickListener {
                    commitWord()
                    currentInputConnection?.commitText(item.text, 1)
                    clip.markUsed()
                    showPanel(Panel.KEYBOARD)
                    refreshSuggestions()
                }
            }
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(barButton(if (item.pinned) "📌" else "📍") {
                clip.togglePin(item); notifyDataSetChanged()
            }.apply { alpha = if (item.pinned) 1f else 0.5f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f) },
                LinearLayout.LayoutParams(dp(40f), dp(44f)))
            row.addView(barButton("✕") { clip.remove(item); notifyDataSetChanged() }.apply {
                setTextColor(theme.hint); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(dp(36f), dp(44f)))
            return row
        }
    }

    private fun buildClipPanel(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(barButton("ABC") { showPanel(Panel.KEYBOARD) }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(56f), dp(40f)))
        header.addView(TextView(this).apply {
            text = "Zwischenablage"
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(barButton("Leeren") {
            clip.clearUnpinned(); clipAdapter?.notifyDataSetChanged()
        }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }, LinearLayout.LayoutParams(dp(72f), dp(40f)))
        header.addView(barButton("⚙") { openSettings(null, false) }, LinearLayout.LayoutParams(dp(44f), dp(40f)))
        root.addView(header)

        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        val adapter = ClipAdapter()
        clipAdapter = adapter
        list.adapter = adapter
        list.emptyView = TextView(this).apply {
            text = "Noch nichts kopiert.\nKopierter Text erscheint hier – 📌 heftet Einträge dauerhaft an."
            gravity = Gravity.CENTER
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val frame = FrameLayout(this)
        frame.addView(list)
        frame.addView(list.emptyView)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** Pegelanzeige beim Diktieren. */
    private class LevelView(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        var level = 0f
            set(v) { field = field * 0.5f + v * 0.5f; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val bars = 4
            val bw = width / (bars * 2f)
            for (i in 0 until bars) {
                val f = (level * (1.2f - kotlin.math.abs(i - 1.5f) * 0.25f)).coerceIn(0.12f, 1f)
                val h = height * f
                val x = bw / 2 + i * bw * 2
                canvas.drawRoundRect(x, (height - h) / 2, x + bw, (height + h) / 2, bw / 2, bw / 2, paint)
            }
        }
    }
}
