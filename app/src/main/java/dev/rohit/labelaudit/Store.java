package dev.rohit.labelaudit;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;
public class Store extends SQLiteOpenHelper {
 public Store(Context c){super(c,"audit.db",null,1);}
 public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY, created TEXT NOT NULL, data TEXT NOT NULL)");}
 public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported database upgrade");}
 public synchronized void save(JSONObject j) {
  ContentValues v=new ContentValues(); v.put("id",j.optString("id"));v.put("created",j.optString("created_at"));v.put("data",j.toString());
  getWritableDatabase().insertWithOnConflict("events",null,v,SQLiteDatabase.CONFLICT_REPLACE);
 }
 public synchronized List<JSONObject> all() {
  List<JSONObject> out=new ArrayList<>();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM events ORDER BY created DESC",null)) {
   while(c.moveToNext())try {out.add(new JSONObject(c.getString(0)));}catch(JSONException e){throw new IllegalStateException(e);}
  } return out;
 }
}
