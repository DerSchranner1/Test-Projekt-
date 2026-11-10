package de.schranner.tastatur

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Wortvorschläge komplett auf dem Handy, ohne Internet:
 * - Wörterbuch mit ~105.000 Wörtern (Deutsch + Englisch) samt Häufigkeit
 * - eigenes Wörterbuch, das mitlernt (auch Groß-/Kleinschreibung)
 * - Vorhersage des nächsten Worts aus dem, was du selbst schreibst
 * - Tippfehler-Erkennung, die die Tastatur-Nachbarschaft und Umlaute kennt
 */
class SuggestionEngine(
    private val openWords: () -> java.io.InputStream,
    dataDir: File,
) {
    constructor(context: Context) : this({ context.assets.open("words.tsv") }, context.filesDir)

    private class Entry(var word: String, var score: Float)

    @Volatile private var loaded = false
    private val dict = HashMap<String, Entry>(140_000)
    private var sorted: Array<String> = emptyArray()
    private val byFirst = HashMap<Char, MutableList<String>>()

    private val userWords = HashMap<String, Int>()      // lowercase -> Anzahl
    private val userCasing = HashMap<String, String>()  // lowercase -> bevorzugte Schreibweise
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private var dirty = false

    private val userFile = File(dataDir, "user_words.tsv")
    private val bigramFile = File(dataDir, "bigrams.tsv")

    fun loadAsync() {
        Thread {
            try { load() } catch (_: Throwable) { }
            loaded = true
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Synchron laden (für Tests). */
    fun loadBlocking() { load(); loaded = true }

    private fun load() {
        openWords().bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val w = line.substring(0, tab)
                val s = line.substring(tab + 1).toFloatOrNull() ?: continue
                val low = w.lowercase()
                val e = dict[low]
                if (e == null) dict[low] = Entry(w, s)
                else if (s > e.score) e.score = s
            }
        }
        if (userFile.exists()) userFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 2) {
                val low = p[0].lowercase()
                userWords[low] = p[1].toIntOrNull() ?: 1
                userCasing[low] = p[0]
            }
        }
        if (bigramFile.exists()) bigramFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 3) bigrams.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toIntOrNull() ?: 1
        }
        rebuildIndex()
    }

    @Synchronized
    private fun rebuildIndex() {
        val all = HashSet<String>(dict.keys)
        all.addAll(userWords.keys)
        sorted = all.toTypedArray().also { it.sort() }
        byFirst.clear()
        for (w in sorted) {
            val c = w.firstOrNull() ?: continue
            byFirst.getOrPut(baseChar(c)) { ArrayList() }.add(w)
        }
    }

    // ------------------------------------------------------------ Abfragen

    fun isReady() = loaded

    /** Ist das Wort bekannt (Wörterbuch oder selbst gelernt)? */
    @Synchronized
    fun isKnown(word: String): Boolean {
        if (!loaded) return true
        if (word.any { it.isDigit() }) return true
        val low = word.lowercase()
        if (dict.containsKey(low) || (userWords[low] ?: 0) >= LEARN_THRESHOLD) return true
        // Wortverbindungen mit Bindestrich: alle Teile prüfen
        if (low.contains('-')) return low.split('-').filter { it.isNotEmpty() }.all { isKnown(it) }
        // Zusammengesetzte Wörter (Haustür, Autobahnraststätte …)
        return isCompound(low)
    }

    private fun isCompound(low: String, depth: Int = 0): Boolean {
        if (low.length < 6 || depth > 2) return false
        for (i in 3..low.length - 3) {
            val head = low.substring(0, i)
            val tail = low.substring(i)
            val headOk = dict.containsKey(head) ||
                (head.endsWith("s") && head.length > 3 && dict.containsKey(head.dropLast(1)))
            if (!headOk) continue
            if (dict[tail]?.let { it.score > 380 } == true || isCompound(tail, depth + 1)) return true
        }
        return false
    }

    /** Vorschläge für das gerade getippte (unfertige) Wort. Beste zuerst. */
    @Synchronized
    fun suggest(typed: String, prev: String?): List<String> {
        if (!loaded || typed.isEmpty()) return emptyList()
        val low = typed.lowercase()
        val scores = HashMap<String, Float>()

        // 1) Wortvervollständigung (Präfix)
        synchronized(this) {
            var i = lowerBound(low)
            var n = 0
            while (i < sorted.size && n < 4000 && sorted[i].startsWith(low)) {
                val w = sorted[i]
                val extra = w.length - low.length
                scores[w] = score(w, prev) - extra * 0.35f - (if (extra > 0) 1.2f else 0f)
                i++; n++
            }
        }
        // 2) Tippfehler-Korrektur
        if (low.length >= 2) {
            for ((w, dist) in corrections(low)) {
                val s = score(w, prev) - dist * 4.5f
                if (s > (scores[w] ?: Float.NEGATIVE_INFINITY)) scores[w] = s
            }
        }
        // Das getippte Wort selbst etwas bevorzugen, wenn es bekannt ist
        if (isKnown(typed)) scores[low] = (scores[low] ?: score(low, prev)) + 2.5f

        return scores.entries.sortedByDescending { it.value }
            .take(6)
            .map { matchCase(display(it.key), typed) }
            .distinct()
    }

    /** Korrekturvorschläge für ein fertiges, unbekanntes Wort. */
    @Synchronized
    fun correctionsFor(word: String, prev: String?): List<String> {
        if (!loaded) return emptyList()
        return corrections(word.lowercase())
            .map { (w, d) -> w to score(w, prev) - d * 4.5f }
            .sortedByDescending { it.second }
            .take(3)
            .map { matchCase(display(it.first), word) }
    }

    /** Nächstes Wort vorhersagen. */
    @Synchronized
    fun predictNext(prev: String?, sentenceStart: Boolean): List<String> {
        if (!loaded) return emptyList()
        val out = mutableListOf<String>()
        if (prev != null) {
            bigrams[prev.lowercase()]?.entries?.sortedByDescending { it.value }?.take(3)?.forEach {
                out += display(it.key)
            }
        }
        val defaults = if (sentenceStart) listOf("Ich", "Hallo", "Danke") else listOf("und", "ich", "die")
        for (d in defaults) if (out.size < 3 && out.none { it.equals(d, true) }) out += d
        return out.map { if (sentenceStart) it.replaceFirstChar { c -> c.uppercase() } else it }
    }

    // ------------------------------------------------------------ Lernen

    /** Wort wurde fertig geschrieben – merken (für Vorhersage und eigenes Wörterbuch). */
    @Synchronized
    fun learn(word: String, prev: String?, sentenceStart: Boolean) {
        if (!loaded || word.length < 2 || word.length > 40) return
        if (word.any { it.isDigit() }) return
        val low = word.lowercase()
        if (!dict.containsKey(low)) {
            val c = (userWords[low] ?: 0) + 1
            userWords[low] = c
            if (!sentenceStart || userCasing[low] == null) userCasing[low] = word
            if (c == LEARN_THRESHOLD) synchronized(this) { rebuildIndex() }
        } else if (!sentenceStart) {
            // Eigene Schreibweise bekannter Wörter merken (z. B. Firmennamen in Großbuchstaben)
            if (word != dict[low]!!.word && word != word.lowercase()) userCasing[low] = word
        }
        if (prev != null && prev.length < 40) {
            val m = bigrams.getOrPut(prev.lowercase()) { HashMap() }
            m[low] = (m[low] ?: 0) + 1
        }
        dirty = true
    }

    /** Wort dauerhaft ins eigene Wörterbuch aufnehmen. */
    @Synchronized
    fun addWord(word: String) {
        val low = word.lowercase()
        userWords[low] = maxOf(userWords[low] ?: 0, LEARN_THRESHOLD + 5)
        userCasing[low] = word
        dirty = true
        synchronized(this) { rebuildIndex() }
        save()
    }

    /** Ein gelerntes Wort wurde doch korrigiert – Zähler wieder senken. */
    @Synchronized
    fun forget(word: String) {
        val low = word.lowercase()
        val c = userWords[low] ?: return
        if (c <= 1) { userWords.remove(low); userCasing.remove(low) } else userWords[low] = c - 1
        dirty = true
    }

    /** Schreibweise laut Wörterbuch (z. B. „Haus“), oder null wenn unbekannt. */
    @Synchronized
    fun dictionaryForm(word: String): String? {
        val low = word.lowercase()
        return userCasing[low] ?: dict[low]?.word
    }

    @Synchronized
    fun userWordList(): List<String> =
        userWords.filter { it.value >= LEARN_THRESHOLD }.keys.map { userCasing[it] ?: it }.sorted()

    /** Die häufigsten eigenen Wörter – helfen der Spracherkennung bei Namen und Fachbegriffen. */
    @Synchronized
    fun topUserWords(n: Int): List<String> =
        userWords.entries.filter { it.value >= LEARN_THRESHOLD }.sortedByDescending { it.value }
            .take(n).map { userCasing[it.key] ?: it.key }

    @Synchronized
    fun clearUserData() {
        userWords.clear(); userCasing.clear(); bigrams.clear()
        userFile.delete(); bigramFile.delete()
        synchronized(this) { rebuildIndex() }
    }

    fun save() {
        if (!dirty || !loaded) return
        dirty = false
        val words = HashMap(userWords)
        val casing = HashMap(userCasing)
        val bi = bigrams.mapValues { HashMap(it.value) }
        Thread {
            try {
                userFile.bufferedWriter().use { w ->
                    for ((k, v) in words) w.write("${casing[k] ?: k}\t$v\n")
                }
                bigramFile.bufferedWriter().use { w ->
                    for ((a, m) in bi) for ((b, c) in m) w.write("$a\t$b\t$c\n")
                }
            } catch (_: Exception) { }
        }.start()
    }

    // ------------------------------------------------------------ Intern

    private fun score(low: String, prev: String?): Float {
        var s = dict[low]?.score?.let { it / 60f } ?: 0f
        val u = userWords[low] ?: 0
        if (u > 0) s = maxOf(s, 6f) + ln(1f + u) * 1.5f
        if (prev != null) {
            val b = bigrams[prev.lowercase()]?.get(low) ?: 0
            if (b > 0) s += 3f + ln(1f + b) * 2f
        }
        return s
    }

    private fun display(low: String): String = userCasing[low] ?: dict[low]?.word ?: low

    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Ähnliche Wörter mit gewichteter Editierdistanz (max. 2). */
    private fun corrections(low: String): List<Pair<String, Float>> {
        val first = low.firstOrNull() ?: return emptyList()
        val firstChars = HashSet<Char>()
        firstChars += baseChar(first)
        NEIGHBORS[first]?.forEach { firstChars += baseChar(it) }
        val maxDist = if (low.length <= 3) 1.0f else if (low.length <= 5) 1.6f else 2.1f
        val out = ArrayList<Pair<String, Float>>()
        synchronized(this) {
            for (fc in firstChars) {
                val list = byFirst[fc] ?: continue
                for (w in list) {
                    if (abs(w.length - low.length) > 2) continue
                    if (w == low) continue
                    val d = distance(low, w, maxDist)
                    if (d <= maxDist) out += w to d
                }
            }
        }
        return out
    }

    private fun subCost(a: Char, b: Char): Float {
        if (a == b) return 0f
        if (baseChar(a) == baseChar(b)) return 0.25f
        if (NEIGHBORS[a]?.contains(b) == true) return 0.6f
        if (SOUND_ALIKE.contains("$a$b") || SOUND_ALIKE.contains("$b$a")) return 0.5f
        return 1f
    }

    /** Damerau-Levenshtein mit Kosten für Nachbartasten und Umlaute; bricht früh ab. */
    private fun distance(a: String, b: String, max: Float): Float {
        val n = a.length
        val m = b.length
        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1) { it.toFloat() }
        var cur = FloatArray(m + 1)
        for (i in 1..n) {
            cur[0] = i.toFloat()
            var rowMin = cur[0]
            for (j in 1..m) {
                var v = min(min(prev[j] + 1f, cur[j - 1] + 1f), prev[j - 1] + subCost(a[i - 1], b[j - 1]))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, prev2[j - 2] + 0.7f)
                // ss <-> ß, ae <-> ä usw.
                if (i > 1 && j >= 1 && isDigraph(a[i - 2], a[i - 1], b[j - 1])) v = min(v, prev2[j - 1] + 0.3f)
                if (j > 1 && i >= 1 && isDigraph(b[j - 2], b[j - 1], a[i - 1])) v = min(v, prev[j - 2] + 0.3f)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Float.MAX_VALUE
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    private fun isDigraph(x: Char, y: Char, single: Char): Boolean = when (single) {
        'ß' -> x == 's' && y == 's'
        'ä' -> x == 'a' && y == 'e'
        'ö' -> x == 'o' && y == 'e'
        'ü' -> x == 'u' && y == 'e'
        else -> false
    }

    companion object {
        const val LEARN_THRESHOLD = 2

        /** Buchstaben, die gleich klingen und oft verwechselt werden. */
        private val SOUND_ALIKE = setOf("dt", "bp", "gk", "fv", "sz", "ck", "xs", "yi", "ei")

        fun baseChar(c: Char): Char = when (c) {
            'ä', 'á', 'à', 'â' -> 'a'
            'ö', 'ó', 'ò', 'ô' -> 'o'
            'ü', 'ú', 'ù', 'û' -> 'u'
            'é', 'è', 'ê', 'ë' -> 'e'
            'ß' -> 's'
            else -> c
        }

        /** Nachbartasten anhand der echten Tastenpositionen (QWERTZ). */
        private val NEIGHBORS: Map<Char, String> = run {
            val pos = HashMap<Char, Pair<Float, Float>>()
            "qwertzuiopü".forEachIndexed { i, c -> pos[c] = (i + 0.5f) to 0f }
            "asdfghjklöä".forEachIndexed { i, c -> pos[c] = (i + 0.5f) to 1f }
            "yxcvbnmß".forEachIndexed { i, c -> pos[c] = (i + 2f) to 2f }
            val m = HashMap<Char, String>()
            for ((c, p) in pos) {
                m[c] = pos.filter { (o, q) ->
                    o != c && (p.first - q.first).let { it * it } + (p.second - q.second).let { it * it } <= 2.1f
                }.keys.joinToString("")
            }
            m
        }
    }
}
