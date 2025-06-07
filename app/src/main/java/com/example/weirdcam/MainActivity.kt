package com.example.weirdcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.weirdcam.ui.theme.WeirdcamTheme

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
            hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (hasCameraPermission && hasAudioPermission) {
                Log.d("WeirdCam", "Kamera ve Ses Kaydı izinleri verildi.")
            } else {
                Log.d("WeirdCam", "Bir veya daha fazla izin reddedildi. Kamera: $hasCameraPermission, Ses: $hasAudioPermission")
            }
        }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updatePermissionStatus()

        setContent {
            if (!hasCameraPermission || !hasAudioPermission) {
                LaunchedEffect(Unit) {
                    val permissionsToRequest = mutableListOf<String>()
                    if (!hasCameraPermission) {
                        permissionsToRequest.add(Manifest.permission.CAMERA)
                    }
                    if (!hasAudioPermission) {
                        permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (permissionsToRequest.isNotEmpty()) {
                        requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                }
            }

            AppContent(
                hasCameraPermission = hasCameraPermission,
                hasAudioPermission = hasAudioPermission
            )
        }
    }

    private fun updatePermissionStatus() {
        hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }
}

@Composable
fun AppContent(
    hasCameraPermission: Boolean,
    hasAudioPermission: Boolean,
    modifier: Modifier = Modifier
) {
    WeirdcamTheme {
        Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
            if (hasCameraPermission && hasAudioPermission) {
                // Burada CameraWithControls çağrısı en basit haliyle duruyor
                CameraWithControls(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                )
            } else {
                Text(
                    text = "Kamera ve ses izni gereklidir. Lütfen ayarlardan izin verin veya uygulamayı yeniden başlatın.",
                    modifier = Modifier.padding(innerPadding).padding(16.dp)
                )
            }
        }
    }
}