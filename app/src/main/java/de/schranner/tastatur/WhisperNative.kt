package de.schranner.tastatur

/** Zugang zur eingebauten Spracherkennung (whisper.cpp, läuft komplett auf dem Gerät). */
object WhisperNative {
    @Volatile private var loaded: Boolean? = null

    /** Ist die Bibliothek für dieses Gerät vorhanden? (nur 64-Bit-ARM, also praktisch alle aktuellen Handys) */
    @Synchronized
    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        return try { System.loadLibrary("klarwhisper"); true } catch (_: Throwable) { false }.also { loaded = it }
    }

    /** Für Tests auf dem Entwicklungsrechner: Bibliothek aus einer Datei laden. */
    @Synchronized
    fun loadFrom(path: String) {
        System.load(path)
        loaded = true
    }

    @JvmStatic external fun init(path: String): Long
    @JvmStatic external fun free(ctx: Long)

    /**
     * @param audio Aufnahme mit 16 kHz, Werte −1..1
     * @param language „de“, „en“ oder „auto“
     * @param audioCtx Größe des Hörfensters (0 = volle 30 Sekunden)
     * @return erkannter Text als UTF-8, oder null bei Fehler
     */
    @JvmStatic external fun transcribe(
        ctx: Long, audio: FloatArray, threads: Int, language: String, prompt: String?, audioCtx: Int,
    ): ByteArray?

    /** Kurze Aufnahmen brauchen nur einen Teil des 30-Sekunden-Fensters – das spart viel Rechenzeit. */
    fun audioCtxFor(samples: Int): Int {
        val seconds = samples / 16000f
        return (seconds / 30f * 1500f + 160f).toInt().coerceIn(384, 1500)
    }
}
