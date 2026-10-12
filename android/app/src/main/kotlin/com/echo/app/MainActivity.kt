package com.echo.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.echo.app.ui.AppRoot
import com.echo.app.ui.theme.JarvisTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results not needed for the dev console */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                // Wi-Fi Direct (the glasses photo sync) needs both of these — without them
                // discoverPeers() fails silently and every sync times out.
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
        )
        consumeOpenMeeting(intent)
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        consumeOpenMeeting(intent)
    }

    /** The recording notification was tapped → show the meeting screen. */
    private fun consumeOpenMeeting(i: android.content.Intent?) {
        if (i?.getBooleanExtra(com.echo.app.meeting.MeetingRecorderService.EXTRA_OPEN_MEETING, false) == true) {
            com.echo.app.meeting.MeetingNav.openRequested = true
        }
    }
}
