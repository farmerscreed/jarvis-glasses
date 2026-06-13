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
     * Record from the glasses mic until the user stops talking (VAD endpointing). Reworked after the
     * 2026-06-13 voice-quality investigation, which found the old endpointer was the dominant failure
     * (cut speech off on natural pauses; a fragile one-shot noise calibration missed speech entirely;
     * no audible cue ever reached the ear; the blind warm-up clipped word onsets). The fixes:
     *  - **SCO-hot gating + warm-up flush:** wait for the link, then read & DISCARD ramp-up frames
     *    until real audio is flowing, so the first word isn't lost to a cold mic.
     *  - **Audible "listening" cue over the SCO route** ([cueListening]) — plays in the glasses
     *    speaker the moment the mic is live, so the user knows when to speak. Played before recording
     *    starts so it isn't captured.
     *  - **Robust noise floor:** a low-percentile estimate over the calibration window (resistant to
     *    the user speaking early) plus a rolling update during trailing silence — no more one-shot
     *    250 ms reading that caps the threshold and goes deaf.
     *  - **Speech-start debounce + long trailing silence** ([silenceMs] default 1.5 s) so a noise blip
     *    doesn't false-start and natural pauses no longer end the turn mid-sentence.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(
        maxMs: Int = 15_000,
        silenceMs: Int = 1_500,
        noSpeechTimeoutMs: Int = 7_000,
    ): Recording = withContext(Dispatchers.IO) {
        enableSco()
        delay(900) // let the SCO link come fully up before the cue, so the beep isn't clipped/dropped
        // The mic is live now: cue the user in their ear (over SCO) BEFORE we start capturing, so the
        // beep isn't recorded. This is the "speak now" signal the old ToneGenerator earcon never gave.
        cueListening()
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

            // Warm-up flush: drop frames until audio is actually flowing (non-trivial energy) or a
            // short cap elapses. DISCARDED (not written to out) so SCO ramp garbage can't corrupt the
            // noise floor or be mistaken for speech. Onset is preserved because the mic is hot after.
            run {
                val flushEnd = System.currentTimeMillis() + 600
                var stableFlowing = 0
                while (System.currentTimeMillis() < flushEnd) {
                    val n = recorder.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    if (frameRms(frame, n) > 1.0) { if (++stableFlowing >= 3) break } else stableFlowing = 0
                }
            }

            // Robust noise calibration: gather ~350 ms of frames and take a low percentile as the
            // floor (so a user who speaks early doesn't inflate it). These frames ARE real audio → keep.
            val start = System.currentTimeMillis()
            val calib = ArrayList<Double>()
            while (System.currentTimeMillis() - start < 350) {
                val n = recorder.read(frame, 0, frame.size)
                if (n > 0) { out.write(frame, 0, n); calib.add(frameRms(frame, n)) }
            }
            var noiseFloor = percentile(calib, 0.3).coerceAtLeast(1.0)
            fun threshold() = (noiseFloor * 2.5).coerceIn(500.0, 2200.0)

            // Speech-start debounce: require a few consecutive voiced frames (~120 ms) so a single
            // blip can't both start and end an "utterance".
            val startFrames = 4
            var voicedRun = 0
            var speechStarted = false
            var lastVoiceMs = start
            while (true) {
                val now = System.currentTimeMillis()
                if (now - start > maxMs) { stopReason = "maxMs"; break }
                if (!speechStarted && now - start > noSpeechTimeoutMs) { stopReason = "noSpeechTimeout"; break }
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) continue
                out.write(frame, 0, n)
                val level = frameRms(frame, n)
                if (level > threshold()) {
                    voicedRun++
                    if (!speechStarted && voicedRun >= startFrames) speechStarted = true
                    if (speechStarted) lastVoiceMs = now
                } else {
                    voicedRun = 0
                    if (!speechStarted) {
                        noiseFloor = noiseFloor * 0.9 + level * 0.1 // rolling: adapt to the room
                    } else if (now - lastVoiceMs > silenceMs) {
                        stopReason = "trailingSilence"; break // real pause after speech → endpoint
                    }
                }
            }
            speechEver = speechStarted
            noiseFloorOut = noiseFloor.toInt()
            thresholdOut = threshold().toInt()
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            disableSco()
        }
        val pcm = out.toByteArray()
        val (peak, rms) = analyze(pcm)
        Recording(pcm, sampleRate, peak, rms, stopReason, noiseFloorOut, thresholdOut, speechEver)
    }

    /** Low-percentile of a sample list (0..1); robust noise-floor estimate. */
    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /**
     * Short "listening" beep played into the glasses over the SCO/communication route (the only
     * output path live during a recording — the old A2DP ToneGenerator earcon never reached the ear).
     * Blocks until the tone finishes so it isn't captured by the recorder that starts right after.
     */
    private suspend fun cueListening() {
        runCatching {
            // Two rising beeps (880→1320 Hz) so the cue is unmistakable, near full-scale amplitude —
            // the SCO call channel is quiet on these glasses, so a faint tone goes unheard.
            val ms = 260
            val n = sampleRate * ms / 1000
            val pcm = ShortArray(n)
            val fade = (sampleRate * 8 / 1000).coerceAtLeast(1)
            val gapLo = n * 46 / 100; val gapHi = n * 54 / 100 // brief silence splits it into two beeps
            for (i in 0 until n) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                val gate = if (i in gapLo until gapHi) 0.0 else 1.0
                val freq = if (i < n / 2) 880.0 else 1320.0
                pcm[i] = (kotlin.math.sin(2 * kotlin.math.PI * freq * i / sampleRate) * 22000 * env * gate).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC) // short one-shot: fill buffer THEN play (no underrun)
                .build()
            try {
                track.write(pcm, 0, n) // load the static buffer before playing so the start isn't dropped
                track.play()
                delay((ms + 180).toLong()) // let the tone fully drain before the recorder opens
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
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
