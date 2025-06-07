package com.example.weirdcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.rememberAsyncImagePainter
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

sealed class UiAspectRatio(val displayName: String) {
    data object Ratio16x9 : UiAspectRatio("16:9")
    data object Ratio4x3 : UiAspectRatio("4:3")
    data object Ratio1x1 : UiAspectRatio("1:1")

    @Suppress("DEPRECATION")
    fun toCameraXAspectRatio(): Int {
        return when (this) {
            Ratio16x9 -> AspectRatio.RATIO_16_9
            Ratio4x3 -> AspectRatio.RATIO_4_3
            Ratio1x1 -> AspectRatio.RATIO_4_3
        }
    }
}

enum class FlashModeCycle(val mode: Int, val icon: @Composable () -> Unit) {
    OFF(ImageCapture.FLASH_MODE_OFF, { Icon(Icons.Filled.FlashOff, contentDescription = "Flaş Kapalı", tint = Color.White) }),
    ON(ImageCapture.FLASH_MODE_ON, { Icon(Icons.Filled.FlashOn, contentDescription = "Flaş Açık", tint = Color.Yellow) }),
    AUTO(ImageCapture.FLASH_MODE_AUTO, { Icon(Icons.Filled.FlashAuto, contentDescription = "Flaş Otomatik", tint = Color.White) });

