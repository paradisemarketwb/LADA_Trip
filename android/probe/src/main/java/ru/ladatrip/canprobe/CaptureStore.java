package ru.ladatrip.canprobe;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded local, read-only vehicle test log. No networking or location permission. */
final class CaptureStore extends SQLiteOpenHelper {
    private static final String PREFS="can_probe_state";
    private static final int MAX_ROWS=3500;
    CaptureStore(Context c){super(c,"can_probe_test.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER, source TEXT, signal TEXT, value TEXT, detail TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){throw new IllegalStateException("Migration required");}
    private static SharedPreferences pref(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static boolean active(Context c){return pref(c).getBoolean("active",false);}
    static long started(Context c){return pref(c).getLong("started_at",0);}
    static synchronized void begin(Context c){
        try(CaptureStore db=new CaptureStore(c)){db.getWritableDatabase().delete("events",null,null);}
        pref(c).edit().clear().putLong("started_at",System.currentTimeMillis()).putBoolean("active",true).commit();
        event(c,"SYSTEM","SESSION","START","User started read-only test");
    }
    static synchronized void end(Context c){
        event(c,"SYSTEM","SESSION","STOP","User stopped test");
        pref(c).edit().putBoolean("active",false).commit();
    }
    static synchronized void count(Context c,String key){
        if(!active(c))return;
        SharedPreferences p=pref(c);
        p.edit().putInt(key,p.getInt(key,0)+1).apply();
    }
    static synchronized void event(Context c,String source,String signal,String value,String detail){
        if(!active(c))return;
        String key="last/"+source+"/"+signal;
        String safeValue=clamp(value,320);
        String last=pref(c).getString(key,null);
        // State changes are logged, identical values are counted by sampling metrics.
        if(safeValue.equals(last))return;
        pref(c).edit().putString(key,safeValue).apply();
        ContentValues v=new ContentValues();
        v.put("at_ms",System.currentTimeMillis());
        v.put("source",clamp(source,40));
        v.put("signal",clamp(signal,80));
        v.put("value",safeValue);
        v.put("detail",clamp(detail,180));
        try(CaptureStore db=new CaptureStore(c)){
            SQLiteDatabase sql=db.getWritableDatabase();
            sql.insert("events",null,v);
            sql.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT "+MAX_ROWS+")");
        }
    }
    private static String clamp(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,n);}
    static String recent(Context c,int max){
        StringBuilder b=new StringBuilder();
        try(CaptureStore db=new CaptureStore(c);
            Cursor cur=db.getReadableDatabase().rawQuery(
                "SELECT at_ms,source,signal,value FROM events ORDER BY id DESC LIMIT "+Math.max(1,Math.min(max,60)),null)){
            while(cur.moveToNext()){
                String t=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault())
                    .format(new java.util.Date(cur.getLong(0)));
                b.append(t).append("  ").append(cur.getString(1)).append(" / ")
                    .append(cur.getString(2)).append(": ").append(cur.getString(3)).append("\n\n");
            }
        }catch(Exception e){return "Ошибка журнала: "+e.getClass().getSimpleName();}
        return b.length()==0?"Изменений пока нет":b.toString();
    }
    static JSONObject report(Context c){
        JSONObject root=new JSONObject();
        try{
            root.put("application","CAN Probe Test 0.1");
            root.put("device",Build.MODEL);
            root.put("build",Build.DISPLAY);
            root.put("android",Build.VERSION.RELEASE);
            root.put("export_at_ms",System.currentTimeMillis());
            root.put("started_at_ms",started(c));
            root.put("capture_active",active(c));
            root.put("read_only",true);
            root.put("network_permission",false);
            SharedPreferences prefs=pref(c);
            JSONObject metrics=new JSONObject();
            for(String key:new String[]{"accessibility_scans","accessibility_text_nodes","direct_polls","property_configs","sensor_count"}){
                metrics.put(key,prefs.getInt(key,0));
            }
            root.put("metrics",metrics);
            JSONObject permissions=new JSONObject();
            for(String permission:ProbeActivity.PERMISSIONS){
                String name=permission.substring(permission.lastIndexOf('.')+1);
                permissions.put(name,c.checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_GRANTED);
            }
            root.put("permissions",permissions);
            JSONArray rows=new JSONArray();
            Map<String,JSONObject> latest=new LinkedHashMap<>();
            try(CaptureStore db=new CaptureStore(c);
                Cursor cur=db.getReadableDatabase().rawQuery(
                    "SELECT at_ms,source,signal,value,detail FROM events ORDER BY id ASC LIMIT "+MAX_ROWS,null)){
                while(cur.moveToNext()){
                    JSONObject item=new JSONObject();
                    item.put("at_ms",cur.getLong(0));item.put("source",cur.getString(1));
                    item.put("signal",cur.getString(2));item.put("value",cur.getString(3));
                    item.put("detail",cur.getString(4));rows.put(item);
                    latest.put(cur.getString(1)+"/"+cur.getString(2),item);
                }
            }
            JSONObject last=new JSONObject();
            for(Map.Entry<String,JSONObject> e:latest.entrySet())last.put(e.getKey(),e.getValue());
            root.put("latest",last);root.put("changes",rows);
            root.put("note","Accessible UI text is separate from direct CAN property access. Adjacent text is unverified.");
        }catch(Exception e){try{root.put("error",e.toString());}catch(Exception ignored){}}
        return root;
    }
}
