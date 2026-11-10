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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.SuggestionSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.BreakIterator
import java.util.concurrent.Executors

class KeyboardService : InputMethodService(), KeyboardView.Listener, VoiceInput.Callback, MediaPanel.Host {

    private enum class Panel { KEYBOARD, MEDIA, CLIPBOARD }
    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD }

    /** Ein Eintrag in der Vorschlagsleiste. */
    private sealed class Sugg(val label: String) {
        class Word(val word: String, label: String = word) : Sugg(label)
        class Correction(val target: String, val replacement: String) : Sugg(replacement)
        class AddWord(val word: String) : Sugg("+ „$word“")
        class Emoji(val emoji: String) : Sugg(emoji)
        class Paste(val text: String) : Sugg(ClipboardStore.smartLabel(text))
    }

    private lateinit var prefs: Prefs
    private lateinit var theme: KeyboardTheme
    private lateinit var engine: SuggestionEngine
    private lateinit var clip: ClipboardStore
    private lateinit var voice: VoiceInput
    private lateinit var decoder: GestureDecoder
    private lateinit var giphy: GiphyClient
    private lateinit var stickers: StickerStore
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var suggestToken = 0

    // Views
    private lateinit var keyboard: KeyboardView
    private lateinit var content: FrameLayout
    private lateinit var suggRow: LinearLayout
    private lateinit var voiceRow: LinearLayout
    private lateinit var voiceStatus: TextView
    private lateinit var levelView: LevelView
    private lateinit var micButton: TextView
    private val slots = arrayOfNulls<TextView>(3)
    private var shown = arrayOfNulls<Sugg>(3)
    private var mediaPanel: MediaPanel? = null
    private lateinit var searchRow: LinearLayout
    private lateinit var searchText: TextView
    private var clipPanel: View? = null
    private var clipAdapter: ClipAdapter? = null
    private var panel = Panel.KEYBOARD

    // Zustand
    private var mode = Mode.LETTERS
    private var field = FieldKind.TEXT
    private var suggestionsOn = true
    private val composing = StringBuilder()
    private var composingPrev: String? = null
    private var composingSentenceStart = false
    private var correctionTarget: String? = null
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
        engine = SuggestionEngine(this).also { it.loadAsync() }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
        }
        root.addView(buildBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)))
        keyboard = KeyboardView(this, theme).also { it.listener = this }
        content = FrameLayout(this)
        content.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        correctionTarget = null
        lastAutoSpace = false
        gestureAlts = null
        lastWasGesture = false
        lastAutocorrect = null
        endSearch()
        keyboard.haptics = prefs.vibrate
        keyboard.keyHeightPercent = prefs.keyHeightPercent

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
        suggestionsOn = cls == InputType.TYPE_CLASS_TEXT && field == FieldKind.TEXT &&
            info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        mode = if (field == FieldKind.NUMBER || field == FieldKind.PHONE) Mode.NUMPAD else Mode.LETTERS
        applyLayout()
        keyboard.enterLabel = enterLabel(info)
        // In den Einstellungen wurde „Gelernte Wörter löschen“ gedrückt?
        if (getSharedPreferences("settings", MODE_PRIVATE).getLong("user_data_cleared", 0L) > engineCreatedAt) {
            engine.clearUserData()
            engineCreatedAt = System.currentTimeMillis()
        }
        voice.vocabulary = engine.topUserWords(60)
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
            if (!voice.isActive && newSelStart != oldSelStart + 1) refreshSuggestions()
        }
    }

    private fun applyLayout() {
        keyboard.rows = when (mode) {
            Mode.LETTERS -> Layouts.letters(prefs.numberRow, field)
            Mode.SYMBOLS -> Layouts.symbols()
            Mode.SYMBOLS2 -> Layouts.symbols2()
            Mode.NUMPAD -> Layouts.numpad(field == FieldKind.PHONE)
        }
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
        lastEditTime = SystemClock.uptimeMillis()
        lastAutocorrect = null
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
                composingPrev = ctx.first
                composingSentenceStart = ctx.second
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
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
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
        composingPrev = ctx.first
        composingSentenceStart = ctx.second
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

    override fun onSpaceLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
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
            val fix = engine.autocorrectFor(w, prev)
            if (fix != null && fix != w) {
                ic.commitText(fix, 1)
                lastAutocorrect = w to fix
                engine.learn(fix, prev, composingSentenceStart)
                return
            }
        }
        if (prefs.markTypos && w.length > 1 && w.any { it.isLetter() } && !engine.isKnown(w)) {
            val corr = engine.correctionsFor(w, prev)
            val sp = SpannableString(w)
            try {
                sp.setSpan(
                    SuggestionSpan(this, corr.take(5).toTypedArray(),
                        SuggestionSpan.FLAG_MISSPELLED or SuggestionSpan.FLAG_EASY_CORRECT),
                    0, w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } catch (_: Exception) { }
            ic.commitText(sp, 1)
            if (corr.isNotEmpty()) {
                correctionTarget = w
                correctionPrev = prev
            }
        } else {
            ic.commitText(w, 1)
        }
        engine.learn(w, prev, composingSentenceStart)
    }

    /** Liefert (vorheriges Wort, Satzanfang?) für den Text vor dem Cursor. */
    private fun contextBefore(text: String? = null): Pair<String?, Boolean> {
        val before = text ?: currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: ""
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return null to true
        if (trimmed.last() in ".!?\n" || before.endsWith("\n")) return null to true
        val m = Regex("([\\p{L}\\p{N}'-]+)$").find(trimmed)
        return m?.value to false
    }

    private fun refreshSuggestions() {
        if (!::keyboard.isInitialized || voice.isActive) return
        val token = ++suggestToken
        val typed = composing.toString()
        val target = correctionTarget
        val targetPrev = correctionPrev
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
            val (prev, start) = contextBefore()
            worker.execute {
                val preds = engine.predictNext(prev, start)
                main.post {
                    if (token != suggestToken) return@post
                    val items = mutableListOf<Sugg>()
                    if (clipText != null) items += Sugg.Paste(clipText)
                    items += preds.map { Sugg.Word(it) }
                    show(items)
                }
            }
            return
        }
        worker.execute {
            val items = mutableListOf<Sugg>()
            if (typed.isNotEmpty()) {
                val list = engine.suggest(typed, composingPrev)
                val known = engine.isKnown(typed)
                val best = list.firstOrNull()
                if (best == null || best.equals(typed, ignoreCase = false) || (known && best.equals(typed, true))) {
                    // Getipptes Wort passt: in die Mitte, Alternativen links und rechts
                    val rest = list.filter { it != typed }
                    rest.getOrNull(0)?.let { items += Sugg.Word(it) }
                    items += Sugg.Word(typed, if (known) typed else "„$typed“")
                    rest.getOrNull(1)?.let { items += Sugg.Word(it) }
                } else {
                    items += Sugg.Word(typed, "„$typed“")
                    items += Sugg.Word(best)
                    list.getOrNull(1)?.let { items += Sugg.Word(it) }
                }
                if (prefs.emojiSuggestions) {
                    EmojiData.keywords[typed.lowercase()]?.let { e ->
                        if (items.size >= 3) items[2] = Sugg.Emoji(e) else items += Sugg.Emoji(e)
                    }
                }
            } else if (target != null) {
                val corr = engine.correctionsFor(target, targetPrev)
                items += Sugg.AddWord(target)
                items += corr.take(2).map { Sugg.Correction(target, it) }
            }
            main.post { if (token == suggestToken) show(items, centerFirst = typed.isEmpty() && target != null) }
        }
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
            tv.setTypeface(null, if (i == 1 && s is Sugg.Word) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(
                when (s) {
                    is Sugg.Correction -> theme.accent
                    is Sugg.AddWord -> theme.hint
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
                applyCorrection(s.target, s.replacement)
                engine.forget(s.target)
            }
            is Sugg.AddWord -> {
                engine.addWord(s.word)
                correctionTarget = null
                removeTypoMark(s.word)
                Toast.makeText(this, "„${s.word}“ ins Wörterbuch aufgenommen", Toast.LENGTH_SHORT).show()
            }
            is Sugg.Emoji -> {
                commitWord()
                val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
                val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
                ic.commitText(prefix + s.emoji + " ", 1)
                addRecentEmoji(s.emoji)
                lastAutoSpace = true
                correctionTarget = null
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
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - idx, 0)
        ic.commitText(replacement + tail.ifEmpty { " " }, 1)
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
        suggRow.visibility = View.GONE
        voiceRow.visibility = View.VISIBLE
        voiceStatus.text = if (engineName == "Whisper") "Ich höre zu … tippe ■ zum Beenden" else "Ich höre zu …"
        levelView.level = 0f
    }

    override fun onLevel(level: Float) { levelView.level = level }

    override fun onPartial(text: String) {
        voiceStatus.text = text
    }

    override fun onProcessing() {
        voiceStatus.text = "Wird erkannt …"
        levelView.level = 0f
    }

    override fun onResult(text: String) {
        endVoiceUi()
        val ic = currentInputConnection ?: return
        var t = text.trim()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val (_, sentenceStart) = contextBefore()
        if (!sentenceStart && t.length > 1 && t[0].isUpperCase() && t[1].isLowerCase()) {
            // Mitten im Satz: erstes Wort nur groß lassen, wenn es ein Hauptwort/Name ist
            val first = Regex("^[\\p{L}]+").find(t)?.value
            val form = first?.let { engine.dictionaryForm(it) }
            if (form != null && form[0].isLowerCase()) t = t.replaceFirstChar { it.lowercase() }
        }
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        ic.commitText("$prefix$t ", 1)
        lastAutoSpace = true
        // Diktierte Wörter für Vorhersagen lernen
        var prev: String? = null
        for (w in Regex("[\\p{L}'-]+").findAll(t).map { it.value }) {
            engine.learn(w, prev, prev == null); prev = w
        }
        updateAutoShift()
        refreshSuggestions()
    }

    override fun onError(message: String) {
        endVoiceUi()
        voiceRow.visibility = View.GONE
        suggRow.visibility = View.VISIBLE
        show(emptyList())
        slots[1]?.apply {
            text = message
            setTextColor(theme.hint)
            setTypeface(null, Typeface.NORMAL)
        }
    }

    override fun onIdle() {
        endVoiceUi()
        refreshSuggestions()
    }

    private fun endVoiceUi() {
        if (!::voiceRow.isInitialized) return
        voiceRow.visibility = View.GONE
        suggRow.visibility = View.VISIBLE
        micButton.text = "🎤"
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

    private fun rippleBg() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(theme.keyPressed), null,
        GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(8f).toFloat() },
    )

    private fun buildBar(): View {
        val frame = FrameLayout(this)
        suggRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        suggRow.addView(barButton("📋") { showPanel(if (panel == Panel.CLIPBOARD) Panel.KEYBOARD else Panel.CLIPBOARD) },
            LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.MATCH_PARENT))
        for (i in 0..2) {
            if (i > 0) suggRow.addView(View(this).apply { setBackgroundColor(theme.divider) },
                LinearLayout.LayoutParams(dp(1f), dp(22f)))
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(theme.barText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4f), 0, dp(4f), 0)
                background = rippleBg()
                setOnClickListener { pickSuggestion(i) }
                setOnLongClickListener {
                    val s = shown[i]
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
        micButton = barButton("🎤") { onMicTap() }
        suggRow.addView(micButton, LinearLayout.LayoutParams(dp(52f), ViewGroup.LayoutParams.MATCH_PARENT))

        voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        voiceRow.addView(barButton("✕") { voice.cancel() }, LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.MATCH_PARENT))
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
        searchRow.addView(barButton("Suchen") { runSearch() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(theme.accent); setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(76f), ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(searchRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(suggRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(voiceRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun panelHeight(): Int =
        if (keyboard.height > 0) keyboard.height else (4 * dp(54f) * prefs.keyHeightPercent / 100 + dp(6f))

    private fun showPanel(p: Panel) {
        if (!::content.isInitialized) return
        if (p != Panel.KEYBOARD) commitWord()
        val h = panelHeight()
        panel = p
        keyboard.visibility = if (p == Panel.KEYBOARD) View.VISIBLE else View.GONE
        mediaPanel?.visibility = View.GONE
        clipPanel?.visibility = View.GONE
        when (p) {
            Panel.MEDIA -> {
                val v = mediaPanel ?: MediaPanel(this, theme, giphy, stickers, this).also {
                    mediaPanel = it
                    content.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
                }
                v.layoutParams.height = h
                v.refreshRecents()
                v.visibility = View.VISIBLE
            }
            Panel.CLIPBOARD -> {
                clip.capture()
                val v = clipPanel ?: buildClipPanel().also {
                    clipPanel = it
                    content.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
                }
                v.layoutParams.height = h
                clipAdapter?.notifyDataSetChanged()
                v.visibility = View.VISIBLE
            }
            Panel.KEYBOARD -> {}
        }
        content.requestLayout()
    }

    // ---------------------------------------------------------------- Emoji, GIFs, Sticker

    private fun addRecentEmoji(e: String) {
        val sp = getSharedPreferences("emoji", MODE_PRIVATE)
        val old = sp.getString("recent", "")!!.split('\u0001').filter { it.isNotEmpty() && it != e }
        sp.edit().putString("recent", (listOf(e) + old).take(32).joinToString("\u0001")).apply()
    }

    override fun commitEmoji(e: String) {
        commitWord()
        currentInputConnection?.commitText(e, 1)
        addRecentEmoji(e)
    }

    override fun deleteChar() = handleDelete()

    override fun backToKeyboard() = showPanel(Panel.KEYBOARD)

    override fun haptic(v: View) {
        if (prefs.vibrate) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
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
        suggRow.visibility = View.GONE
        voiceRow.visibility = View.GONE
        searchRow.visibility = View.VISIBLE
        updateSearchUi()
    }

    private fun updateSearchUi() {
        val target = if (searchTarget == MediaPanel.Tab.STICKER) "Sticker" else "GIFs"
        searchText.text = if (searchQuery.isEmpty()) "🔍 $target suchen …" else "🔍 $searchQuery▏"
        searchText.setTextColor(if (searchQuery.isEmpty()) theme.hint else theme.text)
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
        if (::searchRow.isInitialized) {
            searchRow.visibility = View.GONE
            suggRow.visibility = View.VISIBLE
        }
        applyLayout()
    }

    // ---------------------------------------------------------------- Wischen zum Schreiben

    override fun onGesture(path: List<GestureDecoder.Pt>) {
        val centers = keyboard.letterCenters()
        val keyW = keyboard.letterKeyWidth()
        val prev = if (composing.isNotEmpty()) composing.toString() else contextBefore().first
        worker.execute {
            val words = decoder.decode(path, centers, keyW, prev)
            val cased = words.map { engine.displayForm(it) }
            main.post { applyGesture(cased) }
        }
    }

    private fun applyGesture(words: List<String>) {
        if (words.isEmpty()) {
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
        composingPrev = ctx.first
        composingSentenceStart = ctx.second
        fun cased(w: String) = when {
            keyboard.shift == ShiftState.LOCK -> w.uppercase()
            keyboard.shift == ShiftState.ON || ctx.second -> w.replaceFirstChar { it.uppercase() }
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
