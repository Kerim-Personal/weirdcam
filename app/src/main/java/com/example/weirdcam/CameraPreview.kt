// CameraPreview.kt
package com.example.weirdcam

// import android.util.Size // Doğrudan kullanılmıyor
// import androidx.core.content.ContextCompat // Kullanılmıyorsa kaldırılabilir
// import androidx.core.graphics.scale as KTXBitmapScale // Bu alias artık kullanılmıyor gibi, Bitmap.scale direkt var
import android.annotation.SuppressLint
import android.content.ContentValues
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt


enum class FlashModeUi(val icon: ImageVector, val imageCaptureFlashMode: Int) {
    AUTO(Icons.Filled.FlashAuto, ImageCapture.FLASH_MODE_AUTO),
    ON(Icons.Filled.FlashOn, ImageCapture.FLASH_MODE_ON),
    OFF(Icons.Filled.FlashOff, ImageCapture.FLASH_MODE_OFF)
}

enum class TimerOptionUi(val displayName: String, val seconds: Int, val icon: ImageVector) {
    OFF("Kapalı", 0, Icons.Filled.TimerOff),
    S3("3s", 3, Icons.Filled.Timer3),
    S10("10s", 10, Icons.Filled.Timer10)
}
enum class AspectRatioUi(val displayName: String, val cameraXValue: Int?) {
    R4_3("4:3", AspectRatio.RATIO_4_3),
    R16_9("16:9", AspectRatio.RATIO_16_9),
    R1_1("1:1", null),
    FULL("Tam", null)
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
    var mirrorEffectEnabled by remember { mutableStateOf(false) }

