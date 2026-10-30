package dev.rohit.labelaudit;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.util.*;
public class MainActivity extends ComponentActivity {
 private AuditApp app;private LinearLayout history;private TextView status;private EditText sample;private final List<Button> actions=new ArrayList<>();private String pendingExport;private String captureSample="";
 private final ActivityResultLauncher<Intent> camera=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
  if(result.getResultCode()!=RESULT_OK || result.getData()==null)return;Intent d=result.getData();String mode=d.getStringExtra("mode");
  JSONObject j=app.begin(mode,d.getStringExtra("photo"),d.getStringExtra("raw_code"),d.getIntExtra("format",0),captureSample);
  if(d.hasExtra("capture_error")){AuditApp.put(j,"capture_error",d.getStringExtra("capture_error"));app.store.save(j);}
  busy(true,"barcode".equals(mode)?"Looking up Open Food Facts…":"Reading the label on your phone…");
  app.worker.execute(()->{if("barcode".equals(mode))app.lookup(j);else app.ocr(j);runOnUiThread(()->{if(isDestroyed())return;busy(false,"Saved locally · "+j.optString("status"));refresh();show(j);});});
 });
 private final ActivityResultLauncher<String> save=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"),uri->{
  if(uri==null){busy(false,"Export kept locally. Tap Share last export to send it.");return;}if(pendingExport==null)return;String path=pendingExport;busy(true,"Saving archive…");
  app.worker.execute(()->{try(InputStream in=new FileInputStream(path);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Unable to open destination");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);runOnUiThread(()->busy(false,"Archive saved. You can attach it to this chat."));}catch(Exception e){runOnUiThread(()->busy(false,"Save failed: "+e.getMessage()+". Use Share last export to retry."));}});
 });
 @Override public void onCreate(Bundle state){super.onCreate(state);app=(AuditApp)getApplication();
  pendingExport=getPreferences(0).getString("last_export",null);if(state!=null){captureSample=state.getString("capture_sample","");pendingExport=state.getString("pending_export",pendingExport);}
  ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(22),dp(24),dp(22),dp(24));root.setBackgroundColor(0xfff4f5ef);scroll.addView(root);
  TextView badge=text("FIELD TEST · 01",12);badge.setTextColor(0xff196b52);root.addView(badge);
  TextView title=text("Label Audit",32);title.setTypeface(null,1);root.addView(title);
  root.addView(text("Scan. Capture. Compare.",18));
  TextView intro=text("Collect real packaging data for your nutrition app. Photos and OCR stay on this phone. Barcode numbers are sent to Open Food Facts.",15);intro.setPadding(0,dp(12),0,dp(16));root.addView(intro);
  sample=new EditText(this);sample.setSingleLine(true);sample.setHint("Optional sample name · e.g. tomatoes");sample.setId(9001);root.addView(sample);
  if(state!=null)sample.setText(state.getString("sample",""));
  button(root,"Scan barcode / QR code",()->launch("barcode"));
  button(root,"Photograph nutrition label",()->launch("ocr"));
  button(root,"Enter barcode manually",this::manual);
  root.addView(text("Use the same sample name for a barcode and its label photo so they are easy to compare.",13));
  status=text("Ready · bundled on-device ML Kit",14);status.setPadding(0,dp(16),0,dp(12));root.addView(status);
  button(root,"Export logs (.tar.gz)",this::export);
  button(root,"Share last export",this::share);
  TextView recent=text("Saved captures",23);recent.setPadding(0,dp(22),0,dp(8));root.addView(recent);
  history=new LinearLayout(this);history.setOrientation(LinearLayout.VERTICAL);root.addView(history);
  TextView credit=text("Food data: Open Food Facts · ODbL\nRaw OCR only — no nutrition parsing.\nVersion 0.1.0 · Photos remain until app data is cleared or the app is uninstalled.",12);credit.setPadding(0,dp(20),0,dp(12));root.addView(credit);setContentView(scroll);refresh();
 }
 @Override protected void onResume(){super.onResume();if(app!=null && history!=null)refresh();}
 @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("capture_sample",captureSample);b.putString("pending_export",pendingExport);b.putString("sample",sample.getText().toString());}
 private void launch(String mode){captureSample=sample.getText().toString().trim();camera.launch(new Intent(this,CaptureActivity.class).putExtra("mode",mode));}
 private void manual(){EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);new AlertDialog.Builder(this).setTitle("Product barcode number").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Look up",(d,w)->{
  JSONObject j=app.begin("barcode",null,input.getText().toString().trim(),0,sample.getText().toString().trim());AuditApp.put(j,"source","manual");app.store.save(j);busy(true,"Looking up Open Food Facts…");app.worker.execute(()->{app.lookup(j);runOnUiThread(()->{if(isDestroyed())return;busy(false,"Saved locally · "+j.optString("status"));refresh();show(j);});});}).show();}
 private void export(){busy(true,"Packing all records and original photos…");app.worker.execute(()->{try{File f=app.export();runOnUiThread(()->{pendingExport=f.getAbsolutePath();getPreferences(0).edit().putString("last_export",pendingExport).apply();if(isDestroyed())return;busy(false,"Export ready · "+(f.length()/1024)+" KB");save.launch(f.getName());});}catch(Exception e){runOnUiThread(()->busy(false,"Export failed: "+e.getMessage()));}});}
 private void share(){if(pendingExport==null || !new File(pendingExport).isFile()){Toast.makeText(this,"Export logs first.",Toast.LENGTH_SHORT).show();return;}
  Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",new File(pendingExport));Intent i=new Intent(Intent.ACTION_SEND).setType("application/gzip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("Label Audit export",uri));startActivity(Intent.createChooser(i,"Send audit archive"));}
 private void refresh(){history.removeAllViews();List<JSONObject> records=app.store.all();if(records.isEmpty()){history.addView(text("No captures yet. Start with a product barcode, then photograph its nutrition label.",15));return;}
  for(JSONObject j:records){Button b=new Button(this);b.setAllCaps(false);b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);String name=j.optString("sample");if(name.isEmpty())name=j.optString("product_name",j.optString("raw_code","Nutrition label"));b.setText(name+"\n"+j.optString("kind")+" · "+j.optString("status")+" · "+j.optString("created_at"));b.setTextSize(14);b.setOnClickListener(v->show(j));history.addView(b,new LinearLayout.LayoutParams(-1,-2));}
 }
 private void show(JSONObject j){String body;
  if("ocr".equals(j.optString("kind")))body=j.optString("text",j.optString("error","No text returned."));
  else {body="Code: "+j.optString("raw_code")+"\nStatus: "+j.optString("status")+"\n"+j.optString("error","");try{JSONObject response=new JSONObject(j.optString("response_body","{}"));JSONObject p=response.optJSONObject("product");if(p!=null)body+="\n\n"+p.optString("product_name")+"\n"+p.optString("brands")+"\nServing: "+p.optString("serving_size","not listed")+"\n\nRaw nutriments:\n"+(p.optJSONObject("nutriments")==null?"Not supplied":p.getJSONObject("nutriments").toString(2));}catch(Exception ignored){}}
  TextView t=text(body,15);t.setTextIsSelectable(true);t.setPadding(dp(18),dp(12),dp(18),dp(12));ScrollView s=new ScrollView(this);s.addView(t);
  AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("ocr".equals(j.optString("kind"))?"Raw ML Kit OCR":"Open Food Facts result").setView(s).setPositiveButton("Done",null);
  if(!j.isNull("photo"))dialog.setNeutralButton("View photo",(d,w)->{File f=new File(getFilesDir(),j.optString("photo"));Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",f);try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(ActivityNotFoundException e){Toast.makeText(this,"No photo viewer installed. The image is included in exports.",Toast.LENGTH_LONG).show();}});dialog.show();
 }
 private void busy(boolean value,String message){if(isDestroyed())return;for(Button b:actions)b.setEnabled(!value);status.setText(message);}
 private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xff203b31);return t;}
 private void button(LinearLayout root,String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(16);b.setPadding(dp(8),dp(12),dp(8),dp(12));b.setOnClickListener(v->action.run());root.addView(b,new LinearLayout.LayoutParams(-1,-2));actions.add(b);}
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
}
