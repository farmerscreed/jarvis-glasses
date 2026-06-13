package com.echo.device.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Earcon cues played in the ear so the hands-free loop never feels like a silent hang. */
enum class EarconKind { LISTENING, THINKING }

/** Result of a glasses-mic capture, with a simple level read so we can confirm real audio was captured. */
data class Recording(
    val pcm: ByteArray,
    val sampleRate: Int,
    val peak: Int, // 0..32767
    val rms: Int,
    // --- voice-quality diagnostics (Phase: voice-quality investigation 2026-06-13) ---
    // Why the VAD stopped recording: "trailingSilence" | "maxMs" | "noSpeechTimeout" | "fixed".
    val stopReason: String = "fixed",
    val noiseFloor: Int = 0, // calibrated noise RMS over the first ~250 ms
    val threshold: Int = 0,  // speech-detection threshold actually used
    val speechStarted: Boolean = true, // did any frame ever cross the threshold?
) {
    val seconds: Double get() = pcm.size / 2.0 / sampleRate
}

/**
 * Proves the glasses audio path through our app (Phase 0C):
 *  - mic in  via Bluetooth SCO (HFP)  -> AudioRecord(VOICE_COMMUNICATION)
 *  - sound out via A2DP (media)       -> AudioTrack(USAGE_MEDIA)
 * The glasses are a standard BT headset (proven in recon), so once SCO is routed the mic is theirs.
 */
class BtAudioEngine(private val context: Context) {

    private val sampleRate = 16000
    private val am: AudioManager get() = context.getSystemService(AudioManager::class.java)

    /** Record [durationMs] from the glasses mic over SCO, then play it back over A2DP. */
    suspend fun recordAndPlay(durationMs: Int = 4000): Recording {
        val rec = record(durationMs)
        delay(800) // let A2DP resume after SCO tears down
        play(rec.pcm, rec.sampleRate)
        return rec
    }

    @SuppressLint("MissingPermission")
    suspend fun record(durationMs: Int): Recording = withContext(Dispatchers.IO) {
        enableSco()
        delay(1500) // SCO link needs a moment to come up
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf,
        )
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        try {
            recorder.startRecording()
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            disableSco()
        }
        val pcm = out.toByteArray()
        val (peak, rms) = analyze(pcm)
        Recording(pcm, sampleRate, peak, rms)
    }

    /**
     * Record from the glasses mic until the user stops talking (Phase D — VAD endpointing), instead
     * of a fixed window. Energy-based: calibrate a noise floor, then stop after [silenceMs] of
     * trailing silence once speech has started. Caps at [maxMs]; if no speech is heard within
     * [noSpeechTimeoutMs], returns early. Cuts seconds off the round trip vs a fixed 5 s record.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(
        maxMs: Int = 12_000,
        silenceMs: Int = 700,
        noSpeechTimeoutMs: Int = 4_000,
    ): Recording = withContext(Dispatchers.IO) {
        enableSco()
        delay(1500) // SCO link needs a moment to come up
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf,
        )
        val out = ByteArrayOutputStream()
        val frame = ByteArray(960) // ~30 ms at 16 kHz mono 16-bit
        // voice-quality diagnostics, carried out of the try so the Recording can report them
        var stopReason = "maxMs"
        var speechEver = false
        var noiseFloorOut = 0
        var thresholdOut = 0
        try {
            recorder.startRecording()
            val start = System.currentTimeMillis()

            // Calibrate the noise floor from the first ~250 ms (before the user likely speaks).
            var noiseSum = 0.0; var noiseN = 0
            while (System.currentTimeMillis() - start < 250) {
                val n = recorder.read(frame, 0, frame.size)
                if (n > 0) { out.write(frame, 0, n); noiseSum += frameRms(frame, n); noiseN++ }
            }
            val noiseFloor = if (noiseN > 0) noiseSum / noiseN else 0.0
            val threshold = (noiseFloor * 2.5).coerceIn(600.0, 2500.0)

            var speechStarted = false
            var lastVoiceMs = start
            while (true) {
                val now = System.currentTimeMillis()
                if (now - start > maxMs) { stopReason = "maxMs"; break }
                if (!speechStarted && now - start > noSpeechTimeoutMs) { stopReason = "noSpeechTimeout"; break }
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) continue
                out.write(frame, 0, n)
                if (frameRms(frame, n) > threshold) {
                    speechStarted = true
                    lastVoiceMs = now
                } else if (speechStarted && now - lastVoiceMs > silenceMs) {
                    stopReason = "trailingSilence"; break // endpoint: trailing silence after speech
                }
            }
            speechEver = speechStarted
            noiseFloorOut = noiseFloor.toInt()
            thresholdOut = threshold.toInt()
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            disableSco()
        }
        val pcm = out.toByteArray()
        val (peak, rms) = analyze(pcm)
        Recording(pcm, sampleRate, peak, rms, stopReason, noiseFloorOut, thresholdOut, speechEver)
    }

    /** Best-effort earcon (in-ear via A2DP) so silence never reads as a hang. */
    fun earcon(kind: EarconKind) {
        runCatching {
            val tone = when (kind) {
                EarconKind.LISTENING -> android.media.ToneGenerator.TONE_PROP_BEEP
                EarconKind.THINKING -> android.media.ToneGenerator.TONE_PROP_PROMPT
            }
            val gen = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            gen.startTone(tone, 140)
            // release after the tone finishes
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { gen.release() } }, 250)
        }
    }

    private fun frameRms(buf: ByteArray, len: Int): Double {
        var sumSq = 0.0; var count = 0; var i = 0
        while (i + 1 < len) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sumSq += s.toDouble() * s; count++; i += 2
        }
        return if (count > 0) sqrt(sumSq / count) else 0.0
    }

    suspend fun play(pcm: ByteArray, rate: Int = sampleRate) = withContext(Dispatchers.IO) {
        am.mode = AudioManager.MODE_NORMAL // media route -> A2DP
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(pcm.size.coerceAtMost(1 shl 20))
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var off = 0
            while (off < pcm.size) {
                val n = track.write(pcm, off, pcm.size - off)
                if (n <= 0) break
                off += n
            }
            // let the buffer drain before tearing down
            delay((pcm.size / 2.0 / rate * 1000).toLong() + 300)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableSco() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bt = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (bt != null) am.setCommunicationDevice(bt)
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
        }
    }

    private fun disableSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun analyze(pcm: ByteArray): Pair<Int, Int> {
        var peak = 0
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val a = abs(sample)
            if (a > peak) peak = a
            sumSq += sample.toDouble() * sample
            count++
            i += 2
        }
        val rms = if (count > 0) sqrt(sumSq / count).toInt() else 0
        return peak to rms
    }
}