    fun next(): FlashModeCycle {
        return entries[(ordinal + 1) % entries.size]
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraWithControls(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = remember { ProcessCameraProvider.getInstance(context) }

    var userRequestedLensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val currentCameraSelector = remember(userRequestedLensFacing) {
        if (userRequestedLensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.DEFAULT_BACK_CAMERA
        else CameraSelector.DEFAULT_FRONT_CAMERA
    }

    var selectedUiAspectRatio by remember { mutableStateOf<UiAspectRatio>(UiAspectRatio.Ratio16x9) }
    var imageCaptureFlashMode by remember { mutableStateOf(FlashModeCycle.OFF) }
    var mirrorEffectEnabled by remember { mutableStateOf(false) }
    var invertVerticalEnabled by remember { mutableStateOf(false) }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var cameraInfo: CameraInfo? by remember { mutableStateOf(null) }

    var targetUiZoomLevel by remember { mutableFloatStateOf(1f) }
    val animatedUiZoomLevel by animateFloatAsState(
        targetValue = targetUiZoomLevel,
        animationSpec = tween(durationMillis = 100),
        label = "animatedUiZoomLevel"
    )
    val maxZoomUi = 100f
    var minZoomHardware by remember { mutableFloatStateOf(1f) }
    var maxZoomHardware by remember { mutableFloatStateOf(1f) }
    var sliderActualMinRange by remember { mutableFloatStateOf(1f) }

    var lastSetUseCaseHashForSurfaceProvider by remember { mutableIntStateOf(0) }
    var lastTakenPhotoUri by remember { mutableStateOf<Uri?>(null) }

    var focusRingState by remember { mutableStateOf<Pair<Offset, Long>?>(null) }


    @Suppress("DEPRECATION")
    val previewUseCase = remember(selectedUiAspectRatio) {
        Preview.Builder().setTargetAspectRatio(selectedUiAspectRatio.toCameraXAspectRatio()).build()
    }

    @Suppress("DEPRECATION")
    val imageCaptureUseCase = remember(selectedUiAspectRatio, imageCaptureFlashMode) {
        ImageCapture.Builder()
            .setTargetAspectRatio(selectedUiAspectRatio.toCameraXAspectRatio())
            .setFlashMode(imageCaptureFlashMode.mode)
            .build()
    }

    val videoCaptureUseCase: VideoCapture<Recorder> = remember {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
        VideoCapture.Builder(recorder).build()
    }

    var recording: Recording? by remember { mutableStateOf(null) }
    var isRecording by remember { mutableStateOf(false) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(cameraProviderFuture, currentCameraSelector, lifecycleOwner, previewUseCase, imageCaptureUseCase, videoCaptureUseCase) {
        val cameraProvider = cameraProviderFuture.get()
        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, currentCameraSelector, previewUseCase, imageCaptureUseCase, videoCaptureUseCase
            )
            cameraControl = camera.cameraControl
            cameraInfo = camera.cameraInfo

            cameraInfo?.zoomState?.value?.let { zoomState ->
                minZoomHardware = zoomState.minZoomRatio
                maxZoomHardware = zoomState.maxZoomRatio
                sliderActualMinRange = minZoomHardware
                targetUiZoomLevel = targetUiZoomLevel.coerceIn(sliderActualMinRange, maxZoomUi)
            }
        } catch (e: Exception) {
            Log.e("WeirdCam", "Camera binding failed: ${e.localizedMessage}", e)
        }
    }

    val actualHardwareZoomToApply = remember(animatedUiZoomLevel, minZoomHardware, maxZoomHardware) {
        animatedUiZoomLevel.coerceIn(minZoomHardware, maxZoomHardware)
    }

    LaunchedEffect(actualHardwareZoomToApply) {
        cameraControl?.setZoomRatio(actualHardwareZoomToApply)
    }

    val previewSoftwareZoomFactor by remember(animatedUiZoomLevel, maxZoomHardware) {
        mutableStateOf(
            if (maxZoomHardware > 0f && animatedUiZoomLevel > maxZoomHardware)
                (animatedUiZoomLevel / maxZoomHardware).coerceAtLeast(1f)
            else 1f
        )
    }

    var currentPreviewView: PreviewView? by remember { mutableStateOf(null) }

    val scaleGestureDetector = remember {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    targetUiZoomLevel = (targetUiZoomLevel * detector.scaleFactor).coerceIn(minZoomHardware, maxZoomUi)
                    return true
                }
            })
    }

    val tapGestureDetector = remember(context, cameraControl) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusRingState = Pair(Offset(e.x, e.y), System.currentTimeMillis())

                currentPreviewView?.let { view ->
                    val factory = SurfaceOrientedMeteringPointFactory(view.width.toFloat(), view.height.toFloat())
                    val action = FocusMeteringAction.Builder(
                        factory.createPoint(e.x, e.y),
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cameraControl?.startFocusAndMetering(action)
                }
                return true
            }
        })
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = previewSoftwareZoomFactor
                scaleY = previewSoftwareZoomFactor
                rotationZ = if (invertVerticalEnabled) 180f else 0f
                val isFrontCamera = userRequestedLensFacing == CameraSelector.LENS_FACING_FRONT
                scaleX *= if (mirrorEffectEnabled) {
                    if (isFrontCamera) 1f else -1f
                } else {
                    if (isFrontCamera) -1f else 1f
                }
            },
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    currentPreviewView = this
                    previewUseCase.setSurfaceProvider(surfaceProvider)
                    lastSetUseCaseHashForSurfaceProvider = previewUseCase.hashCode()
                    setOnTouchListener { _, event ->
                        var consumed = scaleGestureDetector.onTouchEvent(event)
                        if (event.pointerCount == 1 && !consumed) {
                            consumed = tapGestureDetector.onTouchEvent(event)
                        }
                        consumed
                    }
                }
            },
            update = { view ->
                if (lastSetUseCaseHashForSurfaceProvider != previewUseCase.hashCode()) {
                    previewUseCase.setSurfaceProvider(view.surfaceProvider)
                    lastSetUseCaseHashForSurfaceProvider = previewUseCase.hashCode()
                }
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            focusRingState?.let { (position, key) ->
                FocusRing(position = position, key = key)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    IconButton(onClick = {
                        imageCaptureFlashMode = imageCaptureFlashMode.next()
                    }) { imageCaptureFlashMode.icon() }
                }
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        UiAspectRatio.Ratio16x9,
                        UiAspectRatio.Ratio4x3,
                        UiAspectRatio.Ratio1x1
                    ).forEach { ar ->
                        Text(
                            ar.displayName,
                            fontSize = 12.sp,
                            color = if (selectedUiAspectRatio == ar) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedUiAspectRatio = ar }
                                .padding(vertical = 6.dp, horizontal = 8.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Zoom: %.1fx".format(animatedUiZoomLevel),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Slider(
                    value = targetUiZoomLevel,
                    onValueChange = {
                        targetUiZoomLevel = it.coerceIn(sliderActualMinRange, maxZoomUi)
                    },
                    valueRange = sliderActualMinRange..maxZoomUi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        lastTakenPhotoUri?.let { uri ->
                            Image(
                                painter = rememberAsyncImagePainter(uri),
                                contentDescription = "Son Çekilen",
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.DarkGray)
                                    .clickable {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            })
                                        } catch (e: ActivityNotFoundException) {
                                            Log.e("WeirdCam", "Galeri bulunamadı.", e)
                                        }
                                    },
                                contentScale = ContentScale.Crop
                            )
                        } ?: Box(Modifier.size(52.dp))
                    }
                    IconButton(onClick = {
                        userRequestedLensFacing =
                            if (userRequestedLensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    }) {
                        Icon(Icons.Filled.Cameraswitch, "Kamera Değiştir", tint = Color.White)
                    }
                    IconButton(onClick = { mirrorEffectEnabled = !mirrorEffectEnabled }) {
                        Icon(Icons.Filled.Flip, "Ayna Efekti", tint = if (mirrorEffectEnabled) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton(onClick = { invertVerticalEnabled = !invertVerticalEnabled }) {
                        Icon(Icons.Filled.Rotate90DegreesCcw, "Dikey Ters Çevir", tint = if (invertVerticalEnabled) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    Box(modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = {
                        takePhoto(context, imageCaptureUseCase, cameraExecutor, mirrorEffectEnabled, invertVerticalEnabled, targetUiZoomLevel, maxZoomHardware, selectedUiAspectRatio) { uri -> lastTakenPhotoUri = uri }
                    }) { Icon(Icons.Filled.PhotoCamera, "Fotoğraf Çek") }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = {
                        if (isRecording) recording?.stop()
                        else recording = startVideoRecording(context, videoCaptureUseCase, cameraExecutor) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> isRecording = true
                                is VideoRecordEvent.Finalize -> {
                                    isRecording = false
                                    if (event.hasError()) Log.e("WeirdCam", "Video hata: ${event.error} - ${event.cause?.message}")
                                    else Log.d("WeirdCam", "Video kaydedildi: ${event.outputResults.outputUri}")
                                }
                                else -> {}
                            }
                        }
                    }) {
                        Icon(if (isRecording) Icons.Filled.StopCircle else Icons.Filled.Videocam, if (isRecording) "Durdur" else "Video")
                    }
                }
            }
        }
    }

    // KAYNAK SIZINTISINI ÖNLEYEN BLOK
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            Log.d("WeirdCam", "CameraExecutor başarıyla kapatıldı.")
        }
    }
}

