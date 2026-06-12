package com.echo.app.ui.components

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Whether the glasses currently own the phone's voice path (Bluetooth SCO input present).
 * This is the link that matters for talk/TTS — the BLE control link is separate and mostly
 * idle. Read-only AudioManager poll; no engine involvement.
 */
@Composable
fun rememberGlassesAudioConnected(): Boolean {
    val ctx = LocalContext.current.applicationContext
    var connected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        while (true) {
            connected = am.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            delay(2000)
        }
    }
    return connected
}
