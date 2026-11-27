package de.schranner.tastatur

/**
 * Was steht vor dem Cursor? Daraus ergeben sich die Wortvorschläge.
 * @param prev letztes Wort (null am Satzanfang)
 * @param start beginnt hier ein neuer Satz?
 * @param prev2 das Wort davor; „<s>“ wenn [prev] das erste Wort des Satzes ist
 * @param recent die letzten Wörter (für die Erkennung Deutsch/Englisch)
 */
data class TextContext(val prev: String?, val start: Boolean, val prev2: String?, val recent: List<String>) {

    companion object {
        private const val END = ".!?…\n"
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*")

        private fun wordChar(s: String, i: Int): Boolean {
            val c = s[i]
            if (c.isLetterOrDigit()) return true
            // Apostroph/Bindestrich nur mitten im Wort („geht's“, „E-Mail“)
            return (c == '\'' || c == '’' || c == '-') && i > 0 && i < s.length - 1 &&
                s[i - 1].isLetterOrDigit() && s[i + 1].isLetterOrDigit()
        }

        /**
         * @param before Text vor dem Cursor (ohne das Wort, das gerade getippt wird)
         * @param complete ist das der ganze Text bis zum Feldanfang?
         */
        fun parse(before: String, complete: Boolean = true): TextContext {
            val recent = WORD.findAll(before).map { it.value }.toList().takeLast(6)
            var i = before.length
            // Leerzeichen, Kommas, Anführungszeichen usw. überspringen
            fun skipSoft() { while (i > 0 && !wordChar(before, i - 1) && before[i - 1] !in END) i-- }
            skipSoft()
            if (i == 0 || before[i - 1] in END) return TextContext(null, true, null, recent)
            val end1 = i
            while (i > 0 && wordChar(before, i - 1)) i--
            val prev = before.substring(i, end1)
            skipSoft()
            val prev2 = when {
                i == 0 -> if (complete) SuggestionEngine.START else null
                before[i - 1] in END -> SuggestionEngine.START
                else -> {
                    val end2 = i
                    while (i > 0 && wordChar(before, i - 1)) i--
                    before.substring(i, end2)
                }
            }
            return TextContext(prev, false, prev2, recent)
        }
    }
}
