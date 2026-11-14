package de.schranner.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ShiftState { OFF, ON, LOCK }

/**
 * Zeichnet die Tasten selbst und kümmert sich um Berührungen:
 * Mehrfinger-Tippen, langes Drücken für Sonderzeichen, Löschen mit Wiederholung
 * und Cursor-Steuerung durch Wischen auf der Leertaste.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val theme: KeyboardTheme) : View(context) {

    interface Listener {
        fun onChar(text: String)
        fun onAction(type: KeyType)
        fun onCursorMove(steps: Int)
        fun onSpaceLongPress()
        fun onEmojiLongPress()
        fun onDeleteWord()
        fun onGesture(path: List<GestureDecoder.Pt>)
    }

    var listener: Listener? = null
    /** Vibration beim Tippen (Stärke stellt die Tastatur ein). */
    var haptics: Haptics? = null
    /** Wischen zum Schreiben aktiv (nur auf der Buchstaben-Tastatur). */
    var gestureEnabled = false
    var gestureTrail = true

    /** Treffhilfe: Gewicht je Buchstabentaste (1 = wahrscheinlichster nächster Buchstabe). */
    var touchWeights: Map<Char, Float> = emptyMap()
    /** Stärke der Treffhilfe, 0..1 (0 = aus). Die Tasten sehen immer gleich aus. */
    var touchAssist = 0f

    var rows: KeyRows = emptyList()
        set(value) {
            field = value
            requestLayout()
            layoutKeys()
            invalidate()
        }

    var shift = ShiftState.OFF
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    var enterLabel = "⏎"
        set(value) { field = value; invalidate() }

    var spaceLabel = "Deutsch"
        set(value) { field = value; invalidate() }

    /** Größe der Tastatur in Prozent (100 = Standard). Schrift und Symbole skalieren mit. */
    var keyHeightPercent = 100
        set(value) {
            if (field == value) return
            field = value
            requestLayout(); layoutKeys(); invalidate()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = theme.accent
        alpha = 190
        strokeWidth = dp(5f)
    }
    private val trailPath = android.graphics.Path()
    // Maße nach Gboard (dort vermessen): Tasten 92 px breit mit 14,5 px Abstand bei 1080 px Breite
    private val gapX = dp(2.6f)
    private val sidePad = dp(2.1f)
    private val topPad = dp(5f)
    private val bottomPad = dp(3f)

    private fun icon(id: Int) = context.getDrawable(id)!!.mutate()
    private val icShift = icon(R.drawable.ic_shift)
    private val icShiftOn = icon(R.drawable.ic_shift_on)
    private val icShiftLock = icon(R.drawable.ic_shift_lock)
    private val icDelete = icon(R.drawable.ic_backspace)
    private val icEmoji = icon(R.drawable.ic_emoji)
    private val icEnter = icon(R.drawable.ic_enter)
    private val icSearch = icon(R.drawable.ic_search)
    private val icSend = icon(R.drawable.ic_send)
    private val icDone = icon(R.drawable.ic_done)
    private val icNext = icon(R.drawable.ic_next)

    // ---- Berührungen ----
    private class Touch(val id: Int, var key: Key?, val downX: Float) {
        var consumed = false
        var longPressed = false
        var spaceDrag = false
        var deleteSwipe = false
        var lastCursorX = downX
        var downY = 0f
        var gesturePossible = false
        var gesture = false
        val points = ArrayList<GestureDecoder.Pt>()
    }

    private val touches = mutableMapOf<Int, Touch>()
    private var longPressTouch: Touch? = null
    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            listener?.onAction(KeyType.DELETE)
            handler.postDelayed(this, REPEAT_INTERVAL)
        }
    }

    // ---- Popups ----
    private val previewText = android.widget.TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.text)
        textSize = 28f
        background = roundRect(theme.popup)
        elevation = dp(4f)
    }
    private val previewPopup = PopupWindow(previewText).apply {
        isClippingEnabled = false
        isTouchable = false
        setBackgroundDrawable(ColorDrawable(0))
    }
    private val altsView = AltsView(context, theme)
    private val altsPopup = PopupWindow(altsView).apply {
        isClippingEnabled = false
        isTouchable = false
        setBackgroundDrawable(ColorDrawable(0))
    }
    private var altsLeftInView = 0f
    private var altsCellWidth = 0f

    private fun roundRect(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10f)
    }

    // ---------------------------------------------------------------- Layout

    /** Reihenabstand der Buchstabenreihen. Alles andere (Abstände, Schrift, Symbole) richtet sich danach. */
    private fun rowHeight(): Float = dp(BASE_ROW_DP) * keyHeightPercent / 100f

    private fun gapY(): Float = (rowHeight() * 0.215f).coerceIn(dp(5f), dp(10f))

    private fun isNumberRow(r: List<Key>, all: KeyRows) =
        all.size >= 5 && r === all[0] && r.all { it.label.length == 1 && it.label[0].isDigit() }

    // Wie bei Gboard: Zahlenreihe etwas niedriger, unterste Reihe etwas höher.
    private fun baseHeight(r: List<Key>, all: KeyRows) = when {
        isNumberRow(r, all) -> rowHeight() * 0.924f
        r === all.last() -> rowHeight() * 1.076f
        else -> rowHeight()
    }

    private fun sumHeights(all: KeyRows) = all.sumOf { baseHeight(it, all).toDouble() }.toFloat()

    /**
     * Referenz-Layout (Buchstaben): Zahlen-/Symbolseiten werden auf dieselbe Höhe gestreckt,
     * damit die Tastatur beim Umschalten nicht springt.
     */
    var referenceRows: KeyRows? = null
        set(value) { field = value; requestLayout() }

    private fun heightOf(row: List<Key>): Float {
        val base = baseHeight(row, rows)
        val ref = referenceRows ?: return base
        val own = sumHeights(rows)
        return if (own > 0f) base * sumHeights(ref) / own else base
    }

    private fun totalHeight() =
        (referenceRows?.let { sumHeights(it) } ?: sumHeights(rows)) + topPad + bottomPad

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, totalHeight().toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private val lastRowKeys = HashSet<Key>()

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f) return
        var top = topPad
        val gy = gapY()
        val inner = w - 2 * sidePad
        val maxWeight = rows.maxOfOrNull { r -> r.sumOf { it.weight.toDouble() } }?.toFloat() ?: return
        lastRowKeys.clear()
        rows.lastOrNull()?.let { lastRowKeys.addAll(it) }
        rows.forEach { row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            // Reihen mit weniger Tasten behalten die Tastenbreite und werden zentriert
            val unit = inner / maxWeight
            var x = sidePad + (inner - unit * total) / 2f
            val rh = heightOf(row)
            for (k in row) {
                val kw = unit * k.weight
                k.bounds.set(x + gapX, top + gy / 2f, x + kw - gapX, top + rh - gy / 2f)
                x += kw
            }
            top += rh
        }
        targetKeys.clear()
        for (row in rows) for (k in row) if (isLetter(k)) targetKeys[k.label[0].lowercaseChar()] = k
        targets = targetKeys.map { (ch, k) -> TouchModel.Target(ch, k.bounds.centerX(), k.bounds.centerY()) }
    }

    private var targets: List<TouchModel.Target> = emptyList()
    private val targetKeys = HashMap<Char, Key>()

    /** Welche Buchstaben haben eine eigene Taste? */
    fun letterKeys(): Set<Char> = HashSet(targetKeys.keys)

    /** Mitte jeder Buchstabentaste – für die Wisch-Erkennung. */
    fun letterCenters(): Map<Char, GestureDecoder.Pt> {
        val m = HashMap<Char, GestureDecoder.Pt>()
        for (row in rows) for (k in row) if (isLetter(k)) {
            m[k.label[0].lowercaseChar()] = GestureDecoder.Pt(k.bounds.centerX(), k.bounds.centerY())
        }
        return m
    }

    fun letterKeyWidth(): Float =
        rows.flatten().firstOrNull { isLetter(it) }?.let { it.bounds.width() + 2 * gapX } ?: dp(36f)

    private fun isLetter(k: Key) = k.type == KeyType.CHAR && k.label.length == 1 && k.label[0].isLetter()

    private fun findKey(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        var r = rows.size - 1
        var top = topPad
        for ((i, row) in rows.withIndex()) {
            top += heightOf(row)
            if (y < top) { r = i; break }
        }
        val base = rows[r].minByOrNull { k ->
            when {
                x < k.bounds.left -> k.bounds.left - x
                x > k.bounds.right -> x - k.bounds.right
                else -> 0f
            }
        }
        // Treffhilfe: wahrscheinliche Buchstaben haben unsichtbar eine größere Trefferfläche
        if (base != null && touchAssist > 0f && touchWeights.isNotEmpty() && isLetter(base)) {
            val t = TouchModel.pick(x, y, targets, letterKeyWidth(), heightOf(rows[r]), touchWeights, touchAssist)
            if (t != null) return targetKeys[t.ch] ?: base
        }
        return base
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val pressed = touches.values.mapNotNull { if (it.consumed || it.gesture) null else it.key }.toSet()
        for (row in rows) for (k in row) drawKey(canvas, k, k in pressed)
        if (gestureTrail) {
            val g = touches.values.firstOrNull { it.gesture } ?: return
            trailPath.reset()
            val pts = g.points
            val from = maxOf(0, pts.size - 120)
            for (i in from until pts.size) {
                if (i == from) trailPath.moveTo(pts[i].x, pts[i].y) else trailPath.lineTo(pts[i].x, pts[i].y)
            }
            canvas.drawPath(trailPath, trailPaint)
        }
    }

    private val pillRect = RectF()

    private fun drawKey(canvas: Canvas, k: Key, pressed: Boolean) {
        val rh = rowHeight()
        val inLastRow = k in lastRowKeys
        val switcher = k.type == KeyType.SYMBOLS || k.type == KeyType.SYMBOLS2 || k.type == KeyType.LETTERS
        // Funktionstasten sind etwas heller als Buchstabentasten (wie bei Gboard)
        val functional = k.type == KeyType.SHIFT || k.type == KeyType.DELETE || switcher ||
            (k.type == KeyType.CHAR && inLastRow && !k.label[0].isLetterOrDigit())
        keyPaint.color = when {
            pressed -> theme.keyPressed
            k.type == KeyType.ENTER -> theme.accent
            k.type == KeyType.SHIFT && shift == ShiftState.LOCK -> theme.accent
            functional -> theme.specialKey
            else -> theme.key
        }
        if (inLastRow && (switcher || k.type == KeyType.ENTER)) {
            // „?123“ und Enter als Pille
            pillRect.set(k.bounds)
            pillRect.inset(dp(2.4f), rh * 0.085f)
            canvas.drawRoundRect(pillRect, pillRect.height() / 2f, pillRect.height() / 2f, keyPaint)
        } else {
            val r = minOf(dp(7f), k.bounds.height() / 3f)
            canvas.drawRoundRect(k.bounds, r, r, keyPaint)
        }

        var label = k.label
        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = theme.text
        textPaint.textSize = rh * 0.51f
        fun drawIcon(d: android.graphics.drawable.Drawable, color: Int, size: Float) {
            val cx = k.bounds.centerX().toInt()
            val cy = k.bounds.centerY().toInt()
            val h = (minOf(size, k.bounds.height() * 0.95f) / 2).toInt()
            d.setBounds(cx - h, cy - h, cx + h, cy + h)
            d.setTint(color)
            d.draw(canvas)
        }
        when (k.type) {
            KeyType.CHAR -> {
                if (shift != ShiftState.OFF && label.length == 1 && label != "ß") label = label.uppercase()
                if (label.length > 1) textPaint.textSize = rh * 0.36f
            }
            KeyType.SHIFT -> {
                val d = when (shift) { ShiftState.OFF -> icShift; ShiftState.ON -> icShiftOn; ShiftState.LOCK -> icShiftLock }
                drawIcon(d, if (shift == ShiftState.LOCK) theme.accentText else theme.text, rh * 0.70f)
                return
            }
            KeyType.DELETE -> { drawIcon(icDelete, theme.text, rh * 0.62f); return }
            KeyType.EMOJI -> { drawIcon(icEmoji, theme.text, rh * 0.64f); return }
            KeyType.ENTER -> {
                val d = when (enterLabel) {
                    "🔍" -> icSearch; "➤" -> icSend; "✓" -> icDone; "➜", "⇥" -> icNext; else -> icEnter
                }
                drawIcon(d, theme.accentText, rh * 0.66f)
                return
            }
            KeyType.SPACE -> {
                label = spaceLabel
                textPaint.color = theme.hint
                textPaint.textSize = rh * 0.30f
            }
            KeyType.SYMBOLS, KeyType.SYMBOLS2, KeyType.LETTERS -> {
                textPaint.textSize = rh * 0.35f
            }
        }
        val cy = k.bounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, k.bounds.centerX(), cy, textPaint)
    }

    // ---------------------------------------------------------------- Touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (touches.values.any { it.gesture }) return true
                // Schnelles Tippen mit zwei Daumen: vorherige Taste sofort auslösen
                for (t in touches.values) {
                    if (!t.consumed && !t.longPressed && !t.spaceDrag && !t.deleteSwipe) {
                        fire(t.key)
                        t.consumed = true
                    }
                }
                cancelTimers()
                hidePreview()
                val t = Touch(e.getPointerId(idx), findKey(e.getX(idx), e.getY(idx)), e.getX(idx))
                t.downY = e.getY(idx)
                t.gesturePossible = gestureEnabled && touches.isEmpty() && t.key?.let { isLetter(it) } == true
                if (t.gesturePossible) t.points += GestureDecoder.Pt(e.getX(idx), t.downY)
                touches[t.id] = t
                onDown(t)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(i)] ?: continue
                    if (t.gesturePossible) {
                        for (h in 0 until e.historySize) {
                            t.points += GestureDecoder.Pt(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                        }
                        t.points += GestureDecoder.Pt(e.getX(i), e.getY(i))
                    }
                    onMove(t, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches.remove(e.getPointerId(idx))
                if (t != null) onUp(t)
            }
            MotionEvent.ACTION_CANCEL -> {
                touches.clear()
                cancelTimers()
                hidePreview()
                hideAlts()
            }
        }
        invalidate()
        return true
    }

    private fun onDown(t: Touch) {
        val k = t.key ?: return
        feedback()
        when (k.type) {
            KeyType.DELETE -> {
                listener?.onAction(KeyType.DELETE)
                handler.postDelayed(repeatRunnable, REPEAT_DELAY)
            }
            KeyType.SHIFT -> {
                listener?.onAction(KeyType.SHIFT)
                t.consumed = true
            }
            KeyType.CHAR -> {
                showPreview(k)
                scheduleLongPress(t, LONG_PRESS)
            }
            KeyType.SPACE -> scheduleLongPress(t, LONG_PRESS_SPACE)
            KeyType.EMOJI -> scheduleLongPress(t, LONG_PRESS_SPACE)
            else -> {}
        }
    }

    private fun onMove(t: Touch, x: Float, y: Float) {
        if (t.consumed) return
        val k = t.key ?: return
        if (t.gesture) return
        if (t.gesturePossible && !t.longPressed) {
            val dx = x - t.downX
            val dy = y - t.downY
            if (sqrt(dx * dx + dy * dy) > letterKeyWidth() * 0.8f) {
                t.gesture = true
                cancelTimers()
                hidePreview()
                return
            }
        }
        if (t.longPressed) {
            if (altsPopup.isShowing) {
                val i = ((x - altsLeftInView) / altsCellWidth).toInt().coerceIn(0, altsView.items.size - 1)
                altsView.selected = i
            }
            return
        }
        when (k.type) {
            KeyType.SPACE -> {
                if (!t.spaceDrag && abs(x - t.downX) > dp(14f)) {
                    t.spaceDrag = true
                    cancelTimers()
                    t.lastCursorX = x
                }
                if (t.spaceDrag) {
                    val step = dp(11f)
                    val steps = ((x - t.lastCursorX) / step).toInt()
                    if (steps != 0) {
                        listener?.onCursorMove(steps)
                        t.lastCursorX += steps * step
                        feedback()
                    }
                }
            }
            KeyType.DELETE -> {
                if (!t.deleteSwipe && t.downX - x > dp(40f)) {
                    // Nach links wischen auf ⌫ löscht ganze Wörter
                    t.deleteSwipe = true
                    cancelTimers()
                    t.lastCursorX = x
                    listener?.onDeleteWord()
                } else if (t.deleteSwipe && t.lastCursorX - x > dp(35f)) {
                    t.lastCursorX = x
                    listener?.onDeleteWord()
                    feedback()
                }
            }
            KeyType.CHAR -> {
                if (gestureEnabled) return
                // Finger auf benachbarte Taste geschoben: neue Taste übernehmen
                val nk = findKey(x, y)
                if (nk != null && nk != k && nk.type == KeyType.CHAR) {
                    t.key = nk
                    cancelTimers()
                    showPreview(nk)
                    scheduleLongPress(t, LONG_PRESS)
                }
            }
            else -> {}
        }
    }

    private fun onUp(t: Touch) {
        cancelTimers()
        hidePreview()
        if (t.consumed) return
        if (t.gesture) {
            listener?.onGesture(ArrayList(t.points))
            invalidate()
            return
        }
        val k = t.key ?: return
        when {
            t.longPressed -> {
                if (altsPopup.isShowing) {
                    val s = altsView.items.getOrNull(altsView.selected)
                    hideAlts()
                    if (s != null) listener?.onChar(applyShift(s))
                }
            }
            t.spaceDrag || t.deleteSwipe -> {}
            k.type == KeyType.DELETE -> {}
            else -> fire(k)
        }
    }

    private fun fire(k: Key?) {
        k ?: return
        when (k.type) {
            KeyType.CHAR -> listener?.onChar(applyShift(k.label))
            KeyType.SHIFT, KeyType.DELETE -> {}
            else -> listener?.onAction(k.type)
        }
    }

    private fun applyShift(s: String): String =
        if (shift != ShiftState.OFF && s.length == 1 && s != "ß") s.uppercase() else s

    private fun scheduleLongPress(t: Touch, delay: Long) {
        longPressTouch = t
        handler.postDelayed(longPressRunnable, delay)
    }

    private fun onLongPress() {
        val t = longPressTouch ?: return
        if (t.consumed || touches[t.id] !== t) return
        val k = t.key ?: return
        when (k.type) {
            KeyType.SPACE -> { t.consumed = true; listener?.onSpaceLongPress() }
            KeyType.EMOJI -> { t.consumed = true; listener?.onEmojiLongPress() }
            KeyType.CHAR -> {
                if (k.alts.isEmpty()) return
                t.longPressed = true
                hidePreview()
                feedback()
                showAlts(k)
            }
            else -> {}
        }
    }

    private fun cancelTimers() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
    }

    private fun feedback() { haptics?.tap() }

    // ---------------------------------------------------------------- Popups

    private fun showPreview(k: Key) {
        if (windowToken == null) return
        val w = (k.bounds.width() + dp(16f)).toInt()
        val h = (k.bounds.height() * 1.25f).toInt()
        previewText.text = applyShift(k.label)
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_PX, rowHeight() * 0.6f)
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val x = loc[0] + k.bounds.centerX().toInt() - w / 2
        val y = loc[1] + k.bounds.top.toInt() - h - dp(4f).toInt()
        try {
            if (previewPopup.isShowing) previewPopup.update(x, y, w, h)
            else {
                previewPopup.width = w; previewPopup.height = h
                previewPopup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
            }
        } catch (_: Exception) { }
    }

    private fun hidePreview() {
        if (previewPopup.isShowing) previewPopup.dismiss()
    }

    private fun showAlts(k: Key) {
        if (windowToken == null) return
        val items = listOf(k.label) + k.alts
        altsView.items = items.map { applyShift(it) }
        altsCellWidth = max(k.bounds.width(), dp(34f))
        val w = (altsCellWidth * items.size).toInt()
        val h = (k.bounds.height() * 1.1f).toInt()
        var left = k.bounds.centerX() - altsCellWidth / 2f
        left = min(left, width - w.toFloat()).coerceAtLeast(0f)
        altsLeftInView = left
        // Vorauswahl: erstes Sonderzeichen (z. B. Ziffer oder Umlaut)
        altsView.selected = if (items.size > 1) 1 else 0
        val loc = IntArray(2)
        getLocationInWindow(loc)
        try {
            altsPopup.width = w; altsPopup.height = h
            altsPopup.showAtLocation(this, Gravity.NO_GRAVITY,
                loc[0] + left.toInt(), loc[1] + k.bounds.top.toInt() - h - dp(4f).toInt())
        } catch (_: Exception) { }
    }

    private fun hideAlts() {
        if (altsPopup.isShowing) altsPopup.dismiss()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTimers()
        hidePreview()
        hideAlts()
    }

    /** Zeile mit Sonderzeichen, die beim langen Drücken erscheint. */
    private class AltsView(context: Context, val theme: KeyboardTheme) : View(context) {
        var items: List<String> = emptyList()
            set(v) { field = v; invalidate() }
        var selected = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        private val d = context.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 22f * context.resources.displayMetrics.scaledDensity
        }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            if (items.isEmpty()) return
            bg.color = theme.popup
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(r, 10 * d, 10 * d, bg)
            val cw = width.toFloat() / items.size
            items.forEachIndexed { i, s ->
                if (i == selected) {
                    bg.color = theme.popupSelected
                    r.set(i * cw + 2 * d, 2 * d, (i + 1) * cw - 2 * d, height - 2 * d)
                    canvas.drawRoundRect(r, 8 * d, 8 * d, bg)
                    tp.color = theme.accentText
                } else tp.color = theme.text
                tp.textSize = (if (s.length > 2) 15f else 22f) * resources.displayMetrics.scaledDensity
                val cy = height / 2f - (tp.descent() + tp.ascent()) / 2f
                canvas.drawText(s, i * cw + cw / 2f, cy, tp)
            }
        }
    }

    companion object {
        /** Reihenabstand bei 100 % – entspricht einer kompakt eingestellten Gboard. */
        const val BASE_ROW_DP = 37.3f
        const val LONG_PRESS = 330L
        const val LONG_PRESS_SPACE = 650L
        const val REPEAT_DELAY = 400L
        const val REPEAT_INTERVAL = 45L
    }
}
