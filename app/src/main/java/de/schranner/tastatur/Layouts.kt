package de.schranner.tastatur

import android.graphics.RectF

enum class KeyType { CHAR, SHIFT, DELETE, ENTER, SPACE, SYMBOLS, SYMBOLS2, LETTERS, EMOJI }

class Key(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
    val alts: List<String> = emptyList(),
) {
    /** Kleiner Hinweis oben rechts (z. B. Ziffer bei langem Drücken). */
    val hint: String? = null
    val bounds = RectF()
}

typealias KeyRows = List<List<Key>>

object Layouts {
    private fun c(label: String, alts: String = "") =
        Key(label, KeyType.CHAR, 1f, if (alts.isEmpty()) emptyList() else alts.split(" "))

    private fun bottomRow(left: Key, comma: String, period: String, emoji: Boolean): List<Key> {
        val list = mutableListOf(left)
        list += Key(comma, KeyType.CHAR, 1f, commaAlts(comma))
        if (emoji) list += Key("☺", KeyType.EMOJI, 1f)
        list += Key(" ", KeyType.SPACE, if (emoji) 4f else 5f)
        list += Key(period, KeyType.CHAR, 1f, listOf(".", "…", "!", "?", ",", "-", ":", ";", "'", "\"").filter { it != period })
        list += Key("⏎", KeyType.ENTER, 1.5f)
        return list
    }

    private fun commaAlts(comma: String) = when (comma) {
        "@" -> listOf(".com", ".de", "_", "-", ",")
        "/" -> listOf(".com", ".de", "https://", "www.", ",")
        else -> listOf(";", ":", "!", "?", "'", "\"", "-")
    }

    fun letters(numberRow: Boolean, field: FieldKind): KeyRows {
        val rows = mutableListOf<List<Key>>()
        if (numberRow) {
            rows += "1234567890".map { Key(it.toString(), KeyType.CHAR, 1f, numberAlts(it)) }
        }
        // Lang drücken: der Umlaut ist automatisch vorausgewählt (a → ä, o → ö, u → ü, s → ß)
        rows += listOf(
            c("q"), c("w"), c("e", "é è ê ë €"), c("r"), c("t"),
            c("z"), c("u", "ü ú ù û"), c("i", "í ì î ï"), c("o", "ö ó ò ô œ ø"), c("p"),
        )
        rows += listOf(
            c("a", "ä á à â æ å"), c("s", "ß ś š"), c("d"), c("f"), c("g"),
            c("h"), c("j"), c("k"), c("l"),
        )
        rows += listOf(Key("⇧", KeyType.SHIFT, 1.5f)) +
            listOf(c("y"), c("x"), c("c", "ç č"), c("v"), c("b"), c("n", "ñ"), c("m")) +
            listOf(Key("⌫", KeyType.DELETE, 1.5f))
        val comma = when (field) { FieldKind.EMAIL -> "@"; FieldKind.URL -> "/"; else -> "," }
        rows += bottomRow(Key("?123", KeyType.SYMBOLS, 1.5f), comma, ".", true)
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
