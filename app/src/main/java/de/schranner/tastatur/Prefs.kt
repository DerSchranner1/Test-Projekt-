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

    /** Vibration beim Tippen: Stärke 0..100 (0 = aus). */
    var vibration: Int
        get() = if (sp.contains(KEY_VIBRATION)) sp.getInt(KEY_VIBRATION, 40).coerceIn(0, 100)
        else if (sp.getBoolean(KEY_VIBRATE, true)) 40 else 0      // alte Ein/Aus-Einstellung übernehmen
        set(v) = sp.edit().putInt(KEY_VIBRATION, v.coerceIn(0, 100)).apply()

    var numberRow: Boolean
        get() = sp.getBoolean(KEY_NUMBER_ROW, true)
        set(v) = sp.edit().putBoolean(KEY_NUMBER_ROW, v).apply()

    var markTypos: Boolean
        get() = sp.getBoolean(KEY_MARK_TYPOS, true)
        set(v) = sp.edit().putBoolean(KEY_MARK_TYPOS, v).apply()

    var emojiSuggestions: Boolean
        get() = sp.getBoolean(KEY_EMOJI_SUGG, true)
        set(v) = sp.edit().putBoolean(KEY_EMOJI_SUGG, v).apply()

    /** Größe der Tastatur in Prozent (100 = Standard). */
    var keyHeightPercent: Int
        get() = sp.getInt(KEY_SIZE, 100).coerceIn(SIZE_MIN, SIZE_MAX)
        set(v) = sp.edit().putInt(KEY_SIZE, v.coerceIn(SIZE_MIN, SIZE_MAX)).apply()

    /** Abstand zum unteren Bildschirmrand in dp (Tastatur „höher setzen“). */
    var bottomPadDp: Int
        get() = sp.getInt(KEY_BOTTOM_PAD, 0).coerceIn(0, BOTTOM_MAX)
        set(v) = sp.edit().putInt(KEY_BOTTOM_PAD, v.coerceIn(0, BOTTOM_MAX)).apply()

    /** Buchstaben-Anordnung: qwertz, qwertz_umlaut oder qwerty. */
    var layout: String
        get() = sp.getString(KEY_LAYOUT, "qwertz") ?: "qwertz"
        set(v) = sp.edit().putString(KEY_LAYOUT, v).apply()

    /** Eigene ß-Taste anzeigen. */
    var eszettKey: Boolean
        get() = sp.getBoolean(KEY_ESZETT, false)
        set(v) = sp.edit().putBoolean(KEY_ESZETT, v).apply()

    /** Stärke der unsichtbaren Treffhilfe in Prozent (0 = aus). */
    var touchAssist: Int
        get() = sp.getInt(KEY_TOUCH_ASSIST, 50).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_TOUCH_ASSIST, v.coerceIn(0, 100)).apply()

    /** Einhandmodus: "off", "left" oder "right". */
    var oneHanded: String
        get() = sp.getString(KEY_ONE_HANDED, "off") ?: "off"
        set(v) = sp.edit().putString(KEY_ONE_HANDED, v).apply()

    var giphyKey: String
        get() = sp.getString(KEY_GIPHY, "") ?: ""
        set(v) = sp.edit().putString(KEY_GIPHY, v.trim()).apply()

    /** Wischen zum Schreiben. */
    var gestureTyping: Boolean
        get() = sp.getBoolean(KEY_GESTURE, true)
        set(v) = sp.edit().putBoolean(KEY_GESTURE, v).apply()

    var gestureTrail: Boolean
        get() = sp.getBoolean(KEY_GESTURE_TRAIL, true)
        set(v) = sp.edit().putBoolean(KEY_GESTURE_TRAIL, v).apply()

    /** Echte Autokorrektur bei Leertaste (mit ⌫ rückgängig). Standard: aus. */
    var autocorrect: Boolean
        get() = sp.getBoolean(KEY_AUTOCORRECT, false)
        set(v) = sp.edit().putBoolean(KEY_AUTOCORRECT, v).apply()

    var autoCaps: Boolean
        get() = sp.getBoolean(KEY_AUTOCAPS, true)
        set(v) = sp.edit().putBoolean(KEY_AUTOCAPS, v).apply()

    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean(KEY_DOUBLE_SPACE, true)
        set(v) = sp.edit().putBoolean(KEY_DOUBLE_SPACE, v).apply()

    var showSuggestions: Boolean
        get() = sp.getBoolean(KEY_SHOW_SUGG, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_SUGG, v).apply()

    companion object {
        const val KEY_GIPHY = "giphy_key"
        const val KEY_GESTURE = "gesture_typing"
        const val KEY_GESTURE_TRAIL = "gesture_trail"
        const val KEY_AUTOCORRECT = "autocorrect"
        const val KEY_AUTOCAPS = "auto_caps"
        const val KEY_DOUBLE_SPACE = "double_space_period"
        const val KEY_SHOW_SUGG = "show_suggestions"

        const val KEY_GROQ = "groq_key"
        const val KEY_VOICE_LANG = "voice_lang"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_VIBRATION = "vibration_strength"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_MARK_TYPOS = "mark_typos"
        const val KEY_EMOJI_SUGG = "emoji_suggestions"
        const val KEY_SIZE = "key_size"
        const val KEY_BOTTOM_PAD = "bottom_pad"
        const val KEY_LAYOUT = "layout"
        const val KEY_ESZETT = "eszett_key"
        const val KEY_ONE_HANDED = "one_handed"
        const val KEY_TOUCH_ASSIST = "touch_assist"
        const val SIZE_MIN = 75
        const val SIZE_MAX = 165
        const val BOTTOM_MAX = 90
    }
}
