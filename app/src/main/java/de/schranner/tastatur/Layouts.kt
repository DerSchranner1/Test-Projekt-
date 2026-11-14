package de.schranner.tastatur

import android.graphics.RectF

enum class KeyType { CHAR, SHIFT, DELETE, ENTER, SPACE, SYMBOLS, SYMBOLS2, LETTERS, EMOJI }

class Key(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
    val alts: List<String> = emptyList(),
) {
    val bounds = RectF()
}

typealias KeyRows = List<List<Key>>

/** Buchstaben-Anordnung. */
enum class KeyLayout(val id: String, val title: String) {
    QWERTZ("qwertz", "QWERTZ"),
    QWERTZ_UMLAUT("qwertz_umlaut", "QWERTZ mit Ä Ö Ü"),
    QWERTY("qwerty", "QWERTY");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: QWERTZ
    }
}

object Layouts {
    private fun c(label: String, alts: String = "") =
        Key(label, KeyType.CHAR, 1f, if (alts.isEmpty()) emptyList() else alts.split(" "))

    // Lang drücken: der Umlaut ist automatisch vorausgewählt (a → ä, o → ö, u → ü, s → ß)
    private val ALTS = mapOf(
        'e' to "é è ê ë €", 'u' to "ü ú ù û", 'i' to "í ì î ï", 'o' to "ö ó ò ô œ ø",
        'a' to "ä á à â æ å", 's' to "ß ś š", 'c' to "ç č", 'n' to "ñ", 'ß' to "ẞ",
    )

    /** Die drei Buchstabenreihen als Text – auch für Tippfehler-Erkennung (Nachbartasten). */
    fun letterRows(layout: KeyLayout, eszett: Boolean): List<String> {
        val umlaut = layout == KeyLayout.QWERTZ_UMLAUT
        val qwerty = layout == KeyLayout.QWERTY
        return listOf(
            (if (qwerty) "qwertyuiop" else "qwertzuiop") + (if (umlaut) "ü" else ""),
            "asdfghjkl" + (if (umlaut) "öä" else if (eszett) "ß" else ""),
            (if (qwerty) "zxcvbnm" else "yxcvbnm") + (if (umlaut && eszett) "ß" else ""),
        )
    }

    private fun bottomRow(left: Key, comma: String, period: String, emoji: Boolean, scale: Float = 1f): List<Key> {
        val list = mutableListOf(Key(left.label, left.type, left.weight * scale))
        list += Key(comma, KeyType.CHAR, scale, commaAlts(comma))
        if (emoji) list += Key("☺", KeyType.EMOJI, scale)
        list += Key(" ", KeyType.SPACE, (if (emoji) 4f else 5f) * scale)
        list += Key(period, KeyType.CHAR, scale, listOf(".", "…", "!", "?", ",", "-", ":", ";", "'", "\"").filter { it != period })
        list += Key("⏎", KeyType.ENTER, 1.5f * scale)
        return list
    }

    private fun commaAlts(comma: String) = when (comma) {
        "@" -> listOf(".com", ".de", "_", "-", ",")
        "/" -> listOf(".com", ".de", "https://", "www.", ",")
        else -> listOf(";", ":", "!", "?", "'", "\"", "-")
    }

    fun letters(
        numberRow: Boolean, field: FieldKind,
        layout: KeyLayout = KeyLayout.QWERTZ, eszett: Boolean = false,
    ): KeyRows {
        val text = letterRows(layout, eszett)
        val cols = text[0].length                 // 10 oder 11
        val scale = cols / 10f
        val rows = mutableListOf<List<Key>>()
        if (numberRow) {
            rows += "1234567890".map { Key(it.toString(), KeyType.CHAR, scale, numberAlts(it)) }
        }
        rows += text[0].map { c(it.toString(), ALTS[it] ?: "") }
        rows += text[1].map { c(it.toString(), ALTS[it] ?: "") }
        val side = (cols - text[2].length) / 2f
        rows += listOf(Key("⇧", KeyType.SHIFT, side)) +
            text[2].map { c(it.toString(), ALTS[it] ?: "") } +
            listOf(Key("⌫", KeyType.DELETE, side))
        val comma = when (field) { FieldKind.EMAIL -> "@"; FieldKind.URL -> "/"; else -> "," }
        rows += bottomRow(Key("?123", KeyType.SYMBOLS, 1.5f), comma, ".", true, scale)
        return rows
    }

    private fun numberAlts(ch: Char): List<String> = when (ch) {
        '1' -> listOf("¹", "½", "⅓", "¼")
        '2' -> listOf("²", "⅔")
        '3' -> listOf("³", "¾")
        '0' -> listOf("°", "∅")
        else -> emptyList()
    }

    fun symbols(): KeyRows = listOf(
        "1234567890".map { Key(it.toString(), KeyType.CHAR, 1f, numberAlts(it)) },
        listOf(
            c("@"), c("#"), c("€", "$ £ ¥ ¢"), c("_"), c("&"), c("-", "– — ·"),
            c("+", "±"), c("("), c(")"), c("/", "\\"),
        ),
        listOf(Key("=\\<", KeyType.SYMBOLS2, 1.5f)) +
            listOf(c("*", "★ †"), c("\"", "„ “ ” « »"), c("'", "‚ ‘ ’ ‹ ›"), c(":"), c(";"), c("!", "¡"), c("?", "¿")) +
            listOf(Key("⌫", KeyType.DELETE, 1.5f)),
        bottomRow(Key("ABC", KeyType.LETTERS, 1.5f), ",", ".", false),
    )

    fun symbols2(): KeyRows = listOf(
        listOf(c("~"), c("`"), c("|"), c("•", "♪ ♥ ♠ ♦ ♣"), c("√"), c("π", "Π"), c("÷"), c("×"), c("§", "¶"), c("∆")),
        listOf(c("£"), c("¥"), c("$", "¢"), c("¢"), c("^"), c("°", "′ ″"), c("=", "≠ ≈ ∞"), c("{"), c("}"), c("\\")),
        listOf(Key("?123", KeyType.SYMBOLS, 1.5f)) +
            listOf(c("%", "‰"), c("©"), c("®"), c("™"), c("✓"), c("["), c("]")) +
            listOf(Key("⌫", KeyType.DELETE, 1.5f)),
        bottomRow(Key("ABC", KeyType.LETTERS, 1.5f), "<", ">", false),
    )

    /** Ziffernblock für Zahlen- und Telefonfelder. */
    fun numpad(phone: Boolean): KeyRows = listOf(
        listOf(c("1"), c("2"), c("3"), c(if (phone) "+" else "-")),
        listOf(c("4"), c("5"), c("6"), c(if (phone) "*" else ",")),
        listOf(c("7"), c("8"), c("9"), c(if (phone) "#" else ".")),
        listOf(Key("?123", KeyType.SYMBOLS, 1f), c("0"), Key(" ", KeyType.SPACE, 1f), Key("⌫", KeyType.DELETE, 1f)),
        listOf(Key("ABC", KeyType.LETTERS, 2f), Key("⏎", KeyType.ENTER, 2f)),
    )
}

enum class FieldKind { TEXT, EMAIL, URL, NUMBER, PHONE, PASSWORD }
