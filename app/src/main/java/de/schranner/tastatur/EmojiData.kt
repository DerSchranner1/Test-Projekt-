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
    }

    /** Kann dieses Handy das Emoji darstellen? */
    fun isSupported(emoji: String): Boolean = !canCheckGlyphs || paint.hasGlyph(emoji)

    /** Emoji-Vorschlag beim Tippen („danke“ -> 🙏, „pizza“ -> 🍕). */
    fun suggestionFor(word: String): String? {
        val w = word.lowercase()
        return CURATED[w] ?: words[w]
    }

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
        return (first + rest).take(limit)
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
    )

}
