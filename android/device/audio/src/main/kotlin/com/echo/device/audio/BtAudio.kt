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

/** Result of a glasses-mic capture, with a simple level read so we can confirm real audio was captured. */
data class Recording(
    val pcm: ByteArray,
    val sampleRate: Int,
    val peak: Int, // 0..32767
    val rms: Int,
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
