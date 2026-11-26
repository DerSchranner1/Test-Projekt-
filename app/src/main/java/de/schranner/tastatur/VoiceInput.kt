package de.schranner.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Diktieren.
 * - Offline (privat): Whisper läuft auf dem Handy, sobald das Modell geladen wurde. Nichts verlässt das Gerät.
 * - Mit (kostenlosem) Groq-Schlüssel: Erkennung mit Whisper large-v3 im Netz – am genauesten.
 * - Ohne Schlüssel: die Spracherkennung des Handys – wenn möglich auf dem Gerät, als Dauerdiktat
 *   mit Zwischenergebnissen und automatischen Satzzeichen.
 */
class VoiceInput(private val context: Context, private val prefs: Prefs) {

    interface Callback {
        fun onListening(engineName: String)
        fun onLevel(level: Float)
        /** Zwischenstand, während noch gesprochen wird. */
        fun onPartial(text: String)
        /** Ein fertiger Abschnitt; es wird weiter zugehört (Dauerdiktat). */
        fun onSegment(text: String)
        fun onProcessing()
        fun onResult(text: String)
        /** Das schnelle Ergebnis kam schon, das genaue wird gerade berechnet. */
        fun onRefining() {}
        /** Genaueres Ergebnis zum zuletzt eingefügten Text (null = nichts Besseres gefunden). */
        fun onRefined(text: String?) {}
        fun onError(message: String)
        fun onIdle()
    }

    var callback: Callback? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var recording = false
    private var recordThread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    var vocabulary: List<String> = emptyList()

    val isActive: Boolean get() = recording || recognizer != null

    enum class Engine { OFFLINE, GROQ, ANDROID }

    val offline = OfflineWhisper(context)

    /**
     * Welche Erkennung wird benutzt?
     * 1. Groq, wenn eingeschaltet, ein Schlüssel da ist und Internet besteht
     * 2. sonst auf dem Handy (offline) – erst schnell, dann genau
     * 3. sonst Groq (falls Schlüssel und Internet, aber kein Modell), zuletzt die Erkennung des Handys
     */
    fun engine(): Engine {
        val hasKey = prefs.groqKey.isNotBlank()
        val net = hasKey && isOnline()
        return when {
            prefs.useGroq && net -> Engine.GROQ
            offline.ready() -> Engine.OFFLINE
            net -> Engine.GROQ
            else -> Engine.ANDROID
        }
    }

    /** Gibt es gerade eine funktionierende Internetverbindung? */
    fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { true }

    /**
     * Auf dem Handy erkennen. Mit beiden Modellen und „erst schnell, dann genau“:
     * das schnelle Ergebnis sofort zeigen, das genaue danach nachreichen.
     * @return das (erste) Ergebnis
     */
    private fun transcribeOffline(pcm: ByteArray, onFast: ((String) -> Unit)? = null): String? {
        val language = if (prefs.voiceLanguage == "auto") "auto" else prefs.voiceLanguage
        val prompt = buildPrompt()
        if (prefs.voiceTwoPass && offline.bothInstalled() && onFast != null) {
            val fast = offline.transcribe(pcm, language, prompt, OfflineWhisper.Model.BASE)
            if (fast != null) {
                onFast(fast)
                return offline.transcribe(pcm, language, prompt, OfflineWhisper.Model.SMALL)
            }
        }
        return offline.transcribe(pcm, language, prompt)
    }

    fun useWhisper() = engine() != Engine.ANDROID

    fun start() {
        if (isActive) return
        when (val e = engine()) {
            Engine.ANDROID -> startAndroid()
            else -> startWhisper(e)
        }
    }

    /** Aufnahme beenden – bei Whisper wird danach erkannt. */
    fun stop() {
        if (recording) recording = false
        recognizer?.stopListening()
    }

    fun cancel() {
        recording = false
        recordThread = null
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
        cancelled = true
        callback?.onIdle()
    }

    @Volatile private var cancelled = false

    // ------------------------------------------------------------- Whisper (offline oder Groq)

