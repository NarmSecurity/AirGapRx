package com.airgaprx.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgaprx.app.ui.common.appViewModel
import com.airgaprx.core.pipeline.RecaptureReason
import com.google.mlkit.vision.common.InputImage

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(onBack: () -> Unit, onRecognised: () -> Unit, onManualAdd: () -> Unit) {
    val vm = appViewModel { CaptureViewModel(it) }
    val manual = { vm.prepareManual(); onManualAdd() }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    // Gallery path: same pipeline, used for repeatable evaluation with a fixed photo set (criteria 2-5).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()?.let { vm.onImage(it, shutterAtNanos = null) {} }
    }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    if (hasPermission) {
        DisposableEffect(lifecycleOwner) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }, ContextCompat.getMainExecutor(context))
            onDispose { runCatching { future.get().unbindAll() } }
        }
    }

    LaunchedEffect(state) { if (state == CaptureState.Recognised) { vm.reset(); onRecognised() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan a label") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasPermission) {
                AndroidView({ previewView }, Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera access is needed to read labels. You can also choose a photo or add by name.", textAlign = TextAlign.Center)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }

            Text(
                "Fill the frame with the ingredients panel. Hold steady, avoid glare.",
                color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f)).padding(12.dp),
            )

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.PhotoLibrary, "Choose a photo", tint = Color.White)
                }
                FilledIconButton(
                    onClick = {
                        val shutterAt = android.os.SystemClock.elapsedRealtimeNanos()
                        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val media = image.image
                                if (media == null) { image.close(); return }
                                vm.onImage(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees), shutterAt) { image.close() }
                            }
                            override fun onError(exception: ImageCaptureException) = Unit
                        })
                    },
                    enabled = hasPermission && state != CaptureState.Processing,
                    modifier = Modifier.size(72.dp),
                ) { Icon(Icons.Outlined.Camera, "Take photo", Modifier.size(36.dp)) }
                OutlinedButton(onClick = manual) { Text("By name", color = Color.White) }
            }

            if (state == CaptureState.Processing) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Reading label on this phone…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    when (val s = state) {
        is CaptureState.Retake -> AlertDialog(
            onDismissRequest = vm::reset,
            title = { Text("Let's try that again") },
            text = { Text(retakeMessage(s.reason, s.confidence)) },
            confirmButton = { TextButton(onClick = vm::reset) { Text("Retake") } },
            dismissButton = { TextButton(onClick = { vm.reset(); manual() }) { Text("Add by name") } },
        )
        is CaptureState.Error -> AlertDialog(
            onDismissRequest = vm::reset,
            title = { Text("Something went wrong") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::reset) { Text("OK") } },
        )
        else -> Unit
    }
}

private fun retakeMessage(reason: RecaptureReason, confidence: Float?): String = when (reason) {
    RecaptureReason.NO_TEXT -> "No text was found. Move closer so the ingredients panel fills the frame."
    RecaptureReason.LOW_CONFIDENCE ->
        "The text was hard to read (confidence ${"%.0f".format((confidence ?: 0f) * 100)}%). " +
            "Check focus, avoid glare and hold the label flat. Nothing from this photo was added."
    RecaptureReason.NO_INGREDIENTS -> "No ingredients were found on this side of the pack. Try the side with the ingredients list."
}
