package de.schranner.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.widget.TextView

/** Textfeld der Vorschlagsleiste, das ein Wort unterkringeln kann (rot: unbekannt, blau: Großschreibung/Grammatik). */
@SuppressLint("AppCompatCustomView", "ViewConstructor")
class SquiggleTextView(context: Context, color: Int) : TextView(context) {

    var squiggle = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Farbe der Wellenlinie. */
    var squiggleColor: Int = color
        set(v) { if (field != v) { field = v; wave.color = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (!squiggle || text.isNullOrEmpty()) return
        // Wellenlinie knapp unter der Grundlinie, so breit wie der Text
        val left = totalPaddingLeft + l.getLineLeft(0)
        val right = totalPaddingLeft + l.getLineRight(0)
        val y = totalPaddingTop + l.getLineBaseline(0) + 4.5f * d
        val step = 3.2f * d
        val amp = 1.7f * d
        path.reset()
        path.moveTo(left, y)
        var x = left
        var up = true
        while (x < right) {
            val nx = minOf(x + step, right)
            path.quadTo((x + nx) / 2f, if (up) y - amp else y + amp, nx, y)
            x = nx
            up = !up
        }
        canvas.drawPath(path, wave)
    }
}
