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
            ),
        )
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                AppRoot()
            }
        }
    }
}
