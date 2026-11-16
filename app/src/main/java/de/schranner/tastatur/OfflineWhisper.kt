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
    private var ctx = 0L
    private var loaded: Model? = null
    private val releaseLater = Runnable { release() }

    fun file(m: Model) = File(dir, m.file)
    fun partFile(m: Model) = File(dir, m.file + ".part")
    fun isInstalled(m: Model) = file(m).let { it.exists() && it.length() == m.bytes }

    /** Das beste vorhandene Modell („Genau“ vor „Schnell“). */
    fun installed(): Model? = Model.entries.firstOrNull { isInstalled(it) }

    /** Kann dieses Gerät die Offline-Erkennung ausführen und ist ein Modell da? */
    fun ready(): Boolean = installed() != null && WhisperNative.ensureLoaded()

    fun delete(m: Model) {
        if (loaded == m) release()
        file(m).delete()
        partFile(m).delete()
    }

    /**
     * @param pcm Aufnahme: 16 kHz, 16 Bit, mono (little endian)
     * @return erkannter Text, oder null wenn die Erkennung nicht möglich war
     */
    @Synchronized
    fun transcribe(pcm: ByteArray, language: String, prompt: String?): String? {
        val model = installed() ?: return null
        if (!WhisperNative.ensureLoaded()) return null
        main.removeCallbacks(releaseLater)
        if (ctx == 0L || loaded != model) {
            if (ctx != 0L) WhisperNative.free(ctx)
            ctx = WhisperNative.init(file(model).absolutePath)
            loaded = if (ctx != 0L) model else null
            if (ctx == 0L) return null
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
        if (ctx != 0L) WhisperNative.free(ctx)
        ctx = 0L
        loaded = null
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
            main.post { onChange?.invoke() }
        }.start()
    }

    fun cancel() { cancel = true }
}
