package de.schranner.tastatur

import kotlin.math.sqrt

/**
 * Wischen zum Schreiben: vergleicht die Fingerspur mit der „Ideal-Spur“ jedes passenden Worts
 * (Linie durch die Tastenmitten) und kombiniert das mit der Worthäufigkeit.
 * Läuft komplett auf dem Handy.
 */
class GestureDecoder(private val engine: SuggestionEngine) {

    class Pt(val x: Float, val y: Float)

    /**
     * @param path Fingerspur (Koordinaten der Tastatur)
     * @param centers Mitte jeder Buchstabentaste
     * @param keyW Breite einer Taste
     * @return beste Wörter (Kleinschreibung), bestes zuerst
     */
    fun decode(path: List<Pt>, centers: Map<Char, Pt>, keyW: Float, prev: String?): List<String> {
        if (path.size < 2 || centers.isEmpty()) return emptyList()
        val user = resample(path, N)
        val start = path.first()
        val end = path.last()
        val first = nearKeys(start, centers, keyW * 1.15f)
        val last = nearKeys(end, centers, keyW * 1.15f)
        if (first.isEmpty() || last.isEmpty()) return emptyList()
        val userLen = length(path)

        val scored = ArrayList<Pair<String, Float>>()
        for (w in engine.gestureCandidates(first, last)) {
            val pts = ArrayList<Pt>(w.length)
            var ok = true
            var prevC: Char? = null
            for (ch in w) {
                val k = keyFor(ch, centers)
                if (k == null) { ok = false; break }
                if (ch != prevC) pts += k
                prevC = ch
            }
            if (!ok || pts.isEmpty()) continue
            val wordLen = length(pts)
            // Grobe Vorauswahl über die Spurlänge
            if (wordLen > userLen * 2.2f + keyW || wordLen < userLen * 0.4f - keyW) continue
            val ideal = resample(pts, N)
            var sum = 0f
            for (i in 0 until N) {
                val dx = ideal[i].x - user[i].x
                val dy = ideal[i].y - user[i].y
                sum += sqrt(dx * dx + dy * dy)
            }
            val d = sum / N / keyW
            if (d > 1.3f) continue
            // Jeder Buchstabe muss nahe an der Spur liegen
            val miss = pts.maxOf { p -> minDist(p, user) } / keyW
            if (miss > 1.2f) continue
            // Anfang und Ende der Spur zählen besonders (dort setzt man bewusst auf)
            val ds = dist(pts.first(), start) / keyW
            val de = dist(pts.last(), end) / keyW
            val s = engine.rank(w, prev) * 0.55f - 9f * d * d - 3f * miss * miss - 2.5f * (ds * ds + de * de)
            scored += w to s
        }
        return scored.sortedByDescending { it.second }.take(5).map { it.first }
    }

    private fun keyFor(ch: Char, centers: Map<Char, Pt>): Pt? =
        centers[ch] ?: centers[SuggestionEngine.baseChar(ch)]

    private fun nearKeys(p: Pt, centers: Map<Char, Pt>, radius: Float): Set<Char> {
        val out = HashSet<Char>()
        for ((c, k) in centers) {
            val dx = k.x - p.x
            val dy = k.y - p.y
            if (sqrt(dx * dx + dy * dy) <= radius) out += c
        }
        if (out.isEmpty()) centers.minByOrNull { (_, k) -> (k.x - p.x) * (k.x - p.x) + (k.y - p.y) * (k.y - p.y) }?.let { out += it.key }
        // Umlaute und ß gehören zu denselben Wörterbuch-Buchstaben
        val extra = HashSet<Char>()
        for (c in out) when (c) {
            'a' -> extra += 'ä'; 'o' -> extra += 'ö'; 'u' -> extra += 'ü'; 's' -> extra += 'ß'
        }
        return out + extra
    }

    private fun dist(a: Pt, b: Pt): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun minDist(p: Pt, path: List<Pt>): Float {
        var best = Float.MAX_VALUE
        for (q in path) {
            val dx = q.x - p.x
            val dy = q.y - p.y
            val d = dx * dx + dy * dy
            if (d < best) best = d
        }
        return sqrt(best)
    }

    companion object {
        const val N = 40

        fun length(pts: List<Pt>): Float {
            var l = 0f
            for (i in 1 until pts.size) {
                val dx = pts[i].x - pts[i - 1].x
                val dy = pts[i].y - pts[i - 1].y
                l += sqrt(dx * dx + dy * dy)
            }
            return l
        }

        /** Spur in n gleichmäßig verteilte Punkte umrechnen. */
        fun resample(pts: List<Pt>, n: Int): List<Pt> {
            val total = length(pts)
            if (pts.size == 1 || total == 0f) return List(n) { pts[0] }
            val step = total / (n - 1)
            val out = ArrayList<Pt>(n)
            out += pts[0]
            var acc = 0f
            var i = 1
            var px = pts[0].x
            var py = pts[0].y
            while (i < pts.size && out.size < n) {
                val qx = pts[i].x
                val qy = pts[i].y
                val dx = qx - px
                val dy = qy - py
                val d = sqrt(dx * dx + dy * dy)
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    px += t * dx
                    py += t * dy
                    out += Pt(px, py)
                    acc = 0f
                } else {
                    acc += d
                    px = qx
                    py = qy
                    i++
                }
            }
            while (out.size < n) out += pts.last()
            return out
        }
    }
}
