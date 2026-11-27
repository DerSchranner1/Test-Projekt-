package de.schranner.tastatur

import android.content.Context

/**
 * Ein gemeinsames Wörterbuch für Tastatur und Rechtschreibprüfung (spart Speicher, und gelernte
 * Wörter gelten überall).
 */
object EngineHolder {
    private var engine: SuggestionEngine? = null
    private var dir: String? = null

    /** Liefert das Wörterbuch; wurde Bairisch in den Einstellungen umgeschaltet, wird es neu geladen. */
    @Synchronized
    fun get(context: Context): SuggestionEngine {
        val app = context.applicationContext
        val path = app.filesDir.absolutePath
        val dialect = Prefs(app).bavarian
        engine?.let { if (dir == path && it.dialect == dialect) return it }
        engine?.save()
        return SuggestionEngine(app, dialect).also { it.loadAsync(); engine = it; dir = path }
    }
}
