// CameraPreview.kt
package com.example.weirdcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.core.graphics.scale // KTX eklentisi için
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt


fun getCameraSelectorDescription(selector: CameraSelector): String {
    return when (selector) {
        CameraSelector.DEFAULT_BACK_CAMERA -> "Arka Kamera"
        CameraSelector.DEFAULT_FRONT_CAMERA -> "Ön Kamera"
        else -> "Bilinmeyen Kamera"
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraWithControls(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var mirrorEffectEnabled by remember { mutableStateOf(false) }
    var invertVerticalEnabled by remember { mutableStateOf(true) }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var cameraInfo: CameraInfo? by remember { mutableStateOf(null) }
    var uiZoomLevel by remember { mutableFloatStateOf(1f) }
    val maxZoomUi = 100f // Değişken adı düzeltildi
    var minZoomHardware by remember { mutableFloatStateOf(1f) }
    var maxZoomHardware by remember { mutableFloatStateOf(1f) }

    val previewUseCase = remember { Preview.Builder().build() }
    val imageCaptureUseCase = remember { ImageCapture.Builder().build() }
    val recorder = remember {
        Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
    }
    val videoCaptureUseCase = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }
    var isRecording by remember { mutableStateOf(false) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(cameraProviderFuture, cameraSelector, lifecycleOwner) {
        Log.d("WeirdCam", "LaunchedEffect (bağlama): Seçici: ${getCameraSelectorDescription(cameraSelector)}")
        val cameraProvider = cameraProviderFuture.get()
        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, previewUseCase, imageCaptureUseCase, videoCaptureUseCase
            )
            cameraControl = camera.cameraControl
            cameraInfo = camera.cameraInfo
            cameraInfo?.zoomState?.value?.let { currentZoomState ->
                minZoomHardware = currentZoomState.minZoomRatio
                maxZoomHardware = currentZoomState.maxZoomRatio
                uiZoomLevel = currentZoomState.zoomRatio.coerceIn(1f, maxZoomUi)
                cameraControl?.setZoomRatio(uiZoomLevel.coerceIn(minZoomHardware,maxZoomHardware))
            }
        } catch (e: Exception) {
            Log.e("WeirdCam", "Kamera bağlama LaunchedEffect hatası: ${e.localizedMessage}", e)
            cameraControl = null; cameraInfo = null
        }
    }

    val scaleGestureDetector = remember(context, cameraControl, minZoomHardware, maxZoomHardware) {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                cameraControl?.let { control ->
                    val currentAppZoom = uiZoomLevel
                    var newAppZoom = currentAppZoom * detector.scaleFactor
                    newAppZoom = newAppZoom.coerceIn(1f, maxZoomUi)
                    if (abs(uiZoomLevel - newAppZoom) > 0.01f) {
                        uiZoomLevel = newAppZoom
                        val actualZoomToSend = newAppZoom.coerceIn(minZoomHardware, maxZoomHardware)
                        control.setZoomRatio(actualZoomToSend)
                    }
                    return true
                }
                return false
            }
        })
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    setOnTouchListener { _, event ->
                        scaleGestureDetector.onTouchEvent(event)
                        return@setOnTouchListener true
                    }
                }
                previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
                previewView
            },
            update = { previewView ->
                previewView.rotationX = if (invertVerticalEnabled) 180f else 0f
                previewView.scaleX = if (mirrorEffectEnabled) -1f else 1f
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            cameraControl?.let { control ->
                Text(text = "Zoom: %.1fx".format(uiZoomLevel), modifier = Modifier.padding(bottom = 0.dp))
                Slider(
                    value = uiZoomLevel,
                    onValueChange = { newZoomLevel ->
                        uiZoomLevel = newZoomLevel
                        val actualZoomToSend = newZoomLevel.coerceIn(minZoomHardware, maxZoomHardware)
                        control.setZoomRatio(actualZoomToSend)
                    },
                    valueRange = 1f..maxZoomUi,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                }) { Icon(Icons.Filled.Cameraswitch, contentDescription = "Kamerayı Değiştir") }
                IconButton(onClick = { mirrorEffectEnabled = !mirrorEffectEnabled }) { Icon(Icons.Filled.Flip, contentDescription = "Ayna Efekti") }
                IconButton(onClick = { invertVerticalEnabled = !invertVerticalEnabled }) { Icon(Icons.Filled.Rotate90DegreesCcw, contentDescription = "Dikey Çevirme") }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    takePhoto(
                        context, imageCaptureUseCase, cameraExecutor,
                        mirrorEffectEnabled, invertVerticalEnabled,
                        uiZoomLevel, maxZoomHardware
                    )
                }) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Fotoğraf Çek") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = {
                    if (isRecording) {
                        recording?.stop() // Mevcut kaydı durdur
                    } else {
                        // Lambda argümanı parantez dışına taşındı (satır 227)
                        recording = startVideoRecording(context, videoCaptureUseCase, cameraExecutor) { event: VideoRecordEvent ->
                            when (event) {
                                is VideoRecordEvent.Start -> { isRecording = true; Log.d("WeirdCam", "Video kaydı başladı.") }
                                is VideoRecordEvent.Finalize -> {
                                    isRecording = false
                                    if (event.hasError()) { Log.e("WeirdCam", "Video kayıt hatası: ${event.error} - Sebep: ${event.cause?.message}") }
                                    else { Log.d("WeirdCam", "Video başarıyla kaydedildi: ${event.outputResults.outputUri}") }
                                }
                                else -> { Log.d("WeirdCam", "Diğer VideoRecordEvent: $event") }
                            }
                        }
                        // recording == null kontrolü (satır 239)
                        if (recording == null) { // isRecording zaten false olacak eğer Start event'i gelmediyse
                            Log.w("WeirdCam", "Video kaydı başlatılamadı (recording null döndü).")
                        }
                    }
                }) { Icon(if (isRecording) Icons.Filled.StopCircle else Icons.Filled.Videocam, contentDescription = if (isRecording) "Kaydı Durdur" else "Video Kaydet") }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            if (cameraProviderFuture.isDone) {
                try { cameraProviderFuture.get().unbindAll() }
                catch (e: Exception) { Log.e("WeirdCam", "Kamerayı onDispose içinde çözerken hata", e) }
            }
        }
    }
}

