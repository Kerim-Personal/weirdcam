package com.example.weirdcam

import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
// import androidx.compose.runtime.rememberUpdatedState // Unused import
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
// import androidx.compose.ui.platform.LocalLifecycleOwner // Deprecated
import androidx.lifecycle.compose.LocalLifecycleOwner // Correct import
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.fillMaxSize // This import is correct
// import androidx.core.content.ContextCompat // Unused import

@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current // Now uses the correct LocalLifecycleOwner
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // rotationY = 180f // Mirror the preview horizontally - uncomment if mirroring is desired
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind any previous bindings to avoid conflicts
                cameraProviderFuture.get().unbindAll()
                // Bind the camera to the lifecycle
                cameraProviderFuture.get().bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )
            } catch (e: Exception) {
                Log.e("WeirdCam", "Camera binding failed", e)
                // Optionally, handle the error in the UI (e.g., show a Toast or update UI state)
            }

            previewView
        },
        update = { /* Update logic if needed (e.g., for configuration changes) */ },
        modifier = modifier.fillMaxSize() // This usage is correct
    )

    // Clean up camera resources when the composable is disposed
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.e("WeirdCam", "Failed to unbind camera", e)
            }
        }
    }
}