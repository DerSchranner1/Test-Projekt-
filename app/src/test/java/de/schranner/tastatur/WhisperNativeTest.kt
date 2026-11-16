package de.schranner.tastatur

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lässt die Offline-Spracherkennung auf dem Entwicklungsrechner laufen.
 * Braucht eine für den Rechner gebaute Bibliothek und ein Modell (siehe README); sonst wird der Test übersprungen:
 *   KLAR_WHISPER_LIB=/pfad/libklarwhisper.so KLAR_WHISPER_MODEL=/pfad/ggml-base-q5_1.bin KLAR_WHISPER_WAV=/pfad/dir
 */
class WhisperNativeTest {

    private fun wav(file: File): FloatArray {
        val b = file.readBytes()
        // „data“-Abschnitt suchen, 16 Bit mono 16 kHz erwartet
        var i = 12
        while (i + 8 < b.size && !(b[i] == 'd'.code.toByte() && b[i + 1] == 'a'.code.toByte() && b[i + 2] == 't'.code.toByte() && b[i + 3] == 'a'.code.toByte())) {
            i += 8 + ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val start = i + 8
        val n = (b.size - start) / 2
        val bb = ByteBuffer.wrap(b, start, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { bb.short / 32768f }
    }

    @Test fun transcribesSpeech() {
        val lib = System.getenv("KLAR_WHISPER_LIB")
        val model = System.getenv("KLAR_WHISPER_MODEL")
        val dir = System.getenv("KLAR_WHISPER_WAV")
        assumeTrue(lib != null && model != null && dir != null && File(lib).exists() && File(model).exists())
        WhisperNative.loadFrom(lib!!)
        val ctx = WhisperNative.init(model!!)
        assertTrue(ctx != 0L)
        for (f in File(dir!!).listFiles { x -> x.name.endsWith(".wav") }!!.sortedBy { it.name }) {
            val audio = wav(f)
            val lang = if (f.name.startsWith("de")) "de" else "en"
            val t = System.nanoTime()
            val text = WhisperNative.transcribe(ctx, audio, 4, lang, null, WhisperNative.audioCtxFor(audio.size))
                ?.toString(Charsets.UTF_8)?.trim()
            val ms = (System.nanoTime() - t) / 1_000_000
            println("WHISPER ${f.name} (${audio.size / 16000}s, $ms ms): $text")
            assertTrue(!text.isNullOrBlank())
            val expected = File(f.path.removeSuffix(".wav") + ".txt").takeIf { it.exists() }?.readText()?.trim()
            if (expected != null) {
                val want = expected.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
                val got = text!!.lowercase().split(Regex("[^\\p{L}]+")).toSet()
                val hit = want.count { it in got }
                println("WHISPER   erwartet: $expected  -> $hit/${want.size} Wörter erkannt")
                assertTrue(hit >= want.size * 0.7)
            }
        }
        WhisperNative.free(ctx)
    }
}
