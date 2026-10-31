package dev.rohit.labelaudit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        allowed =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        setContent {
            TrackerTheme {
                LaunchedEffect(allowed, preview) {
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
                                    onClick = {
                                        torch = !torch
                                        camera?.cameraControl?.enableTorch(torch)
                                    }
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
                        if (allowed)
                            AndroidView(
                                factory = { context ->
                                    PreviewView(context).apply {
                                        implementationMode =
                                            PreviewView.ImplementationMode.COMPATIBLE
                                        preview = this
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.large),
                            )
                        else
                            Box(
                                Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
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
                                        onClick = { permission.launch(Manifest.permission.CAMERA) }
                                    ) {
                                        Text("Allow camera")
                                    }
                                }
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
                        if (intent.getBooleanExtra("ingredient", false))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledTonalButton(
                                    onClick = { result("saved_foods") },
                                    Modifier.weight(1f),
                                ) {
                                    Text("Saved foods")
                                }
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
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    if (isDestroyed || done) return@addListener
                    provider = future.get()
                    provider!!.unbindAll()
                    val display = Preview.Builder().build()
                    display.setSurfaceProvider(view.surfaceProvider)
                    scanner?.close()
                    scanner = BarcodeScanning.getClient()
                    val analyzer =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analyzer.setAnalyzer(executor) { proxy ->
                        if (done || !busy.compareAndSet(false, true)) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        val media = proxy.image
                        if (media == null) {
                            proxy.close()
                            busy.set(false)
                            return@setAnalyzer
                        }
                        scanner!!
                            .process(
                                InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            )
                            .addOnSuccessListener { codes ->
                                if (!done) {
                                    val code = codes.firstOrNull { it.rawValue != null }?.rawValue
                                    if (code != null) {
                                        done = true
                                        setResult(RESULT_OK, Intent().putExtra("raw_code", code))
                                        finish()
                                    }
                                }
                            }
                            .addOnFailureListener {
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
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            display,
                            analyzer,
                        )
                } catch (e: Exception) {
                    status = "Camera unavailable. Manual entry is still available."
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onDestroy() {
        done = true
        provider?.unbindAll()
        scanner?.close()
        executor.shutdown()
        super.onDestroy()
    }
}
