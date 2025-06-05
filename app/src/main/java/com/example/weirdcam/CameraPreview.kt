package com.example.weirdcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// Sealed class to represent UI Aspect Ratio choices (remains the same)
sealed class UiAspectRatio(val displayName: String) {
    object Ratio16_9 : UiAspectRatio("16:9")
    object Ratio4_3 : UiAspectRatio("4:3")
    object Ratio1_1 : UiAspectRatio("1:1")

    fun toCameraXAspectRatio(): Int {
        return when (this) {
            Ratio16_9 -> AspectRatio.RATIO_16_9
            Ratio4_3 -> AspectRatio.RATIO_4_3
            Ratio1_1 -> AspectRatio.RATIO_4_3
        }
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
        if (userRequestedLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }

    var selectedUiAspectRatio by remember { mutableStateOf<UiAspectRatio>(UiAspectRatio.Ratio16_9) }

    // Varsayılan değerler değiştirildi
    var mirrorEffectEnabled by remember { mutableStateOf(false) }   // Varsayılan: Kapalı (Normal)
    var invertVerticalEnabled by remember { mutableStateOf(false) }  // Varsayılan: Kapalı (Normal)

    var isTorchOn by remember { mutableStateOf(false) }

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

    val previewUseCase = remember(selectedUiAspectRatio) {
        Log.d("WeirdCam", "Recreating PreviewUseCase for aspect ratio: ${selectedUiAspectRatio.displayName} (CameraX value: ${selectedUiAspectRatio.toCameraXAspectRatio()})")
        Preview.Builder()
            .setTargetAspectRatio(selectedUiAspectRatio.toCameraXAspectRatio())
            .build()
    }

    val imageCaptureUseCase = remember(selectedUiAspectRatio) {
        Log.d("WeirdCam", "Recreating ImageCaptureUseCase for aspect ratio: ${selectedUiAspectRatio.displayName} (CameraX value: ${selectedUiAspectRatio.toCameraXAspectRatio()})")
        ImageCapture.Builder()
            .setTargetAspectRatio(selectedUiAspectRatio.toCameraXAspectRatio())
            .build()
    }

    val videoCaptureUseCase = remember {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
        VideoCapture.Builder(recorder).build()
    }

    var recording: Recording? by remember { mutableStateOf(null) }
    var isRecording by remember { mutableStateOf(false) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(cameraProviderFuture, currentCameraSelector, lifecycleOwner, selectedUiAspectRatio) {
        Log.d("WeirdCam", "Binding camera. Triggered by aspect ratio change to: ${selectedUiAspectRatio.displayName}")
        val cameraProvider = cameraProviderFuture.get()
        try {
            cameraProvider.unbindAll()
            Log.d("WeirdCam", "Binding camera: ${if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"} with AR: ${selectedUiAspectRatio.displayName}")

            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                currentCameraSelector,
                previewUseCase,
                imageCaptureUseCase,
                videoCaptureUseCase
            )
            cameraControl = camera.cameraControl
            cameraInfo = camera.cameraInfo

            isTorchOn = false
            cameraControl?.enableTorch(false)

            cameraInfo?.zoomState?.value?.let { currentZoomState ->
                minZoomHardware = currentZoomState.minZoomRatio
                maxZoomHardware = currentZoomState.maxZoomRatio
                sliderActualMinRange = minZoomHardware.coerceAtLeast(1f)
                targetUiZoomLevel = targetUiZoomLevel.coerceIn(sliderActualMinRange, maxZoomUi)
            }
        } catch (e: Exception) {
            Log.e("WeirdCam", "Camera binding failed: ${e.localizedMessage}", e)
            cameraControl = null; cameraInfo = null
        }
    }

    val actualHardwareZoomToApply = remember(animatedUiZoomLevel, minZoomHardware, maxZoomHardware) {
        animatedUiZoomLevel.coerceIn(minZoomHardware, maxZoomHardware)
    }

    LaunchedEffect(actualHardwareZoomToApply, cameraControl) {
        cameraControl?.setZoomRatio(actualHardwareZoomToApply)
    }

    val previewSoftwareZoomFactor = remember(animatedUiZoomLevel, maxZoomHardware) {
        if (maxZoomHardware > 0f && animatedUiZoomLevel > maxZoomHardware) {
            (animatedUiZoomLevel / maxZoomHardware).coerceAtLeast(1f)
        } else { 1f }
    }

    var currentPreviewView: PreviewView? by remember { mutableStateOf(null) }

    val scaleGestureDetector = remember(context, sliderActualMinRange, maxZoomUi) {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newAppZoom = targetUiZoomLevel * detector.scaleFactor
                targetUiZoomLevel = newAppZoom.coerceIn(sliderActualMinRange, maxZoomUi)
                return true
            }
        })
    }

    val tapGestureDetector = remember(context, cameraControl) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val view = currentPreviewView ?: return false
                val factory = SurfaceOrientedMeteringPointFactory(view.width.toFloat(), view.height.toFloat())
                val meteringPoint = factory.createPoint(e.x, e.y)
                val action = FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                cameraControl?.startFocusAndMetering(action)
                return true
            }
        })
    }

    LaunchedEffect(isTorchOn, cameraControl, cameraInfo) {
        if (cameraInfo?.hasFlashUnit() == true) {
            cameraControl?.enableTorch(isTorchOn)?.addListener({
            }, ContextCompat.getMainExecutor(context))
        } else {
            if (isTorchOn) Log.d("WeirdCam", "Torch cannot be enabled: No flash unit.")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                currentPreviewView = previewView
                previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
                Log.d("WeirdCam", "AndroidView.factory: Initial surface provider set.")

                previewView.setOnTouchListener { _, event ->
                    var consumed = scaleGestureDetector.onTouchEvent(event)
                    if (event.pointerCount == 1 && !consumed) {
                        consumed = tapGestureDetector.onTouchEvent(event)
                    }
                    consumed
                }
                previewView
            },
            update = { view ->
                Log.d("WeirdCam", "AndroidView.update: Setting surface provider for current previewUseCase.")
                previewUseCase.setSurfaceProvider(view.surfaceProvider)

                currentPreviewView = view
                view.rotationX = if (invertVerticalEnabled) 180f else 0f
                val baseScaleX = if (mirrorEffectEnabled) -1f else 1f
                view.scaleX = baseScaleX * previewSoftwareZoomFactor
                view.scaleY = previewSoftwareZoomFactor
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val aspectRatioChoices = listOf(UiAspectRatio.Ratio16_9, UiAspectRatio.Ratio4_3, UiAspectRatio.Ratio1_1)
                aspectRatioChoices.forEach { ar ->
                    OutlinedButton(
                        onClick = { selectedUiAspectRatio = ar },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selectedUiAspectRatio == ar) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (selectedUiAspectRatio == ar) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(ar.displayName)
                    }
                }
            }

            Text(text = "Zoom: %.1fx".format(animatedUiZoomLevel), modifier = Modifier.padding(bottom = 0.dp))
            Slider(
                value = targetUiZoomLevel,
                onValueChange = { newZoom -> targetUiZoomLevel = newZoom.coerceIn(sliderActualMinRange, maxZoomUi) },
                valueRange = sliderActualMinRange..maxZoomUi,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    userRequestedLensFacing = if (userRequestedLensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else CameraSelector.LENS_FACING_BACK
                }) { Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch Camera") }

                IconButton(onClick = { mirrorEffectEnabled = !mirrorEffectEnabled }) { Icon(Icons.Filled.Flip, contentDescription = "Mirror Effect") }
                IconButton(onClick = { invertVerticalEnabled = !invertVerticalEnabled }) { Icon(Icons.Filled.Rotate90DegreesCcw, contentDescription = "Invert Vertical") }
                IconButton(onClick = {
                    if (cameraInfo?.hasFlashUnit() == true) isTorchOn = !isTorchOn
                    else Log.d("WeirdCam", "Flash toggle ignored: No flash unit")
                }) { Icon(if (isTorchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = "Toggle Flash") }
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
                        targetUiZoomLevel, maxZoomHardware, userRequestedLensFacing,
                        selectedUiAspectRatio
                    )
                }) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Take Photo") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = {
                    if (isRecording) recording?.stop()
                    else {
                        recording = startVideoRecording(context, videoCaptureUseCase, cameraExecutor) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> { isRecording = true }
                                is VideoRecordEvent.Finalize -> {
                                    isRecording = false
                                    if (event.hasError()) Log.e("WeirdCam", "Video recording error: ${event.error} - ${event.cause?.message}")
                                    else Log.d("WeirdCam", "Video saved: ${event.outputResults.outputUri}")
                                }
                                else -> {}
                            }
                        }
                    }
                }) { Icon(if (isRecording) Icons.Filled.StopCircle else Icons.Filled.Videocam, contentDescription = if (isRecording) "Stop Recording" else "Start Recording") }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    isMirrored: Boolean,
    isInvertedVertical: Boolean,
    currentUiZoomLevel: Float,
    cameraRealMaxZoom: Float,
    lensFacing: Int,
    uiAspectRatio: UiAspectRatio
) {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeirdCam")
        }
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
    ).build()

    imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onError(exc: ImageCaptureException) { Log.e("WeirdCam", "Photo capture failed: ${exc.message}", exc) }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            val savedUri = output.savedUri ?: run { Log.e("WeirdCam", "Photo saved URI is null"); return }
            Log.d("WeirdCam", "Photo captured: $savedUri (AR: ${uiAspectRatio.displayName}). Processing...")

            executor.execute {
                try {
                    val inputStream = context.contentResolver.openInputStream(savedUri)
                    var sourceBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (sourceBitmap == null) { Log.e("WeirdCam", "Decode bitmap failed: $savedUri"); return@execute }

                    var bitmapForProcessing = sourceBitmap
                    var transformationAppliedThisStage = false

                    if (uiAspectRatio == UiAspectRatio.Ratio1_1 && sourceBitmap.width != sourceBitmap.height) {
                        Log.d("WeirdCam", "Applying 1:1 crop to photo. Original: ${sourceBitmap.width}x${sourceBitmap.height}")
                        val side = min(sourceBitmap.width, sourceBitmap.height)
                        val cropX = (sourceBitmap.width - side) / 2
                        val cropY = (sourceBitmap.height - side) / 2
                        val cropped1to1Bitmap = Bitmap.createBitmap(sourceBitmap, cropX, cropY, side, side)
                        bitmapForProcessing = cropped1to1Bitmap
                        transformationAppliedThisStage = true
                        Log.d("WeirdCam", "Photo cropped to 1:1: ${bitmapForProcessing.width}x${bitmapForProcessing.height}")
                    }

                    var workingBitmap: Bitmap = bitmapForProcessing

                    val softwareZoomFactor = if (cameraRealMaxZoom > 0f && currentUiZoomLevel > cameraRealMaxZoom) {
                        (currentUiZoomLevel / cameraRealMaxZoom).coerceAtLeast(1f)
                    } else 1.0f

                    if (softwareZoomFactor > 1.01f) {
                        Log.d("WeirdCam", "Applying photo software zoom: $softwareZoomFactor")
                        val oWidth = workingBitmap.width; val oHeight = workingBitmap.height
                        val nWidth = (oWidth / softwareZoomFactor).roundToInt(); val nHeight = (oHeight / softwareZoomFactor).roundToInt()
                        if (nWidth > 0 && nHeight > 0 && nWidth <= oWidth && nHeight <= oHeight) {
                            val cropX = (oWidth - nWidth) / 2; val cropY = (oHeight - nHeight) / 2
                            val cropped = Bitmap.createBitmap(workingBitmap, cropX, cropY, nWidth, nHeight)
                            val scaled = cropped.scale(oWidth, oHeight, true)

                            if (workingBitmap != bitmapForProcessing) workingBitmap.recycle()
                            workingBitmap = scaled
                            transformationAppliedThisStage = true
                            cropped.recycle()
                        } else Log.w("WeirdCam", "Invalid software zoom dims for photo. Skipping.")
                    }

                    val matrix = Matrix()
                    var matrixTransformationNeeded = false
                    if (isInvertedVertical) {
                        matrix.postRotate(180f, workingBitmap.width / 2f, workingBitmap.height / 2f)
                        matrixTransformationNeeded = true;
                    }
                    if (isMirrored) {
                        matrix.postScale(-1f, 1f, workingBitmap.width / 2f, workingBitmap.height / 2f)
                        matrixTransformationNeeded = true;
                    }

                    if (matrixTransformationNeeded) {
                        Log.d("WeirdCam", "Applying matrix transformations to photo.")
                        val transformedResult = Bitmap.createBitmap(workingBitmap, 0, 0, workingBitmap.width, workingBitmap.height, matrix, true)
                        if (workingBitmap != bitmapForProcessing && workingBitmap != transformedResult) workingBitmap.recycle()
                        workingBitmap = transformedResult
                        transformationAppliedThisStage = true
                    }

                    if (transformationAppliedThisStage) {
                        Log.d("WeirdCam", "Saving processed bitmap to $savedUri")
                        context.contentResolver.openOutputStream(savedUri)?.use { out ->
                            workingBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        } ?: Log.e("WeirdCam", "Failed to open output stream for processed photo.")
                    } else Log.d("WeirdCam", "No transformations applied to photo beyond initial CameraX capture.")

                    if (workingBitmap != sourceBitmap && workingBitmap != bitmapForProcessing) workingBitmap.recycle()
                    if (bitmapForProcessing != sourceBitmap) bitmapForProcessing.recycle()
                    sourceBitmap.recycle()

                    Log.d("WeirdCam", "Photo processing finished for $savedUri.")
                } catch (e: Exception) { Log.e("WeirdCam", "Error processing photo: ${e.message}", e) }
            }
        }
    })
}

private fun startVideoRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    executor: ExecutorService,
    onRecordEvent: (VideoRecordEvent) -> Unit
): Recording? {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WeirdCam")
        }
    }
    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
        context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValues).build()

    var pendingRecording: Recording? = null
    try {
        val audioEnabled = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d("WeirdCam", "Starting video recording. Audio enabled: $audioEnabled")

        val activeRecording = videoCapture.output.prepareRecording(context, mediaStoreOutputOptions)
        if (audioEnabled) activeRecording.withAudioEnabled()
        pendingRecording = activeRecording.start(executor, onRecordEvent)

    } catch (e: SecurityException) {
        Log.e("WeirdCam", "Video recording SecurityException (Permissions?): ${e.localizedMessage}", e)
    } catch (e: Exception) {
        Log.e("WeirdCam", "Failed to start video recording (generic Exception): ${e.localizedMessage}", e)
    }
    return pendingRecording
}