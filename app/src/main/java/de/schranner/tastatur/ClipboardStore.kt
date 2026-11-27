package de.schranner.tastatur

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Verlauf der Zwischenablage: merkt sich Kopiertes (außer Passwörtern) für eine Stunde,
 * angeheftete Einträge bleiben dauerhaft. Liegt nur im privaten Speicher der App.
 */
class ClipboardStore(private val context: Context) {

    class Item(val text: String, var pinned: Boolean, val time: Long)

    val items = mutableListOf<Item>()
    private val file = File(context.filesDir, "clipboard.json")
    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var onNewClip: ((Item) -> Unit)? = null

    /** Verlauf führen? Beim Abschalten wird alles Nicht-Angeheftete sofort gelöscht. */
    var enabled = true
        set(v) {
            if (field && !v) clearUnpinned()
            field = v
        }
    /** Zeitpunkt des letzten neuen Eintrags (für den „Einfügen“-Vorschlag). */
    var lastNewClipTime = 0L
        private set

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    init { load() }

    fun start() {
        try { cm.addPrimaryClipChangedListener(listener) } catch (_: Exception) { }
    }

    fun stop() {
        try { cm.removePrimaryClipChangedListener(listener) } catch (_: Exception) { }
    }

    /** Aktuellen Inhalt der Zwischenablage übernehmen (falls neu). */
    fun capture() {
        if (!enabled) return
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            if (Build.VERSION.SDK_INT >= 24 && desc.extras?.getBoolean(EXTRA_SENSITIVE) == true) return
            if (Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
            val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
            if (text.isBlank() || text.length > 20_000) return
            val existing = items.indexOfFirst { it.text == text }
            if (existing == 0) return
            var pinned = false
            if (existing > 0) pinned = items.removeAt(existing).pinned
            val item = Item(text, pinned, System.currentTimeMillis())
            items.add(0, item)
            trim()
            save()
            lastNewClipTime = item.time
            onNewClip?.invoke(item)
        } catch (_: Exception) { }
    }

    fun togglePin(item: Item) { item.pinned = !item.pinned; save() }
    fun remove(item: Item) { items.remove(item); save() }
    fun clearUnpinned() { items.removeAll { !it.pinned }; save() }
    fun markUsed() { lastNewClipTime = 0L }

    private fun trim() {
        // Wie bei Gboard: Kopiertes bleibt eine Stunde, Angeheftetes dauerhaft
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        items.removeAll { !it.pinned && it.time < cutoff }
        var unpinned = 0
        val it = items.iterator()
        while (it.hasNext()) {
            val x = it.next()
            if (!x.pinned && ++unpinned > 40) it.remove()
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += Item(o.getString("t"), o.optBoolean("p"), o.optLong("ts"))
            }
            trim()
        } catch (_: Exception) { }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (x in items) arr.put(JSONObject().put("t", x.text).put("p", x.pinned).put("ts", x.time))
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    companion object {
        const val RETENTION_MS = 3600_000L
        private const val EXTRA_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** Erkennt Codes, Links, E-Mails und Telefonnummern im kopierten Text. */
        fun smartLabel(text: String): String {
            val t = text.trim()
            return when {
                Regex("^\\d{4,8}$").matches(t) -> "🔢 Code $t"
                Regex("^https?://\\S+$").matches(t) -> "🔗 " + t.removePrefix("https://").removePrefix("http://").take(28)
                Regex("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$").matches(t) -> "✉️ $t"
                Regex("^\\+?[\\d ()/-]{6,20}$").matches(t) -> "📞 $t"
                else -> "📋 " + t.replace('\n', ' ').take(30) + if (t.length > 30) "…" else ""
            }
        }
    }
}