    @SuppressLint("MissingPermission")
    private fun startWhisper(engine: Engine) {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        } catch (e: Exception) {
            callback?.onError("Mikrofon nicht verfügbar"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); callback?.onError("Mikrofon nicht verfügbar"); return
        }
        recording = true
        cancelled = false
        callback?.onListening("Whisper")
        recordThread = Thread {
            val pcm = ByteArrayOutputStream()
            val buf = ShortArray(1600)
            var peak = 0.0
            val maxSamples = rate * 60 * 5 // höchstens 5 Minuten
            var total = 0
            var spoken = 0          // Abschnitte mit Sprache
            var quiet = 0           // Stille in Folge (je 0,1 s)
            try {
                rec.startRecording()
                while (recording && total < maxSamples) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        sum += s * s
                        pcm.write(s and 0xff); pcm.write((s shr 8) and 0xff)
                    }
                    total += n
                    val rms = sqrt(sum / n)
                    if (rms > peak) peak = rms
                    // Nach dem Sprechen und 2,5 Sekunden Stille von selbst aufhören
                    if (rms > 700) { spoken++; quiet = 0 } else if (rms < 350) quiet++
                    if (spoken >= 4 && quiet >= 25) recording = false
                    val level = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()
                    main.post { callback?.onLevel(level) }
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) { }
                rec.release()
                recording = false
            }
            if (cancelled) return@Thread
            if (total < rate / 3 || peak < 350) {
                main.post { callback?.onError("Nichts gehört – bitte nochmal") }
                return@Thread
            }
            main.post { callback?.onProcessing() }
            // Zweistufig: Das schnelle Ergebnis geht sofort raus, das genaue kommt als Verbesserung
            var fastSent = false
            val onFast: (String) -> Unit = { fast ->
                val clean = cleanWhisper(fast)
                if (clean.isNotEmpty() && !cancelled) {
                    fastSent = true
                    main.post { callback?.onResult(clean); callback?.onRefining() }
                }
            }
            val result = try {
                if (engine == Engine.OFFLINE) {
                    // Auf dem Gerät – die Aufnahme bleibt im Arbeitsspeicher und wird danach verworfen
                    transcribeOffline(pcm.toByteArray(), onFast) ?: throw IllegalStateException("offline")
                } else {
                    try {
                        transcribe(wav(pcm.toByteArray(), rate))
                    } catch (net: java.io.IOException) {
                        // Kein Internet: mit der Erkennung auf dem Handy weitermachen, wenn sie da ist
                        if (!offline.ready()) throw net
                        transcribeOffline(pcm.toByteArray(), onFast) ?: throw net
                    }
                }
            } catch (e: Exception) {
                if (fastSent) { main.post { callback?.onRefined(null) }; return@Thread }
                main.post { callback?.onError(if (engine == Engine.OFFLINE) "Offline-Erkennung fehlgeschlagen" else friendlyError(e)) }
                return@Thread
            }
            if (cancelled) return@Thread
            val clean = cleanWhisper(result)
            if (fastSent) {
                main.post { callback?.onRefined(clean.ifEmpty { null }) }
                return@Thread
            }
            main.post {
                if (clean.isEmpty()) callback?.onError("Nichts erkannt – bitte nochmal")
                else callback?.onResult(clean)
            }
        }.also { it.start() }
    }

    private fun friendlyError(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("401") -> "Groq-Schlüssel ungültig – bitte in den Einstellungen prüfen"
            m.contains("429") -> "Tageslimit erreicht – später nochmal versuchen"
            e is java.net.UnknownHostException -> "Kein Internet"
            e is java.net.SocketTimeoutException -> "Zeitüberschreitung – Internet langsam?"
            else -> "Fehler bei der Erkennung"
        }
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun short(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private fun transcribe(wav: ByteArray): String {
        val boundary = "----KlarTastatur" + System.nanoTime()
        val conn = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", "Bearer ${prefs.groqKey}")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(conn.outputStream).use { o ->
            fun field(name: String, value: String) {
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", "whisper-large-v3")
            field("response_format", "json")
            field("temperature", "0")
            if (prefs.voiceLanguage != "auto") field("language", prefs.voiceLanguage)
            field("prompt", buildPrompt())
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            o.write(wav)
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("HTTP $code $err")
        }
        val body = conn.inputStream.bufferedReader().readText()
        return JSONObject(body).optString("text", "")
    }

    /** Der Prompt gibt Whisper Stil und eigene Wörter (Namen, Fachbegriffe) vor. */
    private fun buildPrompt(): String {
        val base = "Hallo, das ist ein Diktat mit korrekter Rechtschreibung, Groß- und Kleinschreibung und Satzzeichen."
        if (vocabulary.isEmpty()) return base
        val words = vocabulary.take(60).joinToString(", ")
        return "$base Wörter: $words."
    }

    private fun cleanWhisper(raw: String): String {
        var t = raw.trim()
        // Bekannte „Halluzinationen“ von Whisper bei Stille entfernen
        // (nur wenn sie allein stehen oder am Ende angehängt sind)
        for (h in HALLUCINATIONS) {
            if (t.endsWith(h, ignoreCase = true)) t = t.dropLast(h.length).trim()
            if (t.startsWith(h, ignoreCase = true) && h.contains("Untertitel")) t = t.drop(h.length).trim()
        }
        return t
    }

    // ------------------------------------------------------------- Android-Erkennung

    private var gotAnything = false

    /**
     * Versuch 0: Erkennung auf dem Gerät als Dauerdiktat mit automatischen Satzzeichen (Android 13+).
     * Versuch 1: normale Erkennung mit automatischen Satzzeichen.
     * Versuch 2: normale Erkennung ohne Extras.
     * Unterstützt das Handy eine Stufe nicht, wird automatisch die nächste genommen.
     */
    private fun startAndroid(attempt: Int = 0) {
        gotAnything = false
        val onDevice = attempt == 0 && Build.VERSION.SDK_INT >= 33 &&
            try { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } catch (_: Exception) { false }
        if (attempt == 0 && !onDevice) { startAndroid(1); return }
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            callback?.onError("Auf diesem Handy ist keine Spracherkennung installiert")
            return
        }
        cancelled = false
        val sr = try {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            if (attempt < 2) startAndroid(attempt + 1) else callback?.onError("Spracherkennung fehlgeschlagen")
            return
        }
        recognizer = sr
        fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { callback?.onListening("Android") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { callback?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (!onDevice) callback?.onProcessing() }
            override fun onError(error: Int) {
                finishAndroid()
                if (cancelled) return
                val heardNothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val hopeless = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_AUDIO
                if (!gotAnything && !heardNothing && !hopeless && attempt < 2) {
                    startAndroid(attempt + 1)
                    return
                }
                if (gotAnything && heardNothing) { callback?.onIdle(); return }
                callback?.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nichts erkannt – bitte nochmal"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Kein Internet"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon nicht erlaubt"
                        else -> "Spracherkennung fehlgeschlagen"
                    },
                )
            }
            override fun onResults(results: Bundle?) {
                val text = best(results)
                finishAndroid()
                when {
                    text.isNotBlank() -> callback?.onResult(text)
                    gotAnything -> callback?.onIdle()
                    else -> callback?.onError("Nichts erkannt – bitte nochmal")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = best(partialResults)
                if (text.isNotBlank()) { gotAnything = true; callback?.onPartial(text) }
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                val text = best(segmentResults)
                if (text.isNotBlank()) { gotAnything = true; callback?.onSegment(text) }
            }
            override fun onEndOfSegmentedSession() {
                finishAndroid()
                callback?.onIdle()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            val silence = if (onDevice) 6000L else 2500L
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            if (attempt < 2 && Build.VERSION.SDK_INT >= 33) {
                // Satzzeichen und Großschreibung automatisch; eigene Wörter (Namen) bevorzugen
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                if (vocabulary.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(vocabulary.take(40)))
                }
                if (onDevice) {
                    // Dauerdiktat: Pausen beenden die Aufnahme nicht, Abschnitte kommen einzeln an
                    putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    )
                }
            }
        }
        try { sr.startListening(intent) } catch (e: Exception) {
            finishAndroid()
            if (attempt < 2) startAndroid(attempt + 1) else callback?.onError("Spracherkennung fehlgeschlagen")
        }
    }

    private fun finishAndroid() {
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private val HALLUCINATIONS = listOf(
            "Untertitel im Auftrag des ZDF für funk, 2017",
            "Untertitel im Auftrag des ZDF, 2017",
            "Untertitel im Auftrag des ZDF, 2018",
            "Untertitel im Auftrag des ZDF, 2020",
            "Untertitel im Auftrag des ZDF, 2021",
            "Untertitel im Auftrag des ZDF",
            "Untertitelung im Auftrag des ZDF",
            "Untertitel der Amara.org-Community",
            "Untertitel von Stephanie Geiges",
            "Vielen Dank fürs Zuschauen!",
            "Vielen Dank für's Zuschauen!",
            "Bis zum nächsten Mal.",
            "Copyright WDR 2021",
            "SWR 2021",
        )
    }
}
