package dev.rohit.labelaudit;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.*;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class CaptureActivity extends ComponentActivity {
 private PreviewView preview;private TextView hint;private Button shutter;private ImageCapture capture;private ProcessCameraProvider provider;private androidx.camera.core.Camera camera;
 private final ExecutorService analyzer=Executors.newSingleThreadExecutor();
 private BarcodeScanner scanner;private final AtomicBoolean processing=new AtomicBoolean(false);private volatile boolean captured=false;private String mode;private boolean torch=false;
 private final ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),ok->{if(ok)startCamera();else{Toast.makeText(this,"Camera permission is needed to scan or photograph labels.",Toast.LENGTH_LONG).show();finish();}});
 @Override public void onCreate(Bundle state){super.onCreate(state);mode=getIntent().getStringExtra("mode");
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff182b25);root.setPadding(16,16,16,16);
  hint=new TextView(this);hint.setTextColor(0xffffffff);hint.setTextSize(18);hint.setPadding(12,18,12,18);hint.setText("barcode".equals(mode)?"Point at a barcode or QR code\nIt will capture automatically.":"Fill the frame with the nutrition label.\nKeep small text sharp and avoid glare.");root.addView(hint);
  preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout controls=new LinearLayout(this);
  Button back=new Button(this);back.setText("Cancel");back.setOnClickListener(v->finish());controls.addView(back,new LinearLayout.LayoutParams(0,-2,1));
  Button light=new Button(this);light.setText("Light");light.setOnClickListener(v->{if(camera!=null && camera.getCameraInfo().hasFlashUnit()){torch=!torch;camera.getCameraControl().enableTorch(torch);}});controls.addView(light,new LinearLayout.LayoutParams(0,-2,1));
  root.addView(controls);
  shutter=new Button(this);shutter.setText("Take label photo");shutter.setEnabled(false);shutter.setOnClickListener(v->savePhoto(null,0));if(!"barcode".equals(mode))root.addView(shutter);
  setContentView(root);
  preview.setOnTouchListener((v,event)->{if(event.getAction()==android.view.MotionEvent.ACTION_UP && camera!=null){MeteringPoint p=preview.getMeteringPointFactory().createPoint(event.getX(),event.getY());camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(p).build());v.performClick();}return true;});
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else permission.launch(Manifest.permission.CAMERA);
 }
 private void startCamera(){ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);future.addListener(()->{try{
   provider=future.get();Preview p=new Preview.Builder().build();p.setSurfaceProvider(preview.getSurfaceProvider());
   capture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();
   if("barcode".equals(mode)){
    scanner=BarcodeScanning.getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13,Barcode.FORMAT_EAN_8,Barcode.FORMAT_UPC_A,Barcode.FORMAT_UPC_E,Barcode.FORMAT_QR_CODE,Barcode.FORMAT_CODE_128,Barcode.FORMAT_ITF,Barcode.FORMAT_DATA_MATRIX).build());
    ImageAnalysis a=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
    a.setAnalyzer(analyzer,proxy->{if(captured || !processing.compareAndSet(false,true)){proxy.close();return;}
     android.media.Image media=proxy.getImage();if(media==null){processing.set(false);proxy.close();return;}
     InputImage image=InputImage.fromMediaImage(media,proxy.getImageInfo().getRotationDegrees());
     scanner.process(image).addOnSuccessListener(result->{if(!captured && !result.isEmpty()){
       Barcode b=result.get(0);if(b.getRawValue()!=null){captured=true;a.clearAnalyzer();savePhoto(b.getRawValue(),b.getFormat());}
      }}).addOnFailureListener(e->hint.setText("Could not read this frame. Hold steady or try better light."))
      .addOnCompleteListener(task->{proxy.close();processing.set(false);});
    });
    camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,capture,a);
   }else camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,capture);
   shutter.setEnabled(true);
  }catch(Exception e){hint.setText("Camera unavailable: "+e.getMessage());}},ContextCompat.getMainExecutor(this));}
 private void savePhoto(String code,int format){if(capture==null)return;shutter.setEnabled(false);hint.setText("Saving original photo…");
  File dir=new File(getFilesDir(),"photos");dir.mkdirs();File file=new File(dir,UUID.randomUUID()+".jpg");
  capture.setTargetRotation(preview.getDisplay().getRotation());
  capture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(this),new ImageCapture.OnImageSavedCallback(){
   public void onImageSaved(ImageCapture.OutputFileResults result){Intent data=new Intent().putExtra("mode",mode).putExtra("photo",file.getAbsolutePath()).putExtra("raw_code",code).putExtra("format",format);setResult(RESULT_OK,data);finish();}
   public void onError(ImageCaptureException e){file.delete();if(code!=null){Intent data=new Intent().putExtra("mode",mode).putExtra("raw_code",code).putExtra("format",format).putExtra("capture_error",e.getMessage());setResult(RESULT_OK,data);finish();}else{hint.setText("Photo failed: "+e.getMessage()+". Try again.");shutter.setEnabled(true);}}
  });
 }
 @Override public void onDestroy(){super.onDestroy();if(provider!=null)provider.unbindAll();if(scanner!=null)scanner.close();analyzer.shutdown();}
}
