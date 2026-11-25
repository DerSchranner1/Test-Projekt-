package de.schranner.tastatur

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Wortvorschläge komplett auf dem Handy, ohne Internet:
 * - Wörterbuch mit ~115.000 Wörtern (Deutsch + Englisch) samt Häufigkeit je Sprache
 * - Deutsch und Englisch gleichzeitig: die letzten Wörter bestimmen, welche Sprache gerade zählt
 * - eigenes Wörterbuch, das mitlernt (auch Groß-/Kleinschreibung)
 * - Vorhersage des nächsten Worts: mitgelieferte Wortfolgen (aus ~2 Mio. deutschen Sätzen)
 *   plus das, was du selbst schreibst
 * - Tippfehler-Erkennung, die die Tastatur-Nachbarschaft und Umlaute kennt
 */
class SuggestionEngine(
    private val openWords: () -> java.io.InputStream,
    dataDir: File,
    private val openNgrams: (() -> java.io.InputStream)? = null,
    private val openNgramsEn: (() -> java.io.InputStream)? = null,
    /** Öffnet weitere Daten nach Namen: „valid.bloom“ (gültige Wortformen), „grammar_de.tsv“ (Verben). */
    private val openExtra: ((String) -> java.io.InputStream)? = null,
    /** Bairisch mitverstehen („i hob“, „des is ned“, „griaß di“): eigene Wörter und Wortfolgen. */
    val dialect: Boolean = false,
) {
    constructor(context: Context, dialect: Boolean = false) : this(
        { context.assets.open("words.tsv") }, context.filesDir,
        { context.assets.open("ngrams.tsv") }, { context.assets.open("ngrams_en.tsv") },
        { name -> context.assets.open(name) },
        dialect,
    )

    /**
     * Aus dem Geschriebenen lernen? Aus, wenn der Nutzer es abgeschaltet hat oder die App um
     * „nicht lernen“ bittet (z. B. Inkognito-Fenster im Browser).
     */
    @Volatile var learning = true

    /** Alle gültigen Wortformen – damit auch seltene richtige Wörter nicht als Fehler gelten. */
    @Volatile private var valid: BloomFilter? = null
    /** Verbreitete Falschschreibungen („vieleicht“): immer markieren, nie vorschlagen. */
    private val knownBad = HashSet<String>()
    @Volatile private var infinitives: Set<String> = emptySet()
    @Volatile private var finite: Set<String> = emptySet()

    /**
     * @param scoreDe/scoreEn Häufigkeit je Sprache: 900 + 100·log10(Anteil), 0 = kommt nicht vor
     * @param wordEn englische Schreibweise, falls anders („Art“ -> „art“, „i“ -> „I“)
     */
    private class Entry(val low: String, var word: String, val scoreDe: Float, val scoreEn: Float, val wordEn: String?)

    /** Folgewörter eines Kontexts („ich“ oder „ich habe“) mit Häufigkeiten. */
    private class Followers(
        val total: Int,
        val words: Array<String>,
        val counts: IntArray,          // absteigend sortiert
        val cased: Array<String?>,     // Schreibweise in diesem Zusammenhang, falls abweichend („Dank“)
    ) {
        fun index(w: String): Int {
            for (i in words.indices) if (words[i] == w) return i
            return -1
        }
        fun count(w: String): Int = index(w).let { if (it < 0) 0 else counts[it] }
    }

    /** Aufgelöster Kontext einer Abfrage (einmal pro Abfrage statt pro Kandidat). */
    private class Ctx(
        val user: Map<String, Int>?,
        val bi: Followers?, val tri: Followers?,        // deutsche Wortfolgen
        val biEn: Followers?, val triEn: Followers?,    // englische Wortfolgen
        val en: Float,                                  // Anteil Englisch (0..1)
        val nominal: Boolean = false,                   // nach „beim“, „zum“ …: ein Verb wird hier zum Hauptwort
        val article: Boolean = false,                   // nach „das“, „ein“ …: könnte ein Hauptwort sein
    ) {
        val hasStatic get() = bi != null || biEn != null
    }

    private fun ctx(prev: String?, prev2: String?, sentenceStart: Boolean = false): Ctx {
        val en = english
        val p1 = (if (sentenceStart) START else prev?.lowercase()) ?: return Ctx(null, null, null, null, null, en)
        val p2 = if (sentenceStart) null else prev2?.lowercase()
        val de = ngrams
        val eng = ngramsEn
        val key3 = if (p2 != null) "$p2 $p1" else null
        return Ctx(
            bigrams[p1], de[p1], key3?.let { de[it] }, eng[p1], key3?.let { eng[it] }, en,
            nominal = en < 0.5f && p1 in NOMINAL_CONTEXT, article = en < 0.5f && p1 in ARTICLES,
        )
    }

    // ------------------------------------------------------------ Hauptwörter aus Verben

    /**
     * Sieht das Wort wie ein Verb in der Grundform aus („machen“, „anrufen“, „unterkringeln“)?
     * Bekannte Verben stehen in einer Liste; unbekannte Wörter auf -eln/-ern zählen ebenfalls.
     */
    private fun isVerbLike(low: String): Boolean {
        if (low.length < 4 || low in NOT_NOMINAL) return false
        val inf = infinitives
        if (low in inf) return true
        for (pre in VERB_PREFIXES) {
            if (low.length > pre.length + 3 && low.startsWith(pre) && low.substring(pre.length) in inf) return true
        }
        val known = dict[low]
        return (known == null || known.scoreDe <= 0f) && low.length >= 6 && (low.endsWith("eln") || low.endsWith("ern"))
    }

    /**
     * Grammatik-Hinweis im Nachhinein: „Das unterkringeln funktioniert“ – folgt auf „das/ein/mein … + Verb“
     * ein gebeugtes Verb, war das erste Verb ein Hauptwort und gehört groß.
     * @return die richtige Schreibweise von [prev], oder null
     */
    @Synchronized
    fun nounFromVerb(prev2: String?, prev: String?, word: String): String? {
        if (!loaded || prev == null || prev2 == null) return null
        if (english > 0.5f || !prev[0].isLowerCase()) return null
        if (prev2.lowercase() !in ARTICLES) return null
        if (word.lowercase() !in finite || !isVerbLike(prev.lowercase())) return null
        return prev.replaceFirstChar { it.uppercase() }
    }


    // ------------------------------------------------------------ Sprache (Deutsch/Englisch)

    /** Geschätzter Anteil Englisch im aktuellen Text. */
    @Volatile private var english = PRIOR_EN

    /**
     * Die letzten Wörter vor dem Cursor mitteilen (jüngstes zuletzt). Daraus wird geschätzt,
     * ob gerade Deutsch oder Englisch geschrieben wird.
     */
    fun setRecentWords(words: List<String>) {
        if (!loaded) return
        var odds = ln(PRIOR_EN / (1f - PRIOR_EN))
        var weight = 1f
        for (w in words.asReversed().take(6)) {
            val e = dict[w.lowercase()]
            if (e != null) {
                val de = if (e.scoreDe > 0f) e.scoreDe else FLOOR_SCORE
                val en = if (e.scoreEn > 0f) e.scoreEn else FLOOR_SCORE
                odds += weight * ((en - de) / 100f * LN10).coerceIn(-3f, 3f)
            }
            weight *= 0.8f
        }
        english = (1f / (1f + kotlin.math.exp(-odds))).coerceIn(0.01f, 0.97f)
    }

    /** Für Tests und Anzeige: geschätzter Anteil Englisch (0..1). */
    fun englishShare(): Float = english

    /** Grundhäufigkeit eines Worts als Mischung beider Sprachen (gleiche Skala wie die Einzelwerte). */
    private fun baseScore(e: Entry, en: Float): Float {
        val de = e.scoreDe
        val es = e.scoreEn
        return when {
            es <= 0f -> de + 100f * log10(1f - en)
            de <= 0f -> es + 100f * log10(en)
            else -> 900f + 100f * log10(
                (1f - en) * 10.0.pow((de - 900.0) / 100.0).toFloat() + en * 10.0.pow((es - 900.0) / 100.0).toFloat(),
            )
        }
    }

    /** Mitgelieferte Wortfolgen. Schlüssel: „wort“ oder „wort1 wort2“, Satzanfang = „<s>“. */
    @Volatile private var ngrams: Map<String, Followers> = emptyMap()
    @Volatile private var ngramsEn: Map<String, Followers> = emptyMap()

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
            // Wortfolgen danach laden – Tippen funktioniert schon vorher
            try { loadNgrams() } catch (_: Throwable) { fullyLoaded = true }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Synchron laden (für Tests). */
    fun loadBlocking() { load(); loaded = true; loadNgrams() }

    private fun loadNgrams() {
        openNgrams?.let { ngrams = readNgrams(it, 180_000) }
        if (dialect) openExtra?.let { open ->
            try { ngrams = mergeDialect(ngrams, readNgrams({ open("ngrams_bar.tsv") }, 10_000)) } catch (_: Exception) { }
        }
        openNgramsEn?.let { ngramsEn = readNgrams(it, 100_000) }
        fullyLoaded = true
    }

    @Volatile private var fullyLoaded = false

    /** Wörterbuch UND Wortfolgen geladen? */
    fun isFullyLoaded() = fullyLoaded

    /**
     * Bairische Wortfolgen zu den hochdeutschen dazunehmen. Wo es beide gibt („des“, „i“),
     * bekommen die bairischen Folgewörter ein Drittel des Gewichts.
     */
    private fun mergeDialect(base: Map<String, Followers>, bar: Map<String, Followers>): Map<String, Followers> {
        val out = HashMap(base)
        for ((key, b) in bar) {
            val d = base[key]
            if (d == null) { out[key] = b; continue }
            val scale = d.total * DIALECT_SHARE / b.total
            val sum = LinkedHashMap<String, Pair<Int, String?>>()
            for (i in d.words.indices) sum[d.words[i]] = d.counts[i] to d.cased[i]
            for (i in b.words.indices) {
                val add = maxOf(1, (b.counts[i] * scale).toInt())
                val old = sum[b.words[i]]
                sum[b.words[i]] = ((old?.first ?: 0) + add) to (old?.second ?: b.cased[i])
            }
            val sorted = sum.entries.sortedByDescending { it.value.first }
            out[key] = Followers(
                d.total + (b.total * scale).toInt(),
                Array(sorted.size) { sorted[it].key },
                IntArray(sorted.size) { sorted[it].value.first },
                Array(sorted.size) { sorted[it].value.second },
            )
        }
        return out
    }

    private fun readNgrams(open: () -> java.io.InputStream, capacity: Int): Map<String, Followers> {
        val map = HashMap<String, Followers>(capacity)
        open().bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val t1 = line.indexOf('\t')
                val t2 = line.indexOf('\t', t1 + 1)
                if (t1 <= 0 || t2 <= 0) continue
                val total = line.substring(t1 + 1, t2).toIntOrNull() ?: continue
                val parts = line.substring(t2 + 1).split(' ')
                val words = ArrayList<String>(parts.size)
                val cased = ArrayList<String?>(parts.size)
                val counts = IntArray(parts.size)
                for (p in parts) {
                    val c = p.lastIndexOf(':')
                    if (c <= 0) continue
                    val raw = p.substring(0, c)
                    val low = raw.lowercase()
                    val e = dict[low]
                    counts[words.size] = p.substring(c + 1).toIntOrNull() ?: continue
                    words += e?.low ?: low   // dieselbe String-Instanz wie im Wörterbuch (spart Speicher)
                    cased += if (e != null && e.word != raw) raw else null
                }
                map[line.substring(0, t1)] =
                    Followers(total, words.toTypedArray(), counts.copyOf(words.size), cased.toTypedArray())
            }
        }
        return map
    }

    private fun load() {
        openWords().bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                // Wort <TAB> Häufigkeit Deutsch <TAB> Häufigkeit Englisch [<TAB> englische Schreibweise]
                val p = line.split('\t')
                if (p.size < 2) continue
                val w = p[0]
                val de = p[1].toFloatOrNull() ?: continue
                val en = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                val low = w.lowercase()
                if (de < 0f) { knownBad += low; continue }
                if (!dict.containsKey(low)) dict[low] = Entry(low, w, de, en, p.getOrNull(3))
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
        if (dialect) openExtra?.let { open ->
            try {
                open("bairisch.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val t = line.indexOf('\t')
                        if (t <= 0) continue
                        val w = line.substring(0, t)
                        val score = line.substring(t + 1).toFloatOrNull() ?: continue
                        val low = w.lowercase()
                        knownBad.remove(low)
                        val e = dict[low]
                        when {
                            e == null -> dict[low] = Entry(low, w, score, 0f, null)
                            // Bairisch häufiger als im Hochdeutschen („ned“, „des“, „i“): bairische Schreibweise
                            e.scoreDe < score -> dict[low] = Entry(e.low, w, score, e.scoreEn, e.wordEn)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        rebuildIndex()
        computeFirstLetters()
        openExtra?.let { open ->
            try { valid = BloomFilter.read(open("valid.bloom")) } catch (_: Exception) { }
            try {
                open("grammar_de.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val t = line.indexOf('\t')
                        if (t < 0) continue
                        val set = HashSet(line.substring(t + 1).split(' '))
                        if (line.startsWith("I")) infinitives = set else if (line.startsWith("F")) finite = set
                    }
                }
            } catch (_: Exception) { }
        }
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
        if ((userWords[low] ?: 0) >= LEARN_THRESHOLD) return true
        if (low in knownBad) return false
        if (dict.containsKey(low)) return true
        if (low.length > 1 && valid?.contains(low.replace('’', '\'')) == true) return true
        // Wortverbindungen mit Bindestrich: alle Teile prüfen
        if (low.contains('-')) return low.split('-').filter { it.isNotEmpty() }.all { isKnown(it) }
        // Zusammengesetzte Wörter (Haustür, Autobahnraststätte …)
        return isCompound(low)
    }

    private fun isGerman(low: String) = dict[low]?.let { it.scoreDe > 0f } == true

    private fun isCompound(low: String, depth: Int = 0): Boolean {
        if (low.length < 6 || depth > 2) return false
        for (i in 3..low.length - 3) {
            val head = low.substring(0, i)
            val tail = low.substring(i)
            val headOk = isGerman(head) || (head.endsWith("s") && head.length > 3 && isGerman(head.dropLast(1)))
            if (!headOk) continue
            if (dict[tail]?.let { it.scoreDe > 380 } == true || isCompound(tail, depth + 1)) return true
        }
        return false
    }

    /** Vorschläge für das gerade getippte (unfertige) Wort. Beste zuerst. */
    @Synchronized
    fun suggest(typed: String, prev: String?, prev2: String? = null, sentenceStart: Boolean = false): List<String> {
        if (!loaded || typed.isEmpty()) return emptyList()
        val low = typed.lowercase()
        val scores = HashMap<String, Float>()
        val c = ctx(prev, prev2, sentenceStart)

        // 1) Wortvervollständigung (Präfix)
        synchronized(this) {
            // Alle Wörter mit diesem Anfang (ohne Obergrenze – sonst fehlen z. B. bei „f“ die
            // Wörter am Ende des Alphabets wie „für“)
            var i = lowerBound(low)
            while (i < sorted.size && sorted[i].startsWith(low)) {
                val w = sorted[i]
                val extra = w.length - low.length
                scores[w] = score(w, c) - extra * 0.35f - (if (extra > 0) 1.2f else 0f)
                i++
            }
        }
        // 2) Tippfehler-Korrektur
        if (low.length >= 2) {
            for ((w, dist) in corrections(low)) {
                val s = score(w, c) - dist * 4.5f
                if (s > (scores[w] ?: Float.NEGATIVE_INFINITY)) scores[w] = s
            }
        }
        // Das getippte Wort selbst etwas bevorzugen, wenn es bekannt ist
        if (isKnown(typed)) scores[low] = (scores[low] ?: score(low, c)) + 2.5f

        val out = ArrayList<String>(8)
        for (e in scores.entries.sortedByDescending { it.value }.take(6)) {
            val shown = matchCase(display(e.key, c), typed)
            out += shown
            // Nach „das“, „ein“, „mein“ … kann ein Verb auch ein Hauptwort sein: beide Schreibweisen anbieten
            if (c.article && shown[0].isLowerCase() && isVerbLike(e.key)) out += shown.replaceFirstChar { it.uppercase() }
        }
        return out.distinct()
    }

    /** Korrekturvorschläge für ein fertiges, unbekanntes Wort. */
    @Synchronized
    fun correctionsFor(word: String, prev: String?): List<String> {
        if (!loaded) return emptyList()
        val c = ctx(prev, null)
        val out = ArrayList<String>(4)
        for ((w, _) in corrections(word.lowercase()).map { (w, d) -> w to score(w, c) - d * 4.5f }
            .sortedByDescending { it.second }.take(3)) {
            val shown = matchCase(display(w, c), word)
            if (c.article && shown[0].isLowerCase() && isVerbLike(w)) out += shown.replaceFirstChar { it.uppercase() }
            out += shown
        }
        return out.distinct().take(3)
    }

    /**
     * Nächstes Wort vorhersagen.
     * @param prev letztes Wort, prev2 das Wort davor („<s>“ wenn prev am Satzanfang steht)
     */
    @Synchronized
    fun predictNext(prev: String?, sentenceStart: Boolean, prev2: String? = null, n: Int = 3): List<String> {
        if (!loaded) return emptyList()
        val c = ctx(prev, prev2, sentenceStart)
        val userTotal = c.user?.values?.sum() ?: 0
        val userWeight = if (userTotal > 0) min(0.6f, userTotal / (userTotal + 10f)) else 0f

        val cand = HashSet<String>()
        c.bi?.words?.let { cand.addAll(it) }
        c.tri?.words?.let { cand.addAll(it) }
        c.biEn?.words?.let { cand.addAll(it) }
        c.triEn?.words?.let { cand.addAll(it) }
        c.user?.keys?.let { cand.addAll(it) }

        val ranked = cand.map { w ->
            val pu = if (userTotal > 0) (c.user!![w] ?: 0) / userTotal.toFloat() else 0f
            w to (1 - userWeight) * staticProb(w, c) + userWeight * pu
        }.sortedByDescending { it.second }

        val out = ArrayList<String>(n)
        for ((w, _) in ranked) {
            if (out.size >= n) break
            out += display(w, c)
        }
        val defaults = when {
            c.en > 0.5f -> if (sentenceStart) listOf("I", "The", "It") else listOf("and", "the", "to")
            sentenceStart -> listOf("Ich", "Das", "Hallo")
            else -> listOf("und", "ich", "die")
        }
        for (d in defaults) if (out.size < n && out.none { it.equals(d, true) }) out += d
        return out.map { if (sentenceStart) it.replaceFirstChar { ch -> ch.uppercase() } else it }
    }

    /** P(Wort | Kontext) aus den mitgelieferten Wortfolgen beider Sprachen; 0 wenn die Folge nicht vorkommt. */
    private fun staticProb(low: String, c: Ctx): Float {
        val de = if (c.bi != null) probIn(low, c.bi, c.tri) else 0f
        val en = if (c.biEn != null) probIn(low, c.biEn, c.triEn) else 0f
        return (1f - c.en) * de + c.en * en
    }

    private fun probIn(low: String, bi: Followers, tri: Followers?): Float {
        val p2 = bi.count(low) / bi.total.toFloat()
        if (tri == null) return p2
        val c3 = tri.count(low)
        if (c3 > 0) return 0.8f * c3 / tri.total + 0.2f * p2
        // Nicht unter den häufigsten Folgewörtern des längeren Kontexts:
        // höchstens so wahrscheinlich wie das seltenste dort gelistete Wort.
        val bound = tri.counts[tri.counts.size - 1] / tri.total.toFloat()
        return min(0.4f * p2, 0.8f * bound)
    }

    /**
     * Wie wahrscheinlich ist jeder Buchstabe als NÄCHSTER Buchstabe? Für die unsichtbare Treffhilfe
     * (wahrscheinliche Tasten bekommen eine größere Trefferfläche).
     * @return Buchstabe -> Anteil (Summe 1), leer wenn nichts bekannt ist
     */
    @Synchronized
    fun nextLetters(prefix: String, prev: String?, prev2: String?, sentenceStart: Boolean): Map<Char, Float> {
        if (!loaded) return emptyMap()
        val low = prefix.lowercase()
        val c = ctx(prev, prev2, sentenceStart)
        val sums = HashMap<Char, Double>()
        if (low.isEmpty()) {
            // Wortanfang: Folgewörter des Kontexts + allgemeine Verteilung der Anfangsbuchstaben
            var listed = 0.0
            val seen = HashSet<String>()
            for (f in listOf(c.tri, c.bi, c.triEn, c.biEn)) {
                f ?: continue
                for (w in f.words) {
                    if (!seen.add(w) || w.isEmpty()) continue
                    val p = staticProb(w, c).toDouble()
                    sums[w[0]] = (sums[w[0]] ?: 0.0) + p
                    listed += p
                }
            }
            val rest = (1.0 - listed).coerceIn(0.15, 1.0)
            for ((ch, p) in firstLetters) {
                val mixed = (1f - c.en) * p.first + c.en * p.second
                sums[ch] = (sums[ch] ?: 0.0) + rest * mixed
            }
        } else {
            var i = lowerBound(low)
            while (i < sorted.size && sorted[i].startsWith(low)) {
                val w = sorted[i++]
                if (w.length <= low.length) continue
                val ch = w[low.length]
                if (!ch.isLetter()) continue
                sums[ch] = (sums[ch] ?: 0.0) + 10.0.pow(score(w, c) / 1.667)
            }
        }
        val total = sums.values.sum()
        if (total <= 0.0) return emptyMap()
        return sums.mapValues { (it.value / total).toFloat() }
    }

    /** Anfangsbuchstabe -> (Anteil Deutsch, Anteil Englisch), einmal beim Laden berechnet. */
    private var firstLetters: Map<Char, Pair<Float, Float>> = emptyMap()

    private fun computeFirstLetters() {
        val de = HashMap<Char, Double>()
        val en = HashMap<Char, Double>()
        for (e in dict.values) {
            val ch = e.low.firstOrNull() ?: continue
            if (!ch.isLetter()) continue
            if (e.scoreDe > 0f) de[ch] = (de[ch] ?: 0.0) + 10.0.pow((e.scoreDe - 900.0) / 100.0)
            if (e.scoreEn > 0f) en[ch] = (en[ch] ?: 0.0) + 10.0.pow((e.scoreEn - 900.0) / 100.0)
        }
        val td = de.values.sum().coerceAtLeast(1e-9)
        val te = en.values.sum().coerceAtLeast(1e-9)
        firstLetters = (de.keys + en.keys).associateWith { ch ->
            ((de[ch] ?: 0.0) / td).toFloat() to ((en[ch] ?: 0.0) / te).toFloat()
        }
    }

    // ------------------------------------------------------------ Lernen

    /** Wort wurde fertig geschrieben – merken (für Vorhersage und eigenes Wörterbuch). */
    @Synchronized
    fun learn(word: String, prev: String?, sentenceStart: Boolean) {
        if (!learning) return
        if (!loaded || word.length < 2 || word.length > 40) return
        if (word.any { it.isDigit() }) return
        val low = word.lowercase()
        if (!dict.containsKey(low)) {
            // Von selbst gelernt wird nur, was nicht nach Tippfehler aussieht: keine bekannte
            // Falschschreibung und kein Wort, das fast wie ein bekanntes aussieht. Solche Wörter
            // nimmt man bewusst mit „+“ auf – sonst würde zweimal Vertippen den Fehler „lernen“.
            val alreadyMine = (userWords[low] ?: 0) >= LEARN_THRESHOLD
            if (!alreadyMine && (low in knownBad || corrections(low).isNotEmpty())) {
                learnFollower(low, prev, sentenceStart)
                return
            }
            val c = (userWords[low] ?: 0) + 1
            userWords[low] = c
            if (!sentenceStart || userCasing[low] == null) userCasing[low] = word
            if (c == LEARN_THRESHOLD) synchronized(this) { rebuildIndex() }
        } else if (!sentenceStart) {
            // Eigene Schreibweise bekannter Wörter merken (z. B. Firmennamen in Großbuchstaben)
            val e = dict[low]!!
            if (word != e.word && word != e.wordEn && word != word.lowercase()) userCasing[low] = word
        }
        learnFollower(low, prev, sentenceStart)
    }

    /** Merken, welches Wort auf welches folgt (für die Vorhersage des nächsten Worts). */
    private fun learnFollower(low: String, prev: String?, sentenceStart: Boolean) {
        val key = if (sentenceStart) START else prev?.lowercase()
        // Unbekannte, nicht gelernte Wörter (mögliche Tippfehler) nicht als Folgewort vorschlagen
        if (key != null && key.length < 40 && (dict.containsKey(low) || (userWords[low] ?: 0) >= LEARN_THRESHOLD)) {
            val m = bigrams.getOrPut(key) { HashMap() }
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

    /**
     * Echte Autokorrektur (nur wenn in den Einstellungen eingeschaltet): liefert eine Korrektur
     * nur, wenn sie eindeutig ist – sonst null.
     */
    @Synchronized
    fun autocorrectFor(word: String, prev: String?, prev2: String? = null): String? {
        if (!loaded || word.length < 2 || isKnown(word)) return null
        if (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }) return null
        val low = word.lowercase()
        val c = ctx(prev, prev2)
        val ranked = corrections(low)
            .map { (w, d) -> Triple(w, d, score(w, c) - d * 4.5f) }
            .sortedByDescending { it.third }
        val best = ranked.firstOrNull() ?: return null
        val maxD = if (low.length <= 4) 0.7f else 1.3f
        if (best.second > maxD) return null
        val second = ranked.getOrNull(1)
        if (second != null && best.third - second.third < 1.0f) return null
        return matchCase(display(best.first, c), word)
    }

    /** Kandidaten fürs Wischen: Anfangs- und Endbuchstabe passen. Liefert Kleinschreibung. */
    @Synchronized
    fun gestureCandidates(first: Set<Char>, last: Set<Char>): List<String> {
        if (!loaded) return emptyList()
        val out = ArrayList<String>()
        val buckets = first.map { baseChar(it) }.toSet()
        for (b in buckets) {
            val list = byFirst[b] ?: continue
            for (w in list) {
                if (w.length < 2 || w.length > 24) continue
                if (w[0] !in first || w[w.length - 1] !in last) continue
                if (!dict.containsKey(w) && (userWords[w] ?: 0) < LEARN_THRESHOLD) continue
                out += w
            }
        }
        return out
    }

    /** Häufigkeits-/Kontext-Punktzahl eines Worts (Kleinschreibung). */
    @Synchronized
    fun rank(low: String, prev: String?, prev2: String? = null): Float = score(low, ctx(prev, prev2))

    /** Wie [rank], aber für viele Wörter mit demselben Kontext (Wischen). */
    @Synchronized
    fun rankAll(words: List<String>, prev: String?, prev2: String? = null): FloatArray {
        val c = ctx(prev, prev2)
        return FloatArray(words.size) { score(words[it], c) }
    }

    @Synchronized
    fun displayForm(low: String, prev: String? = null, prev2: String? = null): String =
        display(low, ctx(prev, prev2))

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

    /**
     * Punktzahl eines Worts: Häufigkeit + eigenes Schreibverhalten + wie gut es zum Kontext passt.
     * Skala: 1,67 Punkte = Faktor 10 in der Wahrscheinlichkeit.
     */
    private fun score(low: String, c: Ctx): Float {
        val base = dict[low]?.let { baseScore(it, c.en) }
        var s = base?.let { it / 60f } ?: 0f
        val u = userWords[low] ?: 0
        if (u > 0) s = maxOf(s, 6f) + ln(1f + u) * 1.5f
        val b = c.user?.get(low) ?: 0
        if (b > 0) s += 3f + ln(1f + b) * 2f
        if (c.hasStatic) {
            val p = staticProb(low, c)
            s += if (p > 0f && base != null) {
                val prior = 10.0.pow((base - 900.0) / 100.0).toFloat()
                (CONTEXT_WEIGHT * log10(p / prior)).coerceIn(-1f, 8f)
            } else CONTEXT_MISS
        }
        return s
    }

    /** Schreibweise: eigene Vorliebe > Schreibweise im Zusammenhang („Vielen Dank“) > Wörterbuch. */
    private fun display(low: String, c: Ctx): String {
        val english = c.en > 0.5f
        val first = if (english) c.triEn else c.tri
        val second = if (english) c.biEn else c.bi
        // 1. Wie wird das Wort in genau diesem Zusammenhang geschrieben? („Vielen Dank“, „zum einen“)
        var seenInContext = false
        for (f in listOf(first, second)) {
            f ?: continue
            val i = f.index(low)
            if (i >= 0) { seenInContext = true; f.cased[i]?.let { return it } }
        }
        // 2. Nach „beim“, „zum“, „vom“ … wird ein Verb zum Hauptwort („beim Unterkringeln“)
        if (c.nominal && !seenInContext && isVerbLike(low)) {
            return (userCasing[low] ?: dict[low]?.word ?: low).replaceFirstChar { it.uppercase() }
        }
        // 3. Eigene Vorliebe, sonst Wörterbuch
        userCasing[low]?.let { return it }
        val e = dict[low] ?: return low
        return if (english) e.wordEn ?: e.word else e.word
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
        const val START = "<s>"
        private const val CONTEXT_WEIGHT = 1.67f
        private const val CONTEXT_MISS = -0.5f
        /** Ohne Hinweise aus dem Text wird überwiegend Deutsch angenommen. */
        private const val PRIOR_EN = 0.03f
        /** Gewicht der bairischen Wortfolgen gegenüber den hochdeutschen. */
        const val DIALECT_SHARE = 0.5f
        private const val FLOOR_SCORE = 150f
        private const val LN10 = 2.3026f

        /** Nach diesen Wörtern (Verhältniswort + Artikel) ist ein folgendes Verb ein Hauptwort. */
        private val NOMINAL_CONTEXT = setOf(
            "beim", "zum", "vom", "ins", "fürs", "aufs", "durchs", "ans", "ums", "übers", "im", "am",
        )
        /** Nach diesen Wörtern kann ein Verb ein Hauptwort sein („das Essen“) oder nicht („das essen wir“). */
        private val ARTICLES = setOf(
            "das", "des", "dieses", "dies", "ein", "kein", "mein", "dein", "sein", "ihr", "unser", "euer",
        )
        /** Sehen aus wie Verben, sind in diesen Zusammenhängen aber fast nie Hauptwörter. */
        private val NOT_NOMINAL = setOf(
            "einen", "sein", "meinen", "deinen", "seinen", "eigenen", "werden", "haben", "weiten", "gleichen",
            "morgen", "gegen", "wegen", "eben", "neben", "sieben", "zusammen", "offen", "selten", "oben", "unten",
        )
        private val VERB_PREFIXES = listOf(
            "ab", "an", "auf", "aus", "bei", "durch", "ein", "her", "hin", "los", "mit", "nach", "um", "unter", "über",
            "vor", "weg", "zu", "zurück", "zusammen", "weiter", "raus", "rein", "ran", "rum", "runter", "rüber", "hoch",
            "fest", "heraus", "herein", "hinaus", "hinein", "herum", "herunter", "vorbei", "wieder",
        )

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

        /** Nachbartasten anhand der Tastenpositionen (Reihen werden zentriert wie auf der Tastatur). */
        private fun neighborsOf(rows: List<String>): Map<Char, String> {
            val widest = rows.maxOf { it.length }
            val pos = HashMap<Char, Pair<Float, Float>>()
            for ((r, row) in rows.withIndex()) {
                val off = (widest - row.length) / 2f
                row.forEachIndexed { i, c -> pos[c] = (off + i + 0.5f) to r.toFloat() }
            }
            val m = HashMap<Char, String>()
            for ((c, p) in pos) {
                m[c] = pos.filter { (o, q) ->
                    o != c && (p.first - q.first).let { it * it } + (p.second - q.second).let { it * it } <= 2.1f
                }.keys.joinToString("")
            }
            return m
        }

        @Volatile private var NEIGHBORS: Map<Char, String> = neighborsOf(listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"))

        /** Aktuelle Tastenanordnung mitteilen (für die Tippfehler-Erkennung). */
        fun setKeyRows(rows: List<String>) { NEIGHBORS = neighborsOf(rows) }
    }
}
