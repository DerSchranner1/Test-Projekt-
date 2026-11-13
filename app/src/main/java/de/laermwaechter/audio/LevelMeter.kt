package de.laermwaechter.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import de.laermwaechter.core.LevelMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Takes one short sound level reading. The microphone is opened, read for
 * [WARMUP_MS] + the requested time, and released again, so it is off between readings.
 * Audio samples never leave this function: only the resulting level is returned.
 */
class LevelMeter(private val context: Context) {

    data class Reading(val db: Double, val clipped: Boolean)

    /** Blocking; call from a background thread. Returns null if the microphone is unavailable. */
    fun measure(measureMs: Int, calibrationDb: Int): Reading? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val record = open() ?: return null
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null

            val rate = record.sampleRate
            val filter = AWeighting(rate)
            val warmup = rate * WARMUP_MS / 1000
            val total = warmup + rate * measureMs / 1000
            val buffer = ShortArray(rate / 20)

            var seen = 0
            var sumSquares = 0.0
            var counted = 0
            var clippedSamples = 0
            while (seen < total) {
                val got = record.read(buffer, 0, min(buffer.size, total - seen))
                if (got <= 0) return null
                for (i in 0 until got) {
                    // The warm-up samples still run through the filter so it has settled.
                    val y = filter.process(buffer[i].toDouble())
                    if (seen + i >= warmup) {
                        sumSquares += y * y
                        counted++
                        if (abs(buffer[i].toInt()) >= CLIP_LEVEL) clippedSamples++
                    }
                }
                seen += got
            }

            // Android hands a background app silence while e.g. a phone call owns the microphone.
            val silenced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                record.activeRecordingConfiguration?.isClientSilenced == true
            if (silenced || counted == 0 || sumSquares == 0.0) return null

            val rms = sqrt(sumSquares / counted)
            return Reading(
                db = LevelMath.rmsToDb(rms, calibrationDb.toDouble()),
                clipped = clippedSamples * 1000 > counted,
            )
        } catch (e: Exception) {
            return null
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        for (rate in SAMPLE_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            try {
                // VOICE_RECOGNITION: no automatic gain control or noise suppression, and the
                // sensitivity Android's compatibility rules specify (see LevelMath).
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, rate * 2 / 5),
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    companion object {
        /** Discarded at the start of every reading while the microphone path settles. */
        const val WARMUP_MS = 200
        private const val CLIP_LEVEL = 32700
        private val SAMPLE_RATES = intArrayOf(48000, 44100)
    }
}
