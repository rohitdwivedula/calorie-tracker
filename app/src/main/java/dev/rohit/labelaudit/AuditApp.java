package dev.rohit.labelaudit;
import android.app.Application;
import android.net.Uri;
import android.os.Build;
import android.graphics.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
public class AuditApp extends Application {
 public final ExecutorService worker=Executors.newSingleThreadExecutor();
 public Store store;
 public void onCreate(){super.onCreate();store=new Store(this);
  // Preserve interrupted records instead of silently dropping them after process death.
  for(JSONObject j:store.all())if("pending".equals(j.optString("status"))){put(j,"status","interrupted");put(j,"error","App process stopped before processing finished.");store.save(j);}
 }
 static void put(JSONObject j,String k,Object v){try{j.put(k,v);}catch(JSONException e){throw new IllegalStateException(e);}}
 public JSONObject begin(String kind,String photo,String raw,int format,String sample) {
  JSONObject j=new JSONObject();put(j,"schema_version",1);put(j,"id",UUID.randomUUID().toString());put(j,"created_at",Instant.now().toString());put(j,"kind",kind);put(j,"status","pending");put(j,"sample",sample);
  put(j,"photo",photo==null?JSONObject.NULL:"photos/"+new File(photo).getName());
  if(raw!=null){put(j,"raw_code",raw);put(j,"barcode_format",format);}
  store.save(j);return j;
 }
 public void lookup(JSONObject j){long t=System.nanoTime();HttpURLConnection conn=null;
  try {
   String code=BarcodeValue.productCode(j.optString("raw_code"));
   if(code==null){put(j,"status","unsupported_code");put(j,"error","No product number found. Use a UPC/EAN barcode, numeric QR, GS1 Digital Link, or Open Food Facts product link.");return;}
   put(j,"product_code",code);
   String url="https://world.openfoodfacts.org/api/v2/product/"+code+".json";
   put(j,"request_url",url);put(j,"request_method","GET");put(j,"request_started_at",Instant.now().toString());store.save(j);
   conn=(HttpURLConnection)new URL(url).openConnection();conn.setConnectTimeout(15000);conn.setReadTimeout(20000);conn.setRequestProperty("User-Agent","LabelAudit/0.1.0 (Android; personal nutrition-label evaluation)");conn.setRequestProperty("Accept","application/json");
   int status=conn.getResponseCode();put(j,"http_status",status);
   JSONObject headers=new JSONObject();for(Map.Entry<String,List<String>> h:conn.getHeaderFields().entrySet())if(h.getKey()!=null)put(headers,h.getKey(),new JSONArray(h.getValue()));put(j,"response_headers",headers);
   InputStream stream=status>=400?conn.getErrorStream():conn.getInputStream();String body="";
   if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>12*1024*1024)throw new IOException("Response exceeds 12 MiB limit");b.write(buf,0,n);}body=b.toString("UTF-8");}
   put(j,"response_body",body);
   if(status<200 || status>=300){put(j,"status","http_error");return;}
   JSONObject data=new JSONObject(body);put(j,"status",data.optInt("status")==1?"found":"not_found");
   if(data.optJSONObject("product")!=null)put(j,"product_name",data.getJSONObject("product").optString("product_name","Unnamed product"));
  }catch(Exception e){put(j,"status","error");put(j,"error",e.getClass().getSimpleName()+": "+e.getMessage());}
  finally{if(conn!=null)conn.disconnect();put(j,"elapsed_ms",(System.nanoTime()-t)/1000000);put(j,"finished_at",Instant.now().toString());store.save(j);}
 }
 public void ocr(JSONObject j){long t=System.nanoTime();TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  try {
   File f=new File(getFilesDir(),j.getString("photo"));
   InputImage image=InputImage.fromFilePath(this,Uri.fromFile(f));
   put(j,"ocr_engine","Google ML Kit Text Recognition / Latin / bundled 16.0.1");
   put(j,"input_width",image.getWidth());put(j,"input_height",image.getHeight());put(j,"rotation_degrees",image.getRotationDegrees());
   Text text=Tasks.await(recognizer.process(image),90,TimeUnit.SECONDS);put(j,"text",text.getText());
   JSONArray blocks=new JSONArray();
   for(Text.TextBlock b:text.getTextBlocks()){
    JSONObject block=segment(b.getText(),b.getBoundingBox(),b.getCornerPoints(),b.getRecognizedLanguage());JSONArray lines=new JSONArray();
    for(Text.Line l:b.getLines()){
     JSONObject line=segment(l.getText(),l.getBoundingBox(),l.getCornerPoints(),l.getRecognizedLanguage());put(line,"confidence",l.getConfidence());put(line,"angle",l.getAngle());JSONArray elements=new JSONArray();
     for(Text.Element e:l.getElements()){JSONObject element=segment(e.getText(),e.getBoundingBox(),e.getCornerPoints(),e.getRecognizedLanguage());put(element,"confidence",e.getConfidence());put(element,"angle",e.getAngle());elements.put(element);}
     put(line,"elements",elements);lines.put(line);
    }put(block,"lines",lines);blocks.put(block);
   }put(j,"blocks",blocks);put(j,"status",text.getText().isEmpty()?"no_text":"recognized");
  }catch(Exception e){put(j,"status","error");put(j,"error",e.getClass().getSimpleName()+": "+e.getMessage());}
  finally{recognizer.close();put(j,"elapsed_ms",(System.nanoTime()-t)/1000000);put(j,"finished_at",Instant.now().toString());store.save(j);}
 }
 private JSONObject segment(String text,Rect box,Point[] corners,String lang){JSONObject j=new JSONObject();put(j,"text",text);put(j,"language",lang);
  if(box!=null){JSONArray a=new JSONArray();a.put(box.left).put(box.top).put(box.right).put(box.bottom);put(j,"bounding_box_ltrb",a);}
  if(corners!=null){JSONArray a=new JSONArray();for(Point p:corners)a.put(new JSONArray().put(p.x).put(p.y));put(j,"corner_points",a);}return j;
 }
 public File export()throws Exception {
  List<JSONObject> records=store.all();File folder=new File(getCacheDir(),"exports");folder.mkdirs();File out=new File(folder,"label-audit-"+System.currentTimeMillis()+".tar.gz");
  JSONArray files=new JSONArray();StringBuilder logs=new StringBuilder();Set<String> photos=new HashSet<>();
  try(TarWriter tar=new TarWriter(new FileOutputStream(out))) {
   for(JSONObject j:records){String id=j.getString("id");logs.append(j.toString()).append('\n');
    add(tar,files,"records/"+id+".json",j.toString(2).getBytes(StandardCharsets.UTF_8));
    if(j.has("response_body"))add(tar,files,"responses/"+id+".json",j.getString("response_body").getBytes(StandardCharsets.UTF_8));
    if(j.has("text"))add(tar,files,"ocr/"+id+".txt",j.getString("text").getBytes(StandardCharsets.UTF_8));
    if(!j.isNull("photo"))photos.add(j.getString("photo"));
   }
   // Include every original capture, even an orphan from an interrupted capture flow.
   File photoDir=new File(getFilesDir(),"photos");File[] originals=photoDir.listFiles();if(originals!=null)for(File f:originals)if(f.isFile())photos.add("photos/"+f.getName());
   for(String path:photos){File f=new File(getFilesDir(),path);if(!f.exists())throw new IOException("Missing original photo: "+path);tar.add(path,f);JSONObject meta=new JSONObject();put(meta,"path",path);put(meta,"bytes",f.length());put(meta,"sha256",sha(f));files.put(meta);}
   add(tar,files,"logs.jsonl",logs.toString().getBytes(StandardCharsets.UTF_8));
   String readme="Label Audit v0.1.0\nAll captured events are in logs.jsonl and records/*.json. Photos are original CameraX JPEG files (not sensor RAW). Responses contain exact decoded UTF-8 HTTP bodies, including error bodies; some .json files may therefore contain non-JSON. OCR plain text is in ocr/*.txt; blocks/lines/elements and coordinates are in records. No nutrition parsing has been performed. Photo paths link records to originals. Coordinates follow the ML Kit input image and rotation metadata. Orphan photos may exist after interrupted capture. Timestamps are UTC. Manifest checksums cover all entries except manifest.json. This archive contains only this app's captures and results, not your gallery. Open Food Facts data: https://world.openfoodfacts.org/data (ODbL).\n";
   add(tar,files,"README.txt",readme.getBytes(StandardCharsets.UTF_8));
   JSONObject manifest=new JSONObject();put(manifest,"schema_version",1);put(manifest,"app_version",BuildConfig.VERSION_NAME);put(manifest,"created_at",Instant.now().toString());put(manifest,"device_model",Build.MODEL);put(manifest,"android_version",Build.VERSION.RELEASE);put(manifest,"record_count",records.size());put(manifest,"photo_count",photos.size());put(manifest,"files",files);
   tar.add("manifest.json",manifest.toString(2).getBytes(StandardCharsets.UTF_8));
  }catch(Exception e){out.delete();throw e;}return out;
 }
 private void add(TarWriter tar,JSONArray list,String path,byte[] data)throws Exception{tar.add(path,data);JSONObject j=new JSONObject();put(j,"path",path);put(j,"bytes",data.length);put(j,"sha256",hex(MessageDigest.getInstance("SHA-256").digest(data)));list.put(j);}
 private String sha(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
 private String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}
}
