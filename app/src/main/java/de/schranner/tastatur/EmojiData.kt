package de.schranner.tastatur

import android.content.Context
import android.graphics.Paint

/**
 * Alle Emojis in der Reihenfolge und den Gruppen von Gboard (Daten: googlefonts/emoji-metadata),
 * mit Hautton-Varianten und deutschen/englischen Suchwörtern (Unicode CLDR).
 * Emojis, die dieses Handy nicht darstellen kann, werden ausgeblendet.
 */
object EmojiData {

    class Emoji(val text: String, val animated: Boolean, val alternates: List<String>, val keywords: String)

    class Category(val icon: String, val name: String, val emojis: List<Emoji>)

    private val GROUPS = listOf(
        "🙂" to "Smileys", "🧑" to "Menschen", "🐶" to "Tiere & Natur", "🍔" to "Essen & Trinken",
        "🚗" to "Reisen & Orte", "⚽" to "Aktivitäten", "💡" to "Gegenstände", "❤️" to "Symbole", "🏳️" to "Flaggen",
    )

    @Volatile var categories: List<Category> = GROUPS.map { Category(it.first, it.second, emptyList()) }
        private set
    @Volatile private var words: Map<String, String> = emptyMap()
    /** Wort -> Emoji aus den Emoji-Namen („pizza“ -> 🍕, „berg“ -> ⛰️): verlässlicher als alle Suchwörter. */
    @Volatile private var names: Map<String, String> = emptyMap()
    /** Animierte Emojis, die in der App stecken (ohne Internet sofort da). */
    @Volatile var bundled: Set<String> = emptySet()
        private set
    /** Eingebaute Animationen, die beliebtesten zuerst. */
    @Volatile var bundledOrder: List<String> = emptyList()
        private set
    @Volatile private var loaded = false
    private var canCheckGlyphs = false
    private val paint = Paint()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) { if (!loaded) { try { load(context.applicationContext) } catch (_: Exception) { }; loaded = true } }
    }

    private fun load(context: Context) {
        val lists = List(GROUPS.size) { ArrayList<Emoji>() }
        val all = ArrayList<Pair<Int, Emoji>>()
        context.assets.open("emoji.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 5) continue
                val g = p[0].toIntOrNull() ?: continue
                if (g !in lists.indices) continue
                val alts = if (p[3].isEmpty()) emptyList() else p[3].split(' ')
                all += g to Emoji(p[1], p[2] == "1", alts, p[4])
            }
        }
        // Nur Emojis zeigen, die die Schrift dieses Handys kennt. Liefert die Prüfung fast nichts,
        // ist sie nicht verlässlich – dann alles zeigen.
        val supported = all.filter { paint.hasGlyph(it.second.text) }
        canCheckGlyphs = supported.size >= 300
        for ((g, e) in if (canCheckGlyphs) supported else all) lists[g] += e
        categories = GROUPS.mapIndexed { i, (icon, name) -> Category(icon, name, lists[i]) }

        val map = HashMap<String, String>(6000)
        context.assets.open("emoji_words.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val t = line.indexOf('\t')
                if (t > 0) map[line.substring(0, t)] = line.substring(t + 1)
            }
        }
        if (canCheckGlyphs) map.values.removeAll { !paint.hasGlyph(it) }
        words = map

        val strong = HashMap<String, String>()
        val weak = HashMap<String, String>()
        for ((_, e) in all) {
            if (canCheckGlyphs && !paint.hasGlyph(e.text)) continue
            val parts = e.keywords.split('|')
            // Erst der deutsche Name, dann deutsche Suchwörter (sortiert); der englische Name bricht die Sortierung
            var en: String? = null
            for (i in 2 until parts.size) if (parts[i] < parts[i - 1]) { en = parts[i]; break }
            for (n in listOfNotNull(parts.firstOrNull(), en)) {
                val ws = n.substringAfter(": ").trim().split(' ')
                if (ws.size == 1) strong.putIfAbsent(ws[0], e.text)
                else if (ws.size == 2 && ws[1].length >= 4) weak.putIfAbsent(ws[1], e.text)
            }
        }
        for ((k, v) in weak) strong.putIfAbsent(k, v)
        strong.keys.removeAll { it.length < 3 || it in STOP }
        names = strong

        val files = try { context.assets.list("animated")?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        bundled = files.mapNotNull { f ->
            try {
                val cps = f.removeSuffix(".gif").split('_').map { it.toInt(16) }.toIntArray()
                String(cps, 0, cps.size)
            } catch (_: Exception) { null }
        }.toSet()
        bundledOrder = try {
            context.assets.open("animated/order.txt").bufferedReader(Charsets.UTF_8).readLines()
                .map { it.trim() }.filter { it in bundled }
        } catch (_: Exception) { bundled.toList() }
    }

    /** Pfad der eingebauten Animation zu diesem Emoji (oder null). */
    fun bundledAsset(emoji: String): String? =
        if (emoji in bundled) "animated/" + emoji.codePoints().toArray().joinToString("_") { Integer.toHexString(it) } + ".gif" else null

    // ------------------------------------------------------------ Text -> Emoji

    /**
     * Emojis, die zum Text passen – alles auf dem Handy, ohne Internet.
     * Erkennt Wendungen („gute nacht“ -> 😴), gebeugte Wörter („gefeiert“ -> 🥳),
     * zusammengesetzte Wörter („lieblingspizza“ -> 🍕) und was du selbst nach einem Wort benutzt hast.
     * @param words die letzten Wörter, das letzte ist das wichtigste
     */
    fun predict(words: List<String>, learned: Map<String, String> = emptyMap(), limit: Int = 3): List<String> {
        val low = words.takeLast(5).map { it.lowercase().trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (low.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val w = low.last()
        learned[w]?.let { out += it }
        for ((phrase, emojis) in PHRASES) {
            val pw = phrase.split(' ')
            if (low.size >= pw.size && low.takeLast(pw.size) == pw) emojis.split(' ').forEach { out += it }
        }
        lookupWord(w)?.let { out += it }
        return out.filter { isSupported(it) }.take(limit)
    }

    /** Ein Emoji zu einem einzelnen Wort (oder null). */
    fun lookupWord(word: String): String? {
        val w = word.lowercase()
        if (w.length < 2 || w in STOP) return null
        direct(w)?.let { return it }
        for (st in stems(w)) direct(st)?.let { return it }
        if (w.length >= 7) {
            // Zusammengesetzte Wörter: hinterer Teil („lieblingspizza“), sonst vorderer Teil („geburtstagsfeier“)
            for (k in 3..w.length - 4) {
                val tail = w.substring(k)
                CURATED[tail]?.let { return it }
                if (tail.length >= 5) names[tail]?.let { return it }
            }
            for (k in w.length - 3 downTo 4) {
                val head = w.substring(0, k)
                CURATED[head]?.let { return it }
                if (head.endsWith('s')) CURATED[head.dropLast(1)]?.let { return it }
            }
        }
        return null
    }

    private fun direct(w: String): String? = CURATED[w] ?: names[w]

    private fun stems(w: String): List<String> {
        val out = ArrayList<String>()
        val bases = ArrayList<String>()
        for (suf in SUFFIXES) if (w.endsWith(suf) && w.length - suf.length >= 3) bases += w.dropLast(suf.length)
        // Partizip: „gefeiert“ -> „feier“, „gelacht“ -> „lach“
        if (w.startsWith("ge") && w.length >= 6) {
            val core = w.drop(2)
            bases += core
            for (suf in listOf("et", "t", "en")) if (core.endsWith(suf) && core.length - suf.length >= 3) bases += core.dropLast(suf.length)
        }
        for (b in bases) { out += b; out += b + "en"; out += b + "n"; out += b + "e" }
        return out
    }

    /** Emojis, die du selbst nach Wörtern benutzt hast (nur auf diesem Handy gespeichert). */
    fun learned(context: Context): Map<String, String> {
        val sp = context.getSharedPreferences(LEARN_FILE, Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        return (sp.all as Map<String, Any?>).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    fun learn(context: Context, word: String, emoji: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > 30) return
        context.getSharedPreferences(LEARN_FILE, Context.MODE_PRIVATE).edit().putString(w, emoji).apply()
    }

    fun forgetLearned(context: Context) {
        context.getSharedPreferences(LEARN_FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Smileys aus Zeichen: „:)“ -> 🙂, „<3“ -> ❤️. Gibt (Zeichenfolge, Emoji) zurück. */
    fun emoticonAtEnd(text: String): Pair<String, String>? {
        val t = text.trimEnd(' ')
        for ((k, v) in EMOTICONS) {
            if (t.endsWith(k)) {
                val before = t.length - k.length
                // Nur als eigenes „Wort“ – nicht mitten in einem Link oder einer Uhrzeit
                if (before == 0 || t[before - 1].isWhitespace()) return k to v
            }
        }
        return null
    }

    private const val LEARN_FILE = "emoji_learn"

    private val SUFFIXES = listOf("ern", "en", "er", "es", "em", "st", "ten", "te", "et", "e", "n", "s", "t")

    private val STOP = setOf(
        "ich", "du", "er", "sie", "es", "wir", "ihr", "und", "oder", "aber", "die", "der", "das", "den", "dem", "des",
        "ein", "eine", "einen", "einem", "ist", "bin", "bist", "sind", "hat", "hab", "habe", "haben", "mit", "auf",
        "an", "in", "im", "zu", "zum", "zur", "von", "vom", "für", "nicht", "so", "wie", "was", "wer", "wo", "wann",
        "noch", "schon", "auch", "nur", "mal", "dann", "denn", "doch", "man", "mich", "mir", "dich", "dir", "uns",
        "the", "and", "you", "for", "are", "with", "this", "that", "from", "have", "not", "but", "can", "was", "will",
        "middle", "order", "under", "side", "mai", "david", "china", "kinder", "frauen", "männer", "personen",
        "person", "kind", "zahlen", "vier", "fünf", "sechs", "alt", "bald", "mean", "lives", "total", "range",
        "trade", "force", "board", "field", "class", "line", "change", "case", "fall", "set", "part", "long",
        "come", "keep", "real", "public", "care", "stand", "move", "saw", "turn", "act", "media", "major",
        "market", "study", "front", "rund", "round", "lost", "died", "gone", "won", "press", "shot", "record",
        "position", "security", "personal", "private", "view", "ground", "step", "amount", "evidence", "natural",
        "serious", "oil", "page", "range", "seite", "bild", "rahmen", "blick", "glaube", "stehen", "halten",
        "wissen", "frage", "fragen", "reden", "kosten", "leicht", "neu", "schreiben", "suche", "test",
    )

    private val EMOTICONS = listOf(
        ":'-(" to "😢", ":'(" to "😢", ":-)" to "🙂", ":)" to "🙂", ":-D" to "😄", ":D" to "😄", ";-)" to "😉", ";)" to "😉",
        ":-(" to "🙁", ":(" to "🙁", ":-P" to "😛", ":P" to "😛", ":p" to "😛", ":-*" to "😘", ":*" to "😘",
        ":-O" to "😮", ":O" to "😮", ":o" to "😮", "<3" to "❤️", "</3" to "💔", "xD" to "😆", "XD" to "😆",
        "^^" to "😊", "^_^" to "😊", ":-/" to "😕", ":/" to "😕", "B-)" to "😎", "8-)" to "😎", ":-|" to "😐", ":|" to "😐",
        "o/" to "👋", "\\o/" to "🙌", ":3" to "😺",
    ).sortedByDescending { it.first.length }

    /** Wendungen, die als Ganzes ein Emoji bekommen. Mehrere Emojis durch Leerzeichen getrennt, das beste zuerst. */
    private val PHRASES: List<Pair<String, String>> = listOf(
        "ich liebe dich" to "❤️ 😘 🥰", "liebe dich" to "❤️ 🥰", "hab dich lieb" to "🥰 ❤️", "love you" to "❤️ 😘",
        "i love you" to "❤️ 😘 🥰", "vermisse dich" to "🥺 ❤️", "miss you" to "🥺 ❤️",
        "gute nacht" to "😴 🌙 💤", "schlaf gut" to "😴 🌙", "träum süß" to "😴 💭", "träum was schönes" to "😴 💭",
        "good night" to "😴 🌙", "guten morgen" to "☀️ 😊 ☕", "good morning" to "☀️ 😊",
        "alles gute" to "🎉 🍀 🥳", "alles gute zum geburtstag" to "🎂 🎉 🥳", "happy birthday" to "🎂 🎉 🥳",
        "herzlichen glückwunsch" to "🎉 🥳 🎂", "frohe weihnachten" to "🎄 🎅 🎁", "frohe ostern" to "🐣 🐰 🥚",
        "frohes neues" to "🎆 🥂 🍀", "frohes neues jahr" to "🎆 🥂 🍀", "guten rutsch" to "🎆 🥂 🍀",
        "gute besserung" to "🍀 💐 🤒", "viel glück" to "🍀 🤞", "viel erfolg" to "🍀 💪", "viel spaß" to "🥳 😄",
        "schönes wochenende" to "😎 🎉", "schönen urlaub" to "🏖️ 😎 ☀️", "gute reise" to "✈️ 🧳", "gute fahrt" to "🚗 👋",
        "bis später" to "👋", "bis morgen" to "👋", "bis bald" to "👋", "bis dann" to "👋", "see you" to "👋",
        "na klar" to "👍", "kein problem" to "👍 👌", "no problem" to "👍 👌", "keine ahnung" to "🤷",
        "oh mein gott" to "😱", "oh my god" to "😱", "guten appetit" to "😋 🍽️", "gut gemacht" to "👏 💪",
        "well done" to "👏", "sehr gut" to "👍 👏", "bin unterwegs" to "🏃 🚗", "auf dem weg" to "🏃 🚗",
        "drück dich" to "🤗", "fühl dich gedrückt" to "🤗 ❤️", "pass auf dich auf" to "❤️ 🙏", "thank you" to "🙏 😊",
        "vielen dank" to "🙏 😊", "danke dir" to "🙏 😊", "of course" to "👍",
    )

    /** Kann dieses Handy das Emoji darstellen? */
    fun isSupported(emoji: String): Boolean = !canCheckGlyphs || paint.hasGlyph(emoji)

    /** Emoji-Vorschlag beim Tippen („danke“ -> 🙏, „pizza“ -> 🍕). */
    fun suggestionFor(word: String): String? = lookupWord(word)

    /** Suche nach Namen und Suchwörtern (Deutsch und Englisch). */
    fun search(query: String, limit: Int = 80): List<Emoji> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val first = ArrayList<Emoji>()
        val rest = ArrayList<Emoji>()
        for (c in categories) for (e in c.emojis) {
            val k = e.keywords
            when {
                k.startsWith(q) -> first += e                                   // der Name beginnt so
                k.contains("|$q") || k.contains(" $q") -> rest += e             // ein Suchwort beginnt so
            }
        }
        // Bester Treffer aus der Text-Erkennung zuerst („liebe“ -> ❤️)
        val top = lookupWord(q)?.let { t -> categories.firstNotNullOfOrNull { c -> c.emojis.firstOrNull { it.text == t } } ?: Emoji(t, false, emptyList(), "") }
        return (listOfNotNull(top) + first + rest).distinctBy { it.text }.take(limit)
    }

    /** Emojis, die es als Animation gibt (Noto Animated Emoji). */
    fun animated(): List<Emoji> = categories.flatMap { c -> c.emojis.filter { it.animated } }

    private val CURATED: Map<String, String> = mapOf(
        "liebe" to "❤️", "lieb" to "🥰", "herz" to "❤️", "love" to "❤️", "ily" to "❤️", "hdl" to "❤️",
        "hdgdl" to "❤️", "kuss" to "😘", "küsschen" to "😘", "bussi" to "😘",
        "haha" to "😂", "hahaha" to "🤣", "lol" to "😂", "lach" to "😂", "witzig" to "😂", "lustig" to "😄",
        "freu" to "😊", "freue" to "😊", "freude" to "😊", "glücklich" to "😊", "super" to "👍",
        "ok" to "👌", "okay" to "👌", "top" to "👍", "perfekt" to "👌", "genau" to "👍", "danke" to "🙏", "bitte" to "🙏", "dankeschön" to "🙏",
        "traurig" to "😢", "weinen" to "😭", "sorry" to "😔", "schade" to "😕",
        "wütend" to "😠", "sauer" to "😡", "müde" to "😴", "schlafen" to "😴",
        "krank" to "🤒", "cool" to "😎", "wow" to "😮", "krass" to "🤯", "hmm" to "🤔", "überlege" to "🤔", "party" to "🎉", "feiern" to "🥳", "geburtstag" to "🎂",
        "glückwunsch" to "🎉", "gratuliere" to "🎉", "prost" to "🍻", "bier" to "🍺", "wein" to "🍷",
        "kaffee" to "☕", "pizza" to "🍕", "burger" to "🍔", "kuchen" to "🍰",
        "hunger" to "😋", "lecker" to "😋", "sonne" to "☀️", "regen" to "🌧️", "schnee" to "❄️",
        "kalt" to "🥶", "heiß" to "🥵", "feuer" to "🔥", "geil" to "🔥", "stark" to "💪", "sport" to "💪",
        "fußball" to "⚽", "auto" to "🚗", "zug" to "🚆", "urlaub" to "🏖️", "flug" to "✈️",
        "zuhause" to "🏠", "geld" to "💶", "hund" to "🐶",
        "katze" to "🐱", "blume" to "🌸", "blumen" to "💐", "baum" to "🌳", "weihnachten" to "🎄",
        "ostern" to "🐣", "geschenk" to "🎁", "musik" to "🎵", "telefon" to "📞", "handy" to "📱", "idee" to "💡", "stern" to "⭐", "hallo" to "👋", "hi" to "👋",
        "tschüss" to "👋", "ciao" to "👋", "servus" to "👋",
        "achtung" to "⚠️", "fertig" to "✅", "erledigt" to "✅", "check" to "✅", "fehler" to "❌",
        "schnell" to "⚡", "baby" to "👶", "familie" to "👪",
        "beten" to "🙏", "daumen" to "👍", "klatschen" to "👏", "bravo" to "👏", "applaus" to "👏",
        "augen" to "👀", "geheim" to "🤫", "teufel" to "😈", "engel" to "😇",
        "kotzen" to "🤮", "eklig" to "🤢", "facepalm" to "🤦", "egal" to "🤷",
        "umarmung" to "🤗", "knuddel" to "🤗", "drücker" to "🤗", "schatz" to "❤️", "verliebt" to "😍", "süß" to "🥰",
        "lachen" to "😂", "omg" to "😱", "schock" to "😱", "angst" to "😨", "peinlich" to "😳", "nervös" to "😬",
        "kopfschmerzen" to "🤕", "erkältet" to "🤧", "schlaf" to "😴", "nacht" to "🌙", "morgen" to "☀️",
        "frühstück" to "🥐", "essen" to "🍽️", "brot" to "🍞", "nudeln" to "🍝", "döner" to "🥙", "eis" to "🍦",
        "schokolade" to "🍫", "tee" to "🍵", "sekt" to "🥂", "cocktail" to "🍹", "wasser" to "💧",
        "laufen" to "🏃", "joggen" to "🏃", "fitness" to "💪", "gym" to "💪", "training" to "💪", "tanzen" to "💃",
        "schwimmen" to "🏊", "fahrrad" to "🚲", "bus" to "🚌", "flugzeug" to "✈️", "strand" to "🏖️", "meer" to "🌊",
        "berge" to "⛰️", "arbeit" to "💼", "büro" to "💼", "schule" to "🏫", "uni" to "🎓", "prüfung" to "📝",
        "lernen" to "📚", "buch" to "📖", "film" to "🎬", "kino" to "🍿", "spiel" to "🎮", "zocken" to "🎮",
        "einkaufen" to "🛒", "shopping" to "🛍️", "termin" to "📅", "uhr" to "⏰", "später" to "⏰", "warten" to "⏳",
        "sicher" to "👍", "klar" to "👍", "jawohl" to "👍", "ja" to "👍", "nein" to "👎", "niemals" to "🙅",
        "hilfe" to "🆘", "glück" to "🍀", "erfolg" to "🏆", "gewonnen" to "🏆", "sieg" to "🏆", "geschafft" to "🎉",
        "regenbogen" to "🌈", "gewitter" to "⛈️", "sommer" to "☀️", "winter" to "❄️", "herbst" to "🍂", "frühling" to "🌷",
        "silvester" to "🎆", "hochzeit" to "💍", "schwanger" to "🤰", "katzen" to "🐱", "hunde" to "🐶",
        "pferd" to "🐴", "einhorn" to "🦄", "affe" to "🐵", "schwein" to "🐷", "geburtstagskind" to "🥳",
        "thanks" to "🙏", "thx" to "🙏", "please" to "🙏", "happy" to "😊", "sad" to "😢", "angry" to "😠",
        "tired" to "😴", "sleep" to "😴", "funny" to "😂", "food" to "🍽️", "hungry" to "😋", "beer" to "🍺",
        "coffee" to "☕", "party" to "🎉", "birthday" to "🎂", "congrats" to "🎉", "yes" to "👍", "no" to "👎",
        "hello" to "👋", "hey" to "👋", "bye" to "👋", "car" to "🚗", "home" to "🏠", "money" to "💰", "fire" to "🔥",
        "wohnung" to "🏠", "haus" to "🏠", "küche" to "🍳", "kochen" to "🍳", "putzen" to "🧹", "wäsche" to "🧺",
        "doktor" to "👨‍⚕️", "arzt" to "🩺", "krankenhaus" to "🏥", "apotheke" to "💊", "polizei" to "🚓",
    )

}