    var currentFlashMode by remember { mutableStateOf(FlashModeUi.AUTO) }
    var selectedTimerOption by remember { mutableStateOf(TimerOptionUi.OFF) }
    var selectedAspectRatio by remember { mutableStateOf(AspectRatioUi.R4_3) }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }

    var targetUiZoomLevel by remember { mutableFloatStateOf(1f) }
    val animatedUiZoomLevel by animateFloatAsState(
        targetValue = targetUiZoomLevel,
        animationSpec = tween(durationMillis = 100),
        label = "animatedUiZoomLevel"
    )
    val maxZoomUi = 10f

    var minZoomHardware by remember { mutableFloatStateOf(1f) }
    var maxZoomHardware by remember { mutableFloatStateOf(maxZoomUi) }
    var sliderActualMinRange by remember { mutableFloatStateOf(1f) }

    var exposureCompensationIndex by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf(android.util.Range(0, 0)) }


    var focusPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var showFocusIndicator by remember { mutableStateOf(false) }
    var lastMediaUri by remember { mutableStateOf<String?>(null) }


    val previewUseCase = remember(selectedAspectRatio) {
        Preview.Builder().apply {
            selectedAspectRatio.cameraXValue?.let {
                // setTargetAspectRatio(it) // Deprecated ve FoV sorununa kesin çözüm olmadığı için yorumda
            }
        }.build()
    }

    val imageCaptureUseCase = remember(currentFlashMode, selectedAspectRatio) {
        ImageCapture.Builder()
            .setFlashMode(currentFlashMode.imageCaptureFlashMode)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).apply {
                selectedAspectRatio.cameraXValue?.let {
                    // setTargetAspectRatio(it) // Deprecated ve FoV sorununa kesin çözüm olmadığı için yorumda
                }
            }
            .build()
    }
    val recorder = remember {
        Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
    }
    val videoCaptureUseCase: VideoCapture<Recorder> = remember { VideoCapture.withOutput(recorder) }


    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(cameraProviderFuture, userRequestedLensFacing, lifecycleOwner) {
        val cameraProvider = cameraProviderFuture.get()
        try {
            cameraProvider.unbindAll()
            val localCameraSelector = if (userRequestedLensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            mirrorEffectEnabled = (userRequestedLensFacing == CameraSelector.LENS_FACING_FRONT)

            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, localCameraSelector, previewUseCase, imageCaptureUseCase, videoCaptureUseCase
            )
            cameraControl = camera.cameraControl
            this.cameraInfo = camera.cameraInfo // composable scope'taki cameraInfo'yu güncelle

            this.cameraInfo?.zoomState?.value?.let { currentZoomState ->
                minZoomHardware = currentZoomState.minZoomRatio
                maxZoomHardware = currentZoomState.maxZoomRatio.coerceAtMost(maxZoomUi)
                sliderActualMinRange = minZoomHardware.coerceAtLeast(1f)
                targetUiZoomLevel = targetUiZoomLevel.coerceIn(sliderActualMinRange, maxZoomHardware)
            }
            this.cameraInfo?.exposureState?.let { exposureState ->
                exposureRange = exposureState.exposureCompensationRange
                exposureCompensationIndex = exposureCompensationIndex.coerceIn(exposureRange.lower, exposureRange.upper)
                cameraControl?.setExposureCompensationIndex(exposureCompensationIndex)
            }
            Log.d("WeirdCam", "Kamera bağlandı. MinZoom=$minZoomHardware, MaxZoom=$maxZoomHardware, KaydırıcıMin=$sliderActualMinRange, UIHedef=$targetUiZoomLevel")
        } catch (e: Exception) {
            Log.e("WeirdCam", "Kamera bağlama hatası: ${e.localizedMessage}", e)
        }
    }

    val actualHardwareZoomToApply = remember(animatedUiZoomLevel, minZoomHardware, maxZoomHardware) {
        animatedUiZoomLevel.coerceIn(minZoomHardware, maxZoomHardware)
    }
    LaunchedEffect(actualHardwareZoomToApply, cameraControl) { cameraControl?.setZoomRatio(actualHardwareZoomToApply) }
    LaunchedEffect(exposureCompensationIndex, cameraControl) { cameraControl?.setExposureCompensationIndex(exposureCompensationIndex) }

    var currentPreviewView: PreviewView? by remember { mutableStateOf(null) }

    val scaleGestureDetector = remember(context, sliderActualMinRange, maxZoomHardware) {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                targetUiZoomLevel = (targetUiZoomLevel * detector.scaleFactor).coerceIn(sliderActualMinRange, maxZoomHardware)
                return true
            }
        })
    }
    val tapGestureDetector = remember(context, cameraControl) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                currentPreviewView?.let { view ->
                    val factory = SurfaceOrientedMeteringPointFactory(view.width.toFloat(), view.height.toFloat())
                    val action = FocusMeteringAction.Builder(factory.createPoint(e.x, e.y), FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                    cameraControl?.startFocusAndMetering(action)
                    focusPoint = Pair(e.x, e.y); showFocusIndicator = true
                }
                return true
            }
        })
    }

    LaunchedEffect(showFocusIndicator) { if (showFocusIndicator) { delay(1000); showFocusIndicator = false } }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    currentPreviewView = this
                    previewUseCase.setSurfaceProvider(surfaceProvider)
                    setOnTouchListener { _, event ->
                        scaleGestureDetector.onTouchEvent(event)
                        tapGestureDetector.onTouchEvent(event)
                        true
                    }
                }
            },
            update = { view ->
                currentPreviewView = view
                view.scaleX = if (mirrorEffectEnabled) -1f else 1f
                view.scaleY = 1f
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showFocusIndicator && focusPoint != null && currentPreviewView != null) {
            Box(
                modifier = Modifier
                    .offset {
                        val point = focusPoint ?: return@offset IntOffset.Zero
                        currentPreviewView ?: return@offset IntOffset.Zero
                        // Ayna efekti PreviewView'ın scaleX'i ile halledildiği için burada ayrıca -1 ile çarpmaya gerek yok.
                        // Koordinatlar doğrudan view üzerinden alınmalı.
                        val xPx = point.first - 25.dp.toPx()
                        val yPx = point.second - 25.dp.toPx()
                        IntOffset(xPx.roundToInt(), yPx.roundToInt())
                    }
                    .size(50.dp)
                    .border(2.dp, Color.White, CircleShape)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                currentFlashMode = when (currentFlashMode) {
                    FlashModeUi.AUTO -> FlashModeUi.ON
                    FlashModeUi.ON -> FlashModeUi.OFF
                    FlashModeUi.OFF -> FlashModeUi.AUTO
                }
            }) { Icon(currentFlashMode.icon, contentDescription = "Flaş", tint = Color.White) }
            var timerExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { timerExpanded = true }) { Icon(selectedTimerOption.icon, contentDescription = "Zamanlayıcı", tint = Color.White) }
                DropdownMenu(expanded = timerExpanded, onDismissRequest = { timerExpanded = false }) {
                    TimerOptionUi.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName) }, onClick = { selectedTimerOption = option; timerExpanded = false })
                    }
                }
            }
            var aspectRatioExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { aspectRatioExpanded = true }) { Icon(Icons.Filled.AspectRatio, contentDescription = "En-Boy Oranı", tint = Color.White) }
                DropdownMenu(expanded = aspectRatioExpanded, onDismissRequest = { aspectRatioExpanded = false }) {
                    AspectRatioUi.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName) }, onClick = { selectedAspectRatio = option; aspectRatioExpanded = false })
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.3f)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (exposureRange.lower < exposureRange.upper) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)){
                    Icon(Icons.Filled.BrightnessMedium, contentDescription = "Pozlama", tint = Color.White)
                    Slider(
                        value = exposureCompensationIndex.toFloat(),
                        onValueChange = { exposureCompensationIndex = it.roundToInt() },
                        valueRange = exposureRange.lower.toFloat()..exposureRange.upper.toFloat(),
                        steps = (exposureRange.upper - exposureRange.lower - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("$exposureCompensationIndex", color = Color.White)
                }
            }
            // Zoom kaydırıcısını göstermek için `cameraInfo?.isZoomControlSupported == true` kontrolü eklenebilir,
            // ancak bu referans çözümlenmiyorsa, şimdilik bu kontrol olmadan gösteriyoruz.
            // Eğer min ve max zoom eşitse (zoom desteklenmiyorsa) kaydırıcı zaten bir işe yaramaz.
            if (minZoomHardware < maxZoomHardware) {
                Slider(
                    value = targetUiZoomLevel,
                    onValueChange = { targetUiZoomLevel = it.coerceIn(sliderActualMinRange, maxZoomHardware) },
                    valueRange = sliderActualMinRange..maxZoomHardware,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp)
                )
            }
            Text("Zoom: %.1fx".format(animatedUiZoomLevel), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
                    lastMediaUri?.let {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "Son Medya", tint = Color.White, modifier = Modifier.fillMaxSize().padding(4.dp))
                    }
                }
                Button(
                    onClick = {
                        val lensFacingForPhoto = userRequestedLensFacing // Mevcut lens yönünü yakala
                        val action = {
                            takePhoto(
                                context,
                                imageCaptureUseCase,
                                cameraExecutor,
                                lensFacingForPhoto == CameraSelector.LENS_FACING_FRONT, // isMirroredForFront buna göre belirlenir
                                selectedAspectRatio
                            ) { uri -> lastMediaUri = uri }
                        }
                        if (selectedTimerOption.seconds > 0) {
                            cameraExecutor.execute {
                                Thread.sleep(selectedTimerOption.seconds * 1000L)
                                action()
                            }
                        } else {
                            action()
                        }
                    },
                    modifier = Modifier.size(72.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Fotoğraf Çek", tint = Color.Black, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = {
                    userRequestedLensFacing = if (userRequestedLensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Kamera Değiştir", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }
}

private fun takePhoto(
    context: android.content.Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    isFrontCameraAndNeedsMirror: Boolean, // Ön kamera ise ve fotoğrafın aynalanması gerekiyorsa true
    aspectRatioToCrop: AspectRatioUi,
    onImageSaved: (String?) -> Unit
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
        override fun onError(exc: ImageCaptureException) {
            Log.e("WeirdCam", "Fotoğraf çekme hatası: ${exc.imageCaptureError}-${exc.message}", exc)
            onImageSaved(null)
        }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            val savedUri = output.savedUri
            if (savedUri == null) { onImageSaved(null); return }

            executor.execute {
                var initialDecodedBitmap: Bitmap? = null
                try {
                    context.contentResolver.openInputStream(savedUri)?.use { inputStream ->
                        initialDecodedBitmap = BitmapFactory.decodeStream(inputStream)
                    }

                    if (initialDecodedBitmap == null) {
                        Log.e("WeirdCam", "Bitmap çözümlenemedi: $savedUri"); onImageSaved(null); return@execute
                    }

                    var currentBitmap = initialDecodedBitmap!! // Artık null değil, bu işlenecek ana bitmap
                    var transformationApplied = false

                    // 1:1 Kırpma
                    if (aspectRatioToCrop == AspectRatioUi.R1_1) {
                        val originalWidth = currentBitmap.width
                        val originalHeight = currentBitmap.height
                        val size = min(originalWidth, originalHeight)
                        if (size > 0) {
                            val xOffset = (originalWidth - size) / 2
                            val yOffset = (originalHeight - size) / 2
                            val cropped = Bitmap.createBitmap(currentBitmap, xOffset, yOffset, size, size)
                            // Eğer currentBitmap, ilk çözülen bitmap'ten farklı bir ara adımdaysa (gelecekteki transformlar için)
                            // onu recycle et. Ama şu anki akışta currentBitmap hep initialDecodedBitmap'in yerini alıyor.
                            if (currentBitmap != initialDecodedBitmap) currentBitmap.recycle()
                            currentBitmap = cropped
                            transformationApplied = true
                        }
                    }

                    val matrix = Matrix()
                    var matrixNeedsUpdate = false
                    if (isFrontCameraAndNeedsMirror) { // Sadece ön kamera ise ve aynalama isteniyorsa
                        matrix.postScale(-1f, 1f, currentBitmap.width / 2f, currentBitmap.height / 2f)
                        matrixNeedsUpdate = true
                    }

                    if (matrixNeedsUpdate) {
                        val transformed = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, matrix, true)
                        if (currentBitmap != initialDecodedBitmap && currentBitmap != transformed) currentBitmap.recycle()
                        currentBitmap = transformed
                        transformationApplied = true
                    }

                    if (transformationApplied) {
                        context.contentResolver.openOutputStream(savedUri)?.use { outStream ->
                            currentBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
                        }
                    }

                    // Son kullanılan 'currentBitmap' eğer ilk çözülen 'initialDecodedBitmap' değilse,
                    // ve 'initialDecodedBitmap' artık kullanılmayacaksa recycle edilebilir.
                    // Ancak, eğer hiçbir transformasyon yapılmadıysa currentBitmap == initialDecodedBitmap olur.
                    // Bu durumda initialDecodedBitmap'i recycle etmek, kaydedilen dosyayı da etkileyebilir.
                    // En güvenli yol, eğer currentBitmap yeni bir nesne ise (transformed veya cropped),
                    // initialDecodedBitmap'i recycle etmektir.
                    if (currentBitmap != initialDecodedBitmap) {
                        initialDecodedBitmap?.recycle()
                    }
                    // 'currentBitmap' (son hali) ise zaten dosyaya yazıldı, burada recycle edilmemeli.

                    onImageSaved(savedUri.toString())
                } catch (e: Exception) {
                    Log.e("WeirdCam", "Fotoğrafı işleme hatası: $savedUri", e); onImageSaved(null)
                } finally {
                    // initialDecodedBitmap burada recycle edilmemeli eğer yukarıda zaten
                    // currentBitmap != initialDecodedBitmap kontrolü ile edildi veya edilmediyse.
                    // Karmaşıklığı azaltmak için, ara adımlarda oluşturulan bitmap'lerin recycle edildiğinden emin olalım.
                }
            }
        }
    })
}

