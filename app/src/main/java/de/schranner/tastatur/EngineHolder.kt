package de.schranner.tastatur

import android.content.Context

/**
 * Ein gemeinsames Wörterbuch für Tastatur und Rechtschreibprüfung (spart Speicher, und gelernte
 * Wörter gelten überall).
 */
object EngineHolder {
    private var engine: SuggestionEngine? = null
    private var dir: String? = null

    @Synchronized
    fun get(context: Context): SuggestionEngine {
        val app = context.applicationContext
        val path = app.filesDir.absolutePath
        engine?.let { if (dir == path) return it }
        return SuggestionEngine(app).also { it.loadAsync(); engine = it; dir = path }
    }
}
