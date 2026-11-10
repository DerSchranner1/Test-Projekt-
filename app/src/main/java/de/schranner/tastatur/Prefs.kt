package de.schranner.tastatur

import android.content.Context
import android.content.SharedPreferences

/** Zentrale Einstellungen der Tastatur. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var groqKey: String
        get() = sp.getString(KEY_GROQ, "") ?: ""
        set(v) = sp.edit().putString(KEY_GROQ, v.trim()).apply()

    /** "de" oder "auto" (automatische Spracherkennung beim Diktieren). */
    var voiceLanguage: String
        get() = sp.getString(KEY_VOICE_LANG, "de") ?: "de"
        set(v) = sp.edit().putString(KEY_VOICE_LANG, v).apply()

    var vibrate: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(v) = sp.edit().putBoolean(KEY_VIBRATE, v).apply()

    var numberRow: Boolean
        get() = sp.getBoolean(KEY_NUMBER_ROW, false)
        set(v) = sp.edit().putBoolean(KEY_NUMBER_ROW, v).apply()

    var markTypos: Boolean
        get() = sp.getBoolean(KEY_MARK_TYPOS, true)
        set(v) = sp.edit().putBoolean(KEY_MARK_TYPOS, v).apply()

    var emojiSuggestions: Boolean
        get() = sp.getBoolean(KEY_EMOJI_SUGG, true)
        set(v) = sp.edit().putBoolean(KEY_EMOJI_SUGG, v).apply()

    /** Tastenhöhe in Prozent (80..130). */
    var keyHeightPercent: Int
        get() = sp.getInt(KEY_HEIGHT, 100)
        set(v) = sp.edit().putInt(KEY_HEIGHT, v.coerceIn(80, 130)).apply()

    companion object {
        const val KEY_GROQ = "groq_key"
        const val KEY_VOICE_LANG = "voice_lang"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_MARK_TYPOS = "mark_typos"
        const val KEY_EMOJI_SUGG = "emoji_suggestions"
        const val KEY_HEIGHT = "key_height"
    }
}
