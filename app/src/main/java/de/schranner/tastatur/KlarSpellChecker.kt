package de.schranner.tastatur

import android.service.textservice.SpellCheckerService
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo

/**
 * Rechtschreibprüfung für das ganze Handy: Android unterstreicht damit unbekannte Wörter rot –
 * in allen Apps mit normalen Textfeldern, auch wenn nicht mit dieser Tastatur getippt wurde.
 * Wird in den Systemeinstellungen unter „Rechtschreibprüfung“ ausgewählt.
 */
class KlarSpellChecker : SpellCheckerService() {

    override fun createSession(): Session = object : Session() {
        private lateinit var engine: SuggestionEngine

        override fun onCreate() {
            engine = EngineHolder.get(this@KlarSpellChecker)
        }

        override fun onGetSuggestions(textInfo: TextInfo?, suggestionsLimit: Int): SuggestionsInfo =
            check(engine, textInfo?.text ?: "", suggestionsLimit)
    }

    companion object {
        fun check(engine: SuggestionEngine, word: String, limit: Int): SuggestionsInfo {
            val none = arrayOf<String>()
            // Nur „richtige“ Wörter prüfen: keine Zahlen, Adressen, Abkürzungen in Großbuchstaben
            val checkable = word.length in 2..40 && word.all { it.isLetter() || it == '\'' || it == '’' || it == '-' } &&
                !(word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() })
            if (!checkable || !engine.isReady() || engine.isKnown(word)) {
                return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, none)
            }
            val fixes = engine.correctionsFor(word, null).take(limit.coerceIn(1, 5)).toTypedArray()
            var attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (fixes.isNotEmpty()) attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            return SuggestionsInfo(attrs, fixes)
        }
    }
}
