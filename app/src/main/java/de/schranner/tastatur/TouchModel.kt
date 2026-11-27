package de.schranner.tastatur

import kotlin.math.ln

/**
 * Unsichtbare Treffhilfe (wie bei der iPhone-Tastatur): Buchstaben, die als nächstes wahrscheinlich
 * sind, bekommen eine größere Trefferfläche – die Tasten sehen dabei unverändert aus.
 *
 * Die Grenze zwischen zwei Nachbartasten verschiebt sich höchstens um [MAX_SHIFT] des Tastenabstands.
 * Wer die Mitte einer Taste trifft, bekommt deshalb immer genau diese Taste.
 */
object TouchModel {

    class Target(val ch: Char, val cx: Float, val cy: Float)

    /** Größte Verschiebung einer Tastengrenze bei Stärke 100 % (Anteil am Tastenabstand). */
    const val MAX_SHIFT = 0.35f

    /** Unter dieser Wahrscheinlichkeit gibt es keinen Bonus (seltene Wörter wie „Bällebad“ vergrößern nichts). */
    const val MIN_PROB = 0.05f
    private val RANGE = ln(1f / MIN_PROB)

    /**
     * Wahrscheinlichkeiten je Buchstabe -> Gewicht je vorhandener Taste (1 = sicher, 0 = kein Bonus).
     * Maßgeblich ist die echte Wahrscheinlichkeit: Ist unklar, wie es weitergeht (mehrere Buchstaben
     * etwa gleich wahrscheinlich), wird keiner stark vergrößert.
     * Buchstaben ohne eigene Taste (ä, ö, ü, ß auf der schlichten Tastatur) zählen für ihre Grundtaste.
     */
    fun weights(probs: Map<Char, Float>, keys: Set<Char>): Map<Char, Float> {
        val folded = HashMap<Char, Float>()
        for ((ch, p) in probs) {
            val k = if (ch in keys) ch else SuggestionEngine.baseChar(ch)
            if (k in keys) folded[k] = (folded[k] ?: 0f) + p
        }
        val total = folded.values.sum()
        if (total <= 0f) return emptyMap()
        return folded.mapValues { (ln((it.value / total) / MIN_PROB) / RANGE).coerceIn(0f, 1f) }
            .filterValues { it > 0f }
    }

    /**
     * Welche Buchstabentaste ist gemeint?
     * @param strength 0 = aus (rein nach Abstand), 1 = volle Stärke
     */
    fun pick(
        x: Float, y: Float, targets: List<Target>, pitchX: Float, pitchY: Float,
        weights: Map<Char, Float>, strength: Float,
    ): Target? {
        var best: Target? = null
        var bestCost = Float.MAX_VALUE
        val bonus = 2f * MAX_SHIFT * strength.coerceIn(0f, 1f)
        for (t in targets) {
            val dx = (x - t.cx) / pitchX
            val dy = (y - t.cy) / pitchY
            if (dx > 1.6f || dx < -1.6f || dy > 1.6f || dy < -1.6f) continue
            val cost = dx * dx + dy * dy - bonus * (weights[t.ch] ?: 0f)
            if (cost < bestCost) { bestCost = cost; best = t }
        }
        return best
    }
}
