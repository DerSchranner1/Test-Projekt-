package de.schranner.tastatur

/** Nachbearbeitung von diktiertem Text. */
object VoiceText {

    private val COMMANDS = listOf(
        "neuer absatz" to "\n\n", "neue zeile" to "\n", "fragezeichen" to "?", "ausrufezeichen" to "!",
        "doppelpunkt" to ":", "semikolon" to ";", "strichpunkt" to ";", "komma" to ",",
    )

    /**
     * Gesprochene Satzzeichen umwandeln („Komma“, „Fragezeichen“, „neue Zeile“; „Punkt“ nur am Ende).
     * Hat die Erkennung schon selbst Satzzeichen gesetzt, bleibt der Text unverändert.
     */
    fun spokenPunctuation(text: String): String {
        if (text.any { it in ".,!?;:" }) return text
        var t = " ${text.trim()} "
        for ((word, sign) in COMMANDS) {
            t = t.replace(Regex("\\s+$word(?=\\s)", RegexOption.IGNORE_CASE), sign)
        }
        t = t.trim(' ')
        if (t.endsWith(" punkt", ignoreCase = true)) t = t.dropLast(6) + "."
        t = t.replace(Regex("\n +"), "\n")
        if (t == text.trim()) return text
        // Nach einem Satzende groß weiterschreiben
        val sb = StringBuilder(t)
        var capNext = false
        for (i in sb.indices) {
            val c = sb[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') capNext = true
            else if (capNext && c.isLetter()) { sb[i] = c.uppercaseChar(); capNext = false }
            else if (!c.isWhitespace()) capNext = false
        }
        return sb.toString()
    }

    /**
     * Erstes Wort passend zum Zusammenhang schreiben: am Satzanfang groß; mitten im Satz nur dann groß,
     * wenn es laut Wörterbuch ein Hauptwort oder Name ist.
     */
    fun fitToContext(text: String, sentenceStart: Boolean, dictionaryForm: (String) -> String?): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        if (sentenceStart) return t.replaceFirstChar { it.uppercase() }
        if (t.length > 1 && t[0].isUpperCase() && t[1].isLowerCase()) {
            val first = Regex("^[\\p{L}]+").find(t)?.value
            val form = first?.let(dictionaryForm)
            if (form != null && form[0].isLowerCase()) return t.replaceFirstChar { it.lowercase() }
        }
        return t
    }
}
