// CameraPreview.kt
package com.example.weirdcam

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraWithControls(modifier: Modifier = Modifier, cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // ImageCapture ve VideoCapture için state'ler
    val imageCapture = remember { ImageCapture.Builder().build() }
    val recorder = remember { Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HIGHEST)) // Veya istediğin bir kalite
        .build() }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }
    var isRecording by remember { mutableStateOf(false) }

    // Arka planda işlemler için executor
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }


    LaunchedEffect(cameraProviderFuture, cameraSelector) {
        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll() // Önceki bağlantıları çöz
        try {
            // Preview zaten var, onu da ekle
            val preview = Preview.Builder().build() // Preview'ı burada yeniden oluşturabilir veya dışarıdan alabilirsin

            // PreviewView'ı bağlamak için AndroidView'a ihtiyacımız olacak,
            // bu yüzden preview.setSurfaceProvider'ı AndroidView içinde yapacağız.

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview, // Preview'ı buraya ekle
                imageCapture, // ImageCapture'ı bağla
                videoCapture  // VideoCapture'ı bağla
            )
            Log.d("WeirdCam", "Kamera kullanım senaryoları bağlandı.")
        } catch (exc: Exception) {
            Log.e("WeirdCam", "Kullanım senaryolarını bağlama başarısız oldu.", exc)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    rotationX = 180f
                    rotationY = 0f
                }
                // Preview'ın surfaceProvider'ını burada ayarla
                cameraProviderFuture.get().bindToLifecycle( // Tekrar bind etmeye gerek olabilir ya da preview'ı dışarı alıp setSurfaceProvider yap
                    lifecycleOwner,
                    cameraSelector,
                    Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) },
                    imageCapture,
                    videoCapture
                )
                previewView
            },
            update = { previewView ->
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView.rotationX = 180f
                previewView.rotationY = 0f
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row {
                Button(onClick = {
                    takePhoto(context, imageCapture, cameraExecutor)
                }) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Fotoğraf Çek")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = {
                    if (isRecording) {
                        recording?.stop()
                        isRecording = false
                    } else {
                        recording = startVideoRecording(context, videoCapture, cameraExecutor) { event ->
                            if (event is VideoRecordEvent.Finalize) {
                                isRecording = false
                                if (event.hasError()) {
                                    Log.e("WeirdCam", "Video kaydı hatası: ${event.error} - ${event.cause?.message}")
                                } else {
                                    Log.d("WeirdCam", "Video başarıyla kaydedildi: ${event.outputResults.outputUri}")
                                }
                            }
                        }
                        isRecording = true
                    }
                }) {
                    Icon(
                        if (isRecording) Icons.Filled.StopCircle else Icons.Filled.Videocam,
                        contentDescription = if (isRecording) "Kaydı Durdur" else "Video Kaydet"
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.e("WeirdCam", "Kamera kaynaklarını serbest bırakma başarısız (controls).", e)
            }
        }
    }
}


private fun takePhoto(
    context: android.content.Context,
    imageCapture: ImageCapture,
    executor: ExecutorService
) {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeirdCam")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions
        .Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        .build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("WeirdCam", "Fotoğraf çekme hatası: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d("WeirdCam", "Fotoğraf başarıyla kaydedildi: ${output.savedUri}")
            }
        }
    )
}

private fun startVideoRecording(
    context: android.content.Context,
    videoCapture: VideoCapture<Recorder>,
    executor: ExecutorService,
    onRecordEvent: (VideoRecordEvent) -> Unit
): Recording? {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WeirdCam")
        }
    }

    val mediaStoreOutputOptions = MediaStoreOutputOptions
        .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        .setContentValues(contentValues)
        .build()

    // Ses iznini kontrol et
    if (PermissionChecker.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
        Log.d("WeirdCam", "Ses kaydı izni var.")
        return videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)
            .withAudioEnabled() // Sesi etkinleştir
            .start(executor, onRecordEvent)
    } else {
        Log.w("WeirdCam", "Ses kaydı izni yok, video sessiz olacak veya kayıt başlamayacak.")
        // Sadece video kaydı başlat (sessiz) veya kullanıcıya bilgi ver.
        // Şimdilik sessiz kayıt deniyoruz.
        return videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)
            // .withAudioEnabled() // Bu satırı kaldır ya da izin yoksa farklı davran
            .start(executor, onRecordEvent)
    }

}

// Eski CameraPreview fonksiyonunu şimdilik olduğu gibi bırakabilirsin veya CameraWithControls ile değiştirebilirsin
@Composable
fun CameraPreview(modifier: Modifier = Modifier, cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember {ProcessCameraProvider.getInstance(context)} // remember kullan

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                rotationX = 180f
                rotationY = 0f
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Bu blok LaunchedEffect içinde olmalı veya cameraProviderFuture hazır olduğunda çalışmalı
            cameraProviderFuture.addListener({
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                try {
                    cameraProvider.unbindAll()
                    // SADECE Preview'ı bağla, diğer use case'ler CameraWithControls'da
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                    Log.d("WeirdCam", "Sadece Preview bağlandı.")
                } catch (exc: Exception) {
                    Log.e("WeirdCam", "Preview bağlama işlemi başarısız oldu.", exc)
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        },
        update = { previewView ->
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            previewView.rotationX = 180f
            previewView.rotationY = 0f
        },
        modifier = modifier.fillMaxSize()
    )

    DisposableEffect(lifecycleOwner) { // lifecycleOwner'ı key olarak kullan
        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
                Log.d("WeirdCam", "Preview kaynakları serbest bırakıldı.")
            } catch (e: Exception) {
                Log.e("WeirdCam", "Preview kamera kaynaklarını serbest bırakma başarısız.", e)
            }
        }
    }
}