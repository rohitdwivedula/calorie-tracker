package dev.rohit.labelaudit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CaptureActivity : ComponentActivity() {
    private var status by mutableStateOf("Place a barcode inside the camera view")
    private var allowed by mutableStateOf(false)
    private var preview by mutableStateOf<PreviewView?>(null)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torch by mutableStateOf(false)
    private var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    private var canFlip by mutableStateOf(false)
    private var hasFlash by mutableStateOf(false)
    private var readingPhoto by mutableStateOf(false)
    @Volatile private var galleryActive = false
    private var galleryScanner: BarcodeScanner? = null
    private val gallery =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) galleryActive = false else readPhoto(uri)
        }

    private var scanner: BarcodeScanner? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var done = false
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            allowed = ok
            if (!ok) status = "Camera access is off. You can still use manual entry."
        }

    private fun result(action: String) {
        done = true
        setResult(RESULT_OK, Intent().putExtra("action", action))
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        lensFacing =
            state?.getInt("lensFacing", CameraSelector.LENS_FACING_BACK)
                ?: CameraSelector.LENS_FACING_BACK
        allowed =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        setContent {
            TrackerTheme {
                LaunchedEffect(allowed, preview, lensFacing) {
                    if (allowed && preview != null) startCamera(preview!!)
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    if (intent.getBooleanExtra("ingredient", false))
                                        "Scan ingredient"
                                    else "Quick log"
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.ArrowBack, "Back")
                                }
                            },
                            actions = {
                                IconButton(
                                    enabled = hasFlash && !readingPhoto,
                                    onClick = {
                                        torch = !torch
                                        camera?.cameraControl?.enableTorch(torch)
                                    },
                                ) {
                                    Icon(
                                        if (torch) Icons.Default.FlashOn
                                        else Icons.Default.FlashOff,
                                        "Toggle light",
                                    )
                                }
                            },
                        )
                    }
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large)) {
                            if (allowed)
                                AndroidView(
                                    factory = { context ->
                                        PreviewView(context).apply {
                                            implementationMode =
                                                PreviewView.ImplementationMode.COMPATIBLE
                                            preview = this
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            else
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.CameraAlt,
                                            null,
                                            Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text("Use the camera or enter food manually")
                                        FilledTonalButton(
                                            onClick = {
                                                permission.launch(Manifest.permission.CAMERA)
                                            }
                                        ) {
                                            Text("Allow camera")
                                        }
                                    }
                                }
                            FilledTonalIconButton(
                                onClick = {
                                    torch = false
                                    hasFlash = false
                                    camera?.cameraControl?.enableTorch(false)
                                    lensFacing =
                                        if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                            CameraSelector.LENS_FACING_FRONT
                                        else CameraSelector.LENS_FACING_BACK
                                },
                                enabled = allowed && canFlip && !readingPhoto,
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                            ) {
                                Icon(Icons.Default.Cameraswitch, "Flip camera")
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    galleryActive = true
                                    try {
                                        gallery.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    } catch (_: Exception) {
                                        galleryActive = false
                                        status =
                                            "Couldn't open photos. Try again or enter a barcode."
                                    }
                                },
                                enabled = !readingPhoto,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            ) {
                                Icon(Icons.Default.PhotoLibrary, "Choose barcode image")
                            }
                            if (readingPhoto)
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (intent.getBooleanExtra("quick_log", false))
                            Button(
                                onClick = { result("calories") },
                                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add calories")
                            }
                        FilledTonalButton(
                            onClick = { result("saved_foods") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Default.Search, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Search foods")
                        }
                        if (intent.getBooleanExtra("ingredient", false))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { result("manual_food") },
                                    Modifier.weight(1f),
                                ) {
                                    Text("Manual food")
                                }
                            }
                        TextButton(
                            onClick = { result("manual_barcode") },
                            Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Dialpad, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Enter barcode")
                        }
                    }
                }
            }
        }
        if (!allowed) permission.launch(Manifest.permission.CAMERA)
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun startCamera(view: PreviewView) {
        val selectedLens = lensFacing
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    if (isDestroyed || done || selectedLens != lensFacing) return@addListener
                    provider = future.get()
                    provider!!.unbindAll()
                    val display = Preview.Builder().build()
                    display.setSurfaceProvider(view.surfaceProvider)
                    if (scanner == null) scanner = BarcodeScanning.getClient()
                    val liveScanner = scanner!!
                    canFlip =
                        provider!!.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) &&
                            provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    val analyzer =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analyzer.setAnalyzer(executor) { proxy ->
                        if (done || galleryActive || !busy.compareAndSet(false, true)) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        val media = proxy.image
                        if (media == null) {
                            proxy.close()
                            busy.set(false)
                            return@setAnalyzer
                        }
                        liveScanner
                            .process(
                                InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            )
                            .addOnSuccessListener { codes ->
                                if (!done && !galleryActive && selectedLens == lensFacing) {
                                    codes
                                        .firstNotNullOfOrNull {
                                            BarcodeValue.productCode(it.rawValue)
                                        }
                                        ?.let(::barcodeResult)
                                }
                            }
                            .addOnFailureListener {
                                if (!done && !galleryActive)
                                    status = "Try better light, or use manual entry."
                            }
                            .addOnCompleteListener {
                                proxy.close()
                                busy.set(false)
                            }
                    }
                    camera =
                        provider!!.bindToLifecycle(
                            this,
                            CameraSelector.Builder().requireLensFacing(selectedLens).build(),
                            display,
                            analyzer,
                        )
                    hasFlash = camera!!.cameraInfo.hasFlashUnit()
                    torch = false
                } catch (e: Exception) {
                    hasFlash = false
                    status = "Camera unavailable. You can choose a photo or enter a barcode."
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun barcodeResult(code: String) {
        if (done || isDestroyed) return
        done = true
        setResult(RESULT_OK, Intent().putExtra("raw_code", code))
        finish()
    }

    private fun readPhoto(uri: Uri) {
        if (done || isDestroyed) return
        galleryActive = true
        readingPhoto = true
        status = "Reading barcode from photo…"
        executor.execute {
            try {
                val image = InputImage.fromFilePath(this, uri)
                runOnUiThread {
                    if (!done && !isDestroyed) {
                        try {
                            val reader = BarcodeScanning.getClient()
                            galleryScanner = reader
                            reader
                                .process(image)
                                .addOnSuccessListener { codes ->
                                    if (!done && !isDestroyed) {
                                        val code =
                                            codes.firstNotNullOfOrNull {
                                                BarcodeValue.productCode(it.rawValue)
                                            }
                                        if (code != null) barcodeResult(code)
                                        else
                                            status =
                                                "No food barcode found. Try another photo or enter a barcode."
                                    }
                                }
                                .addOnFailureListener { photoFailure() }
                                .addOnCompleteListener {
                                    reader.close()
                                    if (galleryScanner === reader) galleryScanner = null
                                    readingPhoto = false
                                    galleryActive = false
                                }
                        } catch (_: Exception) {
                            galleryScanner?.close()
                            galleryScanner = null
                            photoFailure()
                            readingPhoto = false
                            galleryActive = false
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    photoFailure()
                    readingPhoto = false
                    galleryActive = false
                }
            }
        }
    }

    private fun photoFailure() {
        if (!done && !isDestroyed)
            status = "Couldn't read that photo. Try another or enter a barcode."
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("lensFacing", lensFacing)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        done = true
        provider?.unbindAll()
        scanner?.close()
        galleryScanner?.close()
        executor.shutdown()
        super.onDestroy()
    }
}
