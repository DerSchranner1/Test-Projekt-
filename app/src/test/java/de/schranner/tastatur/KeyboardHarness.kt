package de.schranner.tastatur

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.TextView
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Startet die echte Tastatur ohne Handy und verbindet sie mit einem echten Textfeld.
 * Tasten werden wie mit dem Finger angetippt (Berührung an der Tastenposition).
 */
class KeyboardHarness(
    inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
    configure: (Prefs) -> Unit = {},
) {
    val service: KeyboardService = Robolectric.setupService(KeyboardService::class.java)
    val prefs = Prefs(service).also(configure)
    val edit = EditText(service).apply { this.inputType = inputType }
    val root: View
    val width = 1080

    private val engine: SuggestionEngine = ReflectionHelpers.getField(service, "engine")
    private val worker: ExecutorService = ReflectionHelpers.getField(service, "worker")
    val keyboard: KeyboardView get() = ReflectionHelpers.getField(service, "keyboard")

    init {
        while (!engine.isFullyLoaded()) Thread.sleep(20)
        root = service.onCreateInputView()
        val info = EditorInfo()
        val ic = edit.onCreateInputConnection(info)
        ReflectionHelpers.callInstanceMethod<Any>(
            service, "doStartInput",
            ClassParameter.from(InputConnection::class.java, ic),
            ClassParameter.from(EditorInfo::class.java, info),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        service.onStartInputView(info, false)
        layout()
        settle()
    }

    fun layout() {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    /** Warten, bis Vorschläge und Treffhilfe berechnet und angezeigt sind. */
    fun settle() {
        repeat(3) {
            worker.submit {}.get()
            shadowOf(Looper.getMainLooper()).idle()
        }
        layout()
    }

    val text: String get() = edit.text.toString()

    private fun key(match: (Key) -> Boolean): Key =
        keyboard.rows.flatten().firstOrNull(match) ?: error("Taste nicht gefunden")

    /** Taste an ihrer Position antippen. dx/dy: Abweichung von der Mitte in Tastenbreiten. */
    fun tapKey(k: Key, dx: Float = 0f, dy: Float = 0f) {
        val x = k.bounds.centerX() + dx * k.bounds.width()
        val y = k.bounds.centerY() + dy * k.bounds.height()
        val t = SystemClock.uptimeMillis()
        keyboard.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        keyboard.dispatchTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, x, y, 0))
        settle()
    }

    private fun touch(action: Int, x: Float, y: Float, view: View = keyboard) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0))
    }

    fun letter(c: Char): Key = key { it.type == KeyType.CHAR && it.label.equals(c.toString(), ignoreCase = true) }
    fun keyOf(type: KeyType): Key = key { it.type == type }

    /** Lang drücken; danach optional um [slideCells] Felder zur Seite oder [slideDown] Tastenhöhen nach unten schieben. */
    fun longPress(k: Key, slideCells: Float = 0f, slideDown: Float = 0f, release: Boolean = true) {
        val x = k.bounds.centerX()
        val y = k.bounds.centerY()
        touch(MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        if (!release) return
        val x2 = x + slideCells * (k.bounds.width() + 15f)
        val y2 = y + slideDown * k.bounds.height()
        if (slideCells != 0f || slideDown != 0f) touch(MotionEvent.ACTION_MOVE, x2, y2)
        touch(MotionEvent.ACTION_UP, x2, y2)
        settle()
    }

    fun release(k: Key) { touch(MotionEvent.ACTION_UP, k.bounds.centerX(), k.bounds.centerY()); settle() }

    /** Über die Buchstaben wischen (Wischen zum Schreiben). */
    fun swipe(word: String) {
        val pts = word.map { c -> letter(c).bounds.let { it.centerX() to it.centerY() } }
        touch(MotionEvent.ACTION_DOWN, pts[0].first, pts[0].second)
        for (i in 1 until pts.size) {
            val (ax, ay) = pts[i - 1]
            val (bx, by) = pts[i]
            for (s in 1..8) touch(MotionEvent.ACTION_MOVE, ax + (bx - ax) * s / 8f, ay + (by - ay) * s / 8f)
        }
        touch(MotionEvent.ACTION_UP, pts.last().first, pts.last().second)
        settle()
    }

    /** Auf einer Taste aufsetzen und waagerecht ziehen (Leertaste: Cursor, ⌫: Wörter löschen). */
    fun drag(k: Key, dxPx: Float, steps: Int = 10) {
        val x = k.bounds.centerX()
        val y = k.bounds.centerY()
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (s in 1..steps) touch(MotionEvent.ACTION_MOVE, x + dxPx * s / steps, y)
        touch(MotionEvent.ACTION_UP, x + dxPx, y)
        settle()
    }

    fun <T> field(name: String): T = ReflectionHelpers.getField(service, name)

    fun tap(label: String) = tapKey(key { it.type == KeyType.CHAR && it.label.equals(label, ignoreCase = true) })
    fun tap(type: KeyType) = tapKey(key { it.type == type })

    /** Text tippen: Buchstaben, Leerzeichen, Komma und Punkt. */
    fun type(s: String) {
        for (c in s) when (c) {
            ' ' -> tap(KeyType.SPACE)
            else -> tap(c.toString())
        }
    }

    fun space() = tap(KeyType.SPACE)
    fun backspace() = tap(KeyType.DELETE)

    /** Die drei Vorschläge (leer = kein Vorschlag an dieser Stelle). */
    fun suggestions(): List<String> {
        val slots: Array<TextView?> = ReflectionHelpers.getField(service, "slots")
        return slots.map { it?.text?.toString() ?: "" }
    }

    fun pick(index: Int) {
        val slots: Array<TextView?> = ReflectionHelpers.getField(service, "slots")
        slots[index]!!.performClick()
        settle()
    }

    fun pick(word: String) {
        val i = suggestions().indexOfFirst { it == word }
        check(i >= 0) { "Vorschlag „$word“ nicht in ${suggestions()}" }
        pick(i)
    }

    fun visibleRow(): String {
        for (name in listOf("toolRow", "suggRow", "voiceRow", "searchRow", "resizeRow")) {
            val v: View = ReflectionHelpers.getField(service, name)
            if (v.visibility == View.VISIBLE) return name
        }
        return "?"
    }

    fun call(method: String) {
        ReflectionHelpers.callInstanceMethod<Any>(service, method)
        settle()
    }

    fun screenshot(name: String) {
        layout()
        val bmp = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Screenshot $name: ${bmp.width}x${bmp.height}")
    }

    fun clickAll(view: View = root, pred: (View) -> Boolean): Boolean {
        if (pred(view) && view.hasOnClickListeners()) { view.performClick(); settle(); return true }
        if (view is ViewGroup) for (i in 0 until view.childCount) if (clickAll(view.getChildAt(i), pred)) return true
        return false
    }
}
