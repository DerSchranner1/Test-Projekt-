package de.schranner.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * - Mit (kostenlosem) Groq-Schlüssel: Aufnahme auf dem Handy, Erkennung mit Whisper large-v3 –
 *   deutlich genauer als die eingebaute Erkennung, inkl. Satzzeichen.
 * - Ohne Schlüssel: Android-Spracherkennung als Ersatz.
 */
class VoiceInput(private val context: Context, private val prefs: Prefs) {

    interface Callback {
        fun onListening(engineName: String)
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onProcessing()
        fun onResult(text: String)
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

    fun useWhisper() = prefs.groqKey.isNotBlank()

    fun start() {
        if (isActive) return
        if (useWhisper()) startWhisper() else startAndroid()
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

    // ------------------------------------------------------------- Whisper (Groq)

    @SuppressLint("MissingPermission")
    private fun startWhisper() {
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
            val result = try {
                transcribe(wav(pcm.toByteArray(), rate))
            } catch (e: Exception) {
                main.post { callback?.onError(friendlyError(e)) }
                return@Thread
            }
            if (cancelled) return@Thread
            val clean = cleanWhisper(result)
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

    private fun startAndroid() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback?.onError("Keine Spracherkennung verfügbar – Groq-Schlüssel eintragen")
            return
        }
        cancelled = false
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { callback?.onListening("Android") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { callback?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { callback?.onProcessing() }
            override fun onError(error: Int) {
                finishAndroid()
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
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                finishAndroid()
                if (text.isBlank()) callback?.onError("Nichts erkannt – bitte nochmal") else callback?.onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { callback?.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (prefs.voiceLanguage == "auto") "de-DE" else "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
        try { sr.startListening(intent) } catch (e: Exception) {
            finishAndroid(); callback?.onError("Spracherkennung fehlgeschlagen")
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
