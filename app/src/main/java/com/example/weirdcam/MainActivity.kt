package com.example.weirdcam

import android.Manifest // İzinler için
import android.content.pm.PackageManager // İzin kontrolü için
import android.os.Build
import android.os.Bundle
import android.util.Log // Hata ayıklama mesajları için
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts // İzin sonucunu almak için
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text // İzin reddedilirse mesaj için
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat // İzin kontrolü için
import com.example.weirdcam.ui.theme.WeirdcamTheme

class MainActivity : ComponentActivity() {

    // İzin durumunu tutacak bir state
    private var hasCameraPermission by mutableStateOf(false)

    // İzin sonucunu işlemek için bir başlatıcı (launcher)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("WeirdCam", "Kamera izni verildi.")
                hasCameraPermission = true // İzin verildi, state'i güncelle
            } else {
                Log.d("WeirdCam", "Kamera izni reddedildi.")
                hasCameraPermission = false // İzin reddedildi, state'i güncelle
                // İsteğe bağlı: Kullanıcıya neden iznin gerekli olduğunu açıklayan bir mesaj gösterilebilir.
            }
        }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Başlangıçta izin durumunu kontrol et
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            // Composable içinde izin isteme mantığı
            // `LaunchedEffect` kullanarak izin isteme işlemini Composable'ın yaşam döngüsüne bağlayabiliriz.
            // Ancak, izin isteme Activity Result API'ını kullandığı için `onCreate` içinde başlatmak daha yaygındır.
            // `hasCameraPermission` state'i değiştiğinde UI yeniden çizilecektir.

            if (!hasCameraPermission) {
                // Eğer izin başlangıçta yoksa ve daha önce istenmemişse (veya rationale gösterilmeliye) iste.
                // Bu kısmı `LaunchedEffect` içine alarak composable ilk yüklendiğinde tetikleyebiliriz.
                LaunchedEffect(Unit) { // Sadece bir kez çalışması için Unit key olarak kullanılır.
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Log.d("WeirdCam", "Kamera izni için açıklama gösterilmeli (bu adımda direkt isteniyor).")
                        // Burada kullanıcıya neden izin istediğinizi açıklayan bir diyalog gösterebilirsiniz.
                        // Şimdilik direkt izin istiyoruz.
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else if (!hasCameraPermission) { // İzin yoksa ve rationale gerekmiyorsa (ilk kez isteniyor veya "bir daha sorma" seçilmiş)
                        Log.d("WeirdCam", "Kamera izni isteniyor.")
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            AppContent(hasCameraPermission = hasCameraPermission)
        }
    }
}

// İçeriği göstermek için ayrı bir Composable fonksiyon
@Composable
fun AppContent(hasCameraPermission: Boolean, modifier: Modifier = Modifier) {
    WeirdcamTheme {
        Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
            if (hasCameraPermission) {
                CameraPreview(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                )
            } else {
                // İzin verilmediyse veya bekleniyorsa kullanıcıya bir mesaj göster
                Text(
                    text = if (ContextCompat.checkSelfPermission(
                            androidx.compose.ui.platform.LocalContext.current, // Context'i buradan alabiliriz
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        "Kamera yükleniyor..." // İzin yeni verildiyse ve UI güncelleniyorsa
                    } else {
                        "Kamera izni verilmedi. Uygulamanın çalışması için kamera izni gereklidir. Lütfen uygulama ayarlarından izin verin veya uygulamayı yeniden başlatın."
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

