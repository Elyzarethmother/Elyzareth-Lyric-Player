package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.ElyzarethPlayerApp
import com.example.viewmodel.LyricPlayerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: LyricPlayerViewModel by viewModels()

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.scanDeviceAudio()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ElyzarethPlayerApp(
                viewModel = viewModel,
                onRequestDeviceLibrary = { requestDeviceAudioPermission() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    private fun requestDeviceAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.scanDeviceAudio()
        } else {
            audioPermissionLauncher.launch(permission)
        }
    }
}