private fun takePhoto(
    context: android.content.Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    isMirrored: Boolean,
    isInvertedVertical: Boolean,
    currentUiZoomLevel: Float,
    cameraRealMaxZoom: Float
) {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeirdCam")
        }
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

    imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onError(exc: ImageCaptureException) { Log.e("WeirdCam", "Fotoğraf çekme hatası", exc) }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            output.savedUri?.let { uri ->
                executor.execute {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val sourceBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (sourceBitmap == null) {
                            Log.e("WeirdCam", "Bitmap çözümlenemedi: $uri")
                            return@execute
                        }

                        var workingBitmap: Bitmap = sourceBitmap
                        var transformationApplied = false

                        val softwareZoomFactor = if (cameraRealMaxZoom > 0 && currentUiZoomLevel > cameraRealMaxZoom) {
                            currentUiZoomLevel / cameraRealMaxZoom
                        } else { 1.0f }

                        if (softwareZoomFactor > 1.01f) {
                            val originalWidth = workingBitmap.width
                            val originalHeight = workingBitmap.height
                            val newWidth = (originalWidth / softwareZoomFactor).roundToInt()
                            val newHeight = (originalHeight / softwareZoomFactor).roundToInt()
                            if (newWidth > 0 && newHeight > 0 && newWidth <= originalWidth && newHeight <= originalHeight) {
                                val croppedBitmap = Bitmap.createBitmap(workingBitmap, (originalWidth - newWidth) / 2, (originalHeight - newHeight) / 2, newWidth, newHeight)
                                val scaledBitmap = croppedBitmap.scale(originalWidth, originalHeight, true) // KTX scale kullanımı (satır 328)

                                if (workingBitmap != sourceBitmap) { // Eğer workingBitmap zaten sourceBitmap değilse (gelecekteki bir modifikasyon için)
                                    workingBitmap.recycle()
                                }
                                workingBitmap = scaledBitmap // workingBitmap artık yeni, ölçeklenmiş bitmap
                                transformationApplied = true
                                croppedBitmap.recycle()
                            }
                        }

                        val matrix = Matrix()
                        var matrixTransformationNeeded = false
                        if (isInvertedVertical) { matrix.postRotate(180f); matrixTransformationNeeded = true }
                        if (isMirrored) { matrix.postScale(-1f, 1f, workingBitmap.width / 2f, workingBitmap.height / 2f); matrixTransformationNeeded = true }

                        if (matrixTransformationNeeded) {
                            val transformedWithMatrixBitmap = Bitmap.createBitmap(workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true)
                            // Eğer workingBitmap, sourceBitmap değilse VE yeni transformed bitmap'ten de farklıysa, eski workingBitmap'i recycle et
                            if (workingBitmap != sourceBitmap && workingBitmap != transformedWithMatrixBitmap) {
                                workingBitmap.recycle()
                            } else if (workingBitmap == sourceBitmap && workingBitmap == transformedWithMatrixBitmap) {
                                // Bu durumda sourceBitmap ve workingBitmap aynı ve matris bir değişiklik yapmamış (örn. 0 derece döndürme)
                                // Ama matris uygulandığı için transformedWithMatrixBitmap yeni bir nesne olabilir.
                                // Eğer transformedWithMatrixBitmap workingBitmap ile aynıysa (createBitmap bazen aynı nesneyi döndürebilir),
                                // ve bu da sourceBitmap ise, sourceBitmap'i sonda recycle edeceğiz.
                                // Karmaşıklığı azaltmak için: transformedWithMatrixBitmap yeni workingBitmap olur.
                                // Eski workingBitmap (eğer sourceBitmap değilse) recycle edilir.
                            }
                            if (workingBitmap != transformedWithMatrixBitmap && workingBitmap != sourceBitmap) { // workingBitmap yeni bir nesne ve sourceBitmap değilse
                                workingBitmap.recycle()
                            }
                            workingBitmap = transformedWithMatrixBitmap
                            transformationApplied = true
                        }


                        if (transformationApplied) {
                            context.contentResolver.openOutputStream(uri)?.let { outStream: OutputStream ->
                                // sharpenBitmap çağrısı kaldırıldı (satır 328)
                                workingBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
                                outStream.close()
                                Log.d("WeirdCam", "Fotoğraf işlenerek kaydedildi: $uri")
                            } ?: Log.e("WeirdCam", "OutputStream açılamadı: $uri")
                        }

                        // Condition 'workingBitmap != sourceBitmap' is always false (satır 307) için düzeltme:
                        // sourceBitmap her zaman recycle edilmeli. workingBitmap ise, eğer sourceBitmap'ten
                        // farklı bir nesne haline geldiyse (yani işlemlerle yeni bir bitmap oluşturulduysa) recycle edilmeli.
                        if (workingBitmap != sourceBitmap) {
                            workingBitmap.recycle()
                        }
                        sourceBitmap.recycle()

                    } catch (e: Exception) { Log.e("WeirdCam", "Fotoğrafı işleme hatası", e) }
                }
            }
        }
    })
}

private fun startVideoRecording(
    context: android.content.Context,
    videoCapture: VideoCapture<Recorder>, // Parametre adı güncellendi
    executor: ExecutorService,
    onRecordEvent: (VideoRecordEvent) -> Unit // Parametre adı ve tipi güncellendi
): Recording {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WeirdCam")
        }
    }
    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build()

    // 'Assignment' can be lifted out of 'if' (satır 366) için düzeltme:
    val pendingRecording: Recording = if (PermissionChecker.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
        Log.d("WeirdCam", "Ses kaydı izni var.")
        videoCapture.output.prepareRecording(context, mediaStoreOutputOptions).withAudioEnabled().start(executor, onRecordEvent)
    } else {
        Log.w("WeirdCam", "Ses kaydı izni yok.")
        videoCapture.output.prepareRecording(context, mediaStoreOutputOptions).start(executor, onRecordEvent)
    }
    return pendingRecording // Her iki dal da Recording döndürdüğü için fonksiyon da Recording döndürür (satır 392)
}