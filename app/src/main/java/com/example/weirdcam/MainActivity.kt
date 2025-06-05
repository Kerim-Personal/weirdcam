package com.example.weirdcam
import androidx.compose.ui.unit.dp // Bu satırı ekleyin
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
import androidx.core.content.ContextCompat
import com.example.weirdcam.ui.theme.WeirdcamTheme

class MainActivity : ComponentActivity() {

    // İzin durumlarını tutacak state'ler
    private var hasCameraPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)

    // Birden fazla izin sonucunu işlemek için bir başlatıcı (launcher)
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
            hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (hasCameraPermission && hasAudioPermission) {
                Log.d("WeirdCam", "Kamera ve Ses Kaydı izinleri verildi.")
            } else {
                Log.d("WeirdCam", "Bir veya daha fazla izin reddedildi. Kamera: $hasCameraPermission, Ses: $hasAudioPermission")
                // İsteğe bağlı: Kullanıcıya neden izinlerin gerekli olduğunu açıklayan bir mesaj gösterilebilir.
            }
        }

    @RequiresApi(Build.VERSION_CODES.M) // shouldShowRequestPermissionRationale için M gerekli
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Başlangıçta izin durumlarını kontrol et
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
                        // İzinlerden herhangi biri için açıklama gösterilmesi gerekip gerekmediğini kontrol et
                        // Basitlik adına, herhangi biri için gerekliyse hepsini istiyoruz.
                        // Daha detaylı bir mantık kurulabilir.
                        val shouldShowRationaleCamera = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                        val shouldShowRationaleAudio = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

                        if (shouldShowRationaleCamera || shouldShowRationaleAudio) {
                            Log.d("WeirdCam", "İzinler için açıklama gösterilmeli (bu adımda direkt isteniyor).")
                            // Burada kullanıcıya neden izin istediğinizi açıklayan bir diyalog gösterebilirsiniz.
                            // Şimdilik direkt izin istiyoruz.
                            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                        } else {
                            Log.d("WeirdCam", "İzinler isteniyor: $permissionsToRequest")
                            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                        }
                    }
                }
            }

            AppContent(
                hasCameraPermission = hasCameraPermission,
                hasAudioPermission = hasAudioPermission,
                onRequestPermissions = { // Yeniden izin istemek için bir lambda
                    val permissionsToRequestAgain = mutableListOf<String>()
                    if (!hasCameraPermission) permissionsToRequestAgain.add(Manifest.permission.CAMERA)
                    if (!hasAudioPermission) permissionsToRequestAgain.add(Manifest.permission.RECORD_AUDIO)
                    if (permissionsToRequestAgain.isNotEmpty()) {
                        requestMultiplePermissionsLauncher.launch(permissionsToRequestAgain.toTypedArray())
                    }
                }
            )
        }
    }

    private fun updatePermissionStatus() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d("WeirdCam", "İzin durumu güncellendi - Kamera: $hasCameraPermission, Ses: $hasAudioPermission")
    }

    // Uygulama odaktan çıkıp geri geldiğinde izinleri tekrar kontrol etmek isteyebilirsiniz
    override fun onResume() {
        super.onResume()
        updatePermissionStatus() // Özellikle ayarlardan izin değiştirildiyse durumu güncellemek için.
    }
}

// İçeriği göstermek için ayrı bir Composable fonksiyon
@Composable
fun AppContent(
    hasCameraPermission: Boolean,
    hasAudioPermission: Boolean,
    onRequestPermissions: () -> Unit, // İzinleri yeniden istemek için callback
    modifier: Modifier = Modifier
) {
    WeirdcamTheme {
        Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
            if (hasCameraPermission && hasAudioPermission) {
                // Önceki yanıtta oluşturduğumuz CameraWithControls composable'ını burada kullanacağız.
                // Bu composable'ın CameraPreview.kt veya benzer bir dosyada tanımlı olduğunu varsayıyoruz.
                CameraWithControls(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                )
            } else {
                // İzin verilmediyse veya bekleniyorsa kullanıcıya bir mesaj göster
                var message = ""
                if (!hasCameraPermission && !hasAudioPermission) {
                    message = "Kamera ve ses kaydı izinleri verilmedi. Uygulamanın tam olarak çalışması için bu izinler gereklidir."
                } else if (!hasCameraPermission) {
                    message = "Kamera izni verilmedi. Uygulamanın çalışması için kamera izni gereklidir."
                } else if (!hasAudioPermission) {
                    message = "Ses kaydı izni verilmedi. Video kaydı için bu izin gereklidir."
                } else {
                    message = "İzinler bekleniyor veya bir sorun oluştu..." // Bu durum nadir olmalı
                }

                // Kullanıcıya eksik izinleri tekrar istemesi için bir seçenek sunabiliriz (opsiyonel)
                // Örneğin bir buton ile onRequestPermissions() çağrılabilir.
                // Şimdilik sadece mesaj gösteriyoruz.
                Text(
                    text = "$message Lütfen uygulama ayarlarından izinleri verin veya uygulamayı yeniden başlatın.",
                    modifier = Modifier.padding(innerPadding).padding(16.dp) // Ekstra padding
                )

                // Örnek Buton (İsteğe Bağlı)
                // Column(modifier = Modifier.padding(innerPadding).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                //     Text(text = message, textAlign = TextAlign.Center)
                //     Spacer(modifier = Modifier.height(16.dp))
                //     Button(onClick = onRequestPermissions) {
                //         Text("İzinleri Tekrar İste")
                //     }
                // }
            }
        }
    }
}