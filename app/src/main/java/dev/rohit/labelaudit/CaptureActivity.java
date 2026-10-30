package dev.rohit.labelaudit;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.barcode.*;
import com.google.mlkit.vision.common.InputImage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory barcode analysis only. No capture or photo storage. */
public class CaptureActivity extends ComponentActivity {
  PreviewView preview;
  TextView hint;
  ProcessCameraProvider provider;
  BarcodeScanner scanner;
  boolean done = false;
  final AtomicBoolean busy = new AtomicBoolean();
  final ExecutorService executor = Executors.newSingleThreadExecutor();
  final ActivityResultLauncher<String> permission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          ok -> {
            if (ok) start();
            else {
              hint.setText("Camera access is off. Use manual entry below.");
            }
          });

  public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    hint = new TextView(this);
    hint.setText("Scan a barcode");
    hint.setTextSize(20);
    root.addView(hint);
    preview = new PreviewView(this);
    preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
    root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
    if (getIntent().getBooleanExtra("quick_log", false)) {
      Button calories = new Button(this);
      calories.setText("Add calories");
      root.addView(calories);
      calories.setOnClickListener(
          v -> {
            done = true;
            setResult(RESULT_OK, new Intent().putExtra("action", "calories"));
            finish();
          });
    }
    Button manual = new Button(this);
    manual.setText("Enter barcode");
    root.addView(manual);
    manual.setOnClickListener(
        v -> {
          done = true;
          setResult(RESULT_OK, new Intent().putExtra("action", "manual_barcode"));
          finish();
        });
    Button cancel = new Button(this);
    cancel.setText("Cancel");
    cancel.setOnClickListener(v -> finish());
    root.addView(cancel);
    setContentView(root);
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) start();
    else permission.launch(Manifest.permission.CAMERA);
  }

  @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
  void start() {
    com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> f =
        ProcessCameraProvider.getInstance(this);
    f.addListener(
        () -> {
          try {
            provider = f.get();
            Preview p = new Preview.Builder().build();
            p.setSurfaceProvider(preview.getSurfaceProvider());
            scanner = BarcodeScanning.getClient();
            ImageAnalysis a =
                new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            a.setAnalyzer(
                executor,
                proxy -> {
                  if (done || !busy.compareAndSet(false, true)) {
                    proxy.close();
                    return;
                  }
                  android.media.Image image = proxy.getImage();
                  if (image == null) {
                    proxy.close();
                    busy.set(false);
                    return;
                  }
                  scanner
                      .process(
                          InputImage.fromMediaImage(
                              image, proxy.getImageInfo().getRotationDegrees()))
                      .addOnSuccessListener(
                          codes -> {
                            if (!done)
                              for (com.google.mlkit.vision.barcode.common.Barcode code : codes)
                                if (code.getRawValue() != null) {
                                  done = true;
                                  setResult(
                                      RESULT_OK,
                                      new Intent().putExtra("raw_code", code.getRawValue()));
                                  finish();
                                  break;
                                }
                          })
                      .addOnFailureListener(
                          e ->
                              hint.setText(
                                  "Cannot read this frame. Try better light or manual entry."))
                      .addOnCompleteListener(
                          t -> {
                            proxy.close();
                            busy.set(false);
                          });
                });
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, a);
          } catch (Exception e) {
            hint.setText("Camera unavailable. Go back and use manual entry in Foods.");
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  public void onDestroy() {
    super.onDestroy();
    done = true;
    if (provider != null) provider.unbindAll();
    if (scanner != null) scanner.close();
    executor.shutdown();
  }
}
