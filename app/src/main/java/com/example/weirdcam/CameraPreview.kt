package com.example.weirdcam

import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CameraPreview(modifier: Modifier = Modifier, cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA) { // Kamera seçici parametre olarak eklendi
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // TextureView kullanarak dönüşümlerle daha iyi uyumluluk sağlamak için EKLENDİ
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                // Görüntüyü dikeyde aynala (ters çevir)
                rotationX = 180f // Görüntüyü X ekseni etrafında 180 derece döndürerek dikeyde aynala
                rotationY = 0f // Yatayda döndürme yok
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            cameraProviderFuture.addListener({
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector, // MainActivity'den gelen kamera seçiciyi kullan
                        preview
                    )
                } catch (exc: Exception) {
                    Log.e("WeirdCam", "Kamera bağlama işlemi başarısız oldu.", exc)
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        },
        update = { previewView ->
            // Kamera seçici değiştiğinde veya başka bir güncelleme gerektiğinde
            // Görüntüyü dikeyde aynala ve implementasyon modunu koru
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE // Uyumlu modun korunduğundan emin ol
            previewView.rotationX = 180f
            previewView.rotationY = 0f
            // Gerekirse burada kamera yeniden bağlanabilir.
        },
        modifier = modifier.fillMaxSize()
    )

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.e("WeirdCam", "Kamera kaynaklarını serbest bırakma başarısız.", e)
            }
        }
    }
}
