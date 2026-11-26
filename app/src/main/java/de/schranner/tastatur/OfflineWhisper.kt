package de.schranner.tastatur

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spracherkennung komplett auf dem Handy (Whisper über whisper.cpp): kein Konto, kein Schlüssel,
 * die Aufnahme verlässt das Gerät nicht. Das Modell wird einmal heruntergeladen.
 */
class OfflineWhisper(context: Context) {

    enum class Model(val file: String, val bytes: Long, val title: String, val size: String) {
        SMALL("ggml-small-q5_1.bin", 190_085_487L, "Genau", "190 MB"),
        BASE("ggml-base-q5_1.bin", 59_707_625L, "Schnell", "60 MB"),
    }

    private val dir = File(context.applicationContext.filesDir, "whisper").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    /** Geladene Modelle (beide können gleichzeitig bereitstehen: erst schnell, dann genau). */
    private val contexts = HashMap<Model, Long>()
    private val releaseLater = Runnable { release() }

    fun file(m: Model) = File(dir, m.file)
    fun partFile(m: Model) = File(dir, m.file + ".part")
    fun isInstalled(m: Model) = file(m).let { it.exists() && it.length() == m.bytes }

    /** Das beste vorhandene Modell („Genau“ vor „Schnell“). */
    fun installed(): Model? = Model.entries.firstOrNull { isInstalled(it) }

    /** Sind beide Modelle da (für „erst schnell, dann genau“)? */
    fun bothInstalled(): Boolean = Model.entries.all { isInstalled(it) }

    /** Kann dieses Gerät die Offline-Erkennung ausführen und ist ein Modell da? */
    fun ready(): Boolean = installed() != null && WhisperNative.ensureLoaded()

    fun delete(m: Model) {
        release()
        file(m).delete()
        partFile(m).delete()
    }

    /**
     * @param pcm Aufnahme: 16 kHz, 16 Bit, mono (little endian)
     * @return erkannter Text, oder null wenn die Erkennung nicht möglich war
     */
    @Synchronized
    fun transcribe(pcm: ByteArray, language: String, prompt: String?, which: Model? = null): String? {
        val model = which?.takeIf { isInstalled(it) } ?: installed() ?: return null
        if (!WhisperNative.ensureLoaded()) return null
        main.removeCallbacks(releaseLater)
        var ctx = contexts[model] ?: 0L
        if (ctx == 0L) {
            ctx = WhisperNative.init(file(model).absolutePath)
            if (ctx == 0L) return null
            contexts[model] = ctx
        }
        val n = pcm.size / 2
        val audio = FloatArray(n) { i -> ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)) / 32768f }
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (cores - 2).coerceIn(2, 4)
        val bytes = WhisperNative.transcribe(ctx, audio, threads, language, prompt, WhisperNative.audioCtxFor(n))
        // Das Modell braucht viel Arbeitsspeicher: zwei Minuten nach dem letzten Diktat wieder freigeben
        main.postDelayed(releaseLater, 120_000)
        return bytes?.toString(Charsets.UTF_8)
    }

    @Synchronized
    fun release() {
        main.removeCallbacks(releaseLater)
        for (c in contexts.values) if (c != 0L) WhisperNative.free(c)
        contexts.clear()
    }

    /**
     * Modell herunterladen (setzt einen abgebrochenen Download fort).
     * @return null bei Erfolg, sonst eine Fehlermeldung
     */
    fun download(m: Model, progress: (Long) -> Unit, cancelled: () -> Boolean): String? {
        val part = partFile(m)
        return try {
            var have = if (part.exists()) part.length() else 0L
            if (have >= m.bytes) { part.delete(); have = 0L }
            val c = URL(BASE_URL + m.file).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 200) have = 0L else if (code != 206) return "Download nicht möglich (Fehler $code)"
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) return "Abgebrochen"
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        have += r
                        progress(have)
                    }
                }
            }
            if (part.length() != m.bytes) return "Download unvollständig – bitte noch einmal antippen"
            file(m).delete()
            if (!part.renameTo(file(m))) return "Konnte das Modell nicht speichern"
            null
        } catch (e: java.net.UnknownHostException) {
            "Kein Internet"
        } catch (e: Exception) {
            "Download unterbrochen – bitte noch einmal antippen"
        }
    }

    companion object {
        const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    }
}

/** Laufender Modell-Download – überlebt das Schließen der Einstellungsseite. */
object ModelDownload {
    @Volatile var model: OfflineWhisper.Model? = null
        private set
    @Volatile var done = 0L
        private set
    @Volatile var message: String? = null
        private set
    @Volatile private var cancel = false
    @Volatile var onChange: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastNotify = 0L

    val running: Boolean get() = model != null
    /** Modelle, die nach dem aktuellen noch geladen werden. */
    private val queue = ArrayDeque<OfflineWhisper.Model>()

    /** Mehrere Modelle nacheinander laden (erst „Schnell“, dann „Genau“). */
    fun startAll(context: Context, models: List<OfflineWhisper.Model>) {
        val app = context.applicationContext
        val todo = models.filter { !OfflineWhisper(app).isInstalled(it) }
        if (todo.isEmpty()) return
        queue.clear()
        queue.addAll(todo.drop(1))
        start(app, todo.first())
    }

    fun start(context: Context, m: OfflineWhisper.Model) {
        if (running) return
        model = m
        done = OfflineWhisper(context).partFile(m).let { if (it.exists()) it.length() else 0L }
        message = null
        cancel = false
        val whisper = OfflineWhisper(context)
        Thread {
            val error = whisper.download(m, { d ->
                done = d
                val now = System.currentTimeMillis()
                if (now - lastNotify > 400) { lastNotify = now; main.post { onChange?.invoke() } }
            }, { cancel })
            message = error ?: "Modell „${m.title}“ ist bereit ✓"
            model = null
            val next = if (error == null) queue.removeFirstOrNull() else null.also { queue.clear() }
            main.post {
                if (next != null) start(context, next)
                onChange?.invoke()
            }
        }.start()
    }

    fun cancel() { cancel = true; queue.clear() }
}