@Composable
private fun FocusRing(position: Offset, key: Any) {
    var isVisible by remember { mutableStateOf(true) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isVisible) 1.2f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "focusRingScale"
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300, delayMillis = 400),
        label = "focusRingAlpha"
    )

    LaunchedEffect(key) {
        isVisible = true
        delay(650L)
        isVisible = false
    }

    Box(
        modifier = Modifier
            .graphicsLayer(
                scaleX = animatedScale,
                scaleY = animatedScale,
                alpha = animatedAlpha
            )
            .offset {
                IntOffset(
                    (position.x - 40.dp.toPx()).roundToInt(),
                    (position.y - 40.dp.toPx()).roundToInt()
                )
            }
            .size(80.dp)
            .border(2.dp, Color.White, CircleShape)
    )
}


private fun takePhoto(
    context: Context, imageCapture: ImageCapture, executor: ExecutorService, isMirrored: Boolean, isInvertedVertical: Boolean, currentUiZoomLevel: Float, cameraRealMaxZoom: Float, uiAspectRatio: UiAspectRatio, onPhotoTaken: (Uri?) -> Unit
) {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeirdCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onError(exc: ImageCaptureException) {
            Log.e("WeirdCam", "Photo capture failed: ${exc.message}", exc)
            onPhotoTaken(null)
        }

        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            val savedUri = output.savedUri
            if (savedUri == null) {
                Log.e("WeirdCam", "Photo saved but URI is null"); onPhotoTaken(null); return
            }
            onPhotoTaken(savedUri)

            executor.execute {
                var finalBitmapToProcess: Bitmap? = null
                var sourceBitmapFromFile: Bitmap? = null
                var needsResaving = false
                try {
                    context.contentResolver.openInputStream(savedUri).use { inputStream ->
                        sourceBitmapFromFile = BitmapFactory.decodeStream(inputStream)
                    }
                    if (sourceBitmapFromFile == null) {
                        Log.e("WeirdCam", "Decode bitmap failed: $savedUri"); return@execute
                    }
                    var currentBitmap = sourceBitmapFromFile!!
                    if (uiAspectRatio == UiAspectRatio.Ratio1x1 && currentBitmap.width != currentBitmap.height) {
                        val side = min(currentBitmap.width, currentBitmap.height)
                        val cropX = (currentBitmap.width - side) / 2
                        val cropY = (currentBitmap.height - side) / 2
                        val cropped1to1 = Bitmap.createBitmap(currentBitmap, cropX, cropY, side, side)
                        if (currentBitmap != sourceBitmapFromFile) currentBitmap.recycle()
                        currentBitmap = cropped1to1
                        needsResaving = true
                    }
                    val softwareZoomFactor = if (cameraRealMaxZoom > 0f && currentUiZoomLevel > cameraRealMaxZoom) (currentUiZoomLevel / cameraRealMaxZoom).coerceAtLeast(1f) else 1.0f
                    if (softwareZoomFactor > 1.01f) {
                        val oW = currentBitmap.width; val oH = currentBitmap.height
                        val nW = (oW / softwareZoomFactor).roundToInt(); val nH = (oH / softwareZoomFactor).roundToInt()
                        if (nW > 0 && nH > 0 && nW <= oW && nH <= oH) {
                            val cX = (oW - nW) / 2; val cY = (oH - nH) / 2
                            val croppedZoom = Bitmap.createBitmap(currentBitmap, cX, cY, nW, nH)
                            val scaledZoom = croppedZoom.scale(oW, oH, true)
                            croppedZoom.recycle()
                            if (currentBitmap != sourceBitmapFromFile) currentBitmap.recycle()
                            currentBitmap = scaledZoom
                            needsResaving = true
                        }
                    }
                    var matrixTransformationNeeded = false
                    val matrix = Matrix().apply {
                        if (isInvertedVertical) {
                            postRotate(180f, currentBitmap.width / 2f, currentBitmap.height / 2f); matrixTransformationNeeded = true
                        }
                        if (isMirrored) {
                            postScale(-1f, 1f, currentBitmap.width / 2f, currentBitmap.height / 2f); matrixTransformationNeeded = true
                        }
                    }
                    if (matrixTransformationNeeded) {
                        val matrixTransformed = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, matrix, true)
                        if (currentBitmap != sourceBitmapFromFile) currentBitmap.recycle()
                        currentBitmap = matrixTransformed
                        needsResaving = true
                    }
                    finalBitmapToProcess = currentBitmap
                    if (needsResaving) {
                        context.contentResolver.openOutputStream(savedUri, "w")?.use { outStream ->
                            finalBitmapToProcess!!.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WeirdCam", "Error processing photo: ${e.message}", e)
                } finally {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val finalContentValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                        try {
                            context.contentResolver.update(savedUri, finalContentValues, null, null)
                        } catch (e: Exception) {
                            Log.e("WeirdCam", "IS_PENDING (0) güncellenirken hata: $e")
                        }
                    }
                    if (finalBitmapToProcess != null && finalBitmapToProcess != sourceBitmapFromFile) {
                        finalBitmapToProcess.recycle()
                    }
                    sourceBitmapFromFile?.recycle()
                }
            }
        }
    })
}

private fun startVideoRecording(
    context: Context, videoCapture: VideoCapture<Recorder>, executor: ExecutorService, onRecordEvent: (VideoRecordEvent) -> Unit
): Recording? {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
    val contentValuesForVideo = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WeirdCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WeirdCam")
        }
    }
    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
        context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValuesForVideo).build()

    var pendingRecording: Recording? = null
    try {
        val audioEnabled = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val activeRecording = videoCapture.output.prepareRecording(context, mediaStoreOutputOptions)
        if (audioEnabled) activeRecording.withAudioEnabled()
        pendingRecording = activeRecording.start(executor, onRecordEvent)
    } catch (e: Exception) {
        Log.e("WeirdCam", "Video recording start failed: ${e.localizedMessage}", e)
    }
    return pendingRecording
}