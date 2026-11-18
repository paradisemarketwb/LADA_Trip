package ru.ladatrip.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Bounded local event history and last-known automotive readings, never sent over network. */
public final class VehicleLog extends SQLiteOpenHelper {
    private static final String PREF="vehicle_live_state";
    private static final long HEARTBEAT_MS=30000L;
    private static final int MAX_EVENTS=15000;
    public static final String[] SIGNALS={
        "SPEED","FUEL_L","FUEL_LOW","OUTSIDE_C","IGNITION",
        "GEAR","HANDBRAKE","REVERSE","ENGINE","STEER_RAW",
        "TAIL_GATE","HOOD","DRIVER_DOOR","PASSENGER_DOOR",
        "REAR_LEFT_DOOR","REAR_RIGHT_DOOR","ALARM",
        "RPM","COOLANT_C","ODOMETER"
    };
    public VehicleLog(Context c){super(c,"lada_vehicle_live.db",null,1);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT,"+
            " at_ms INTEGER NOT NULL, source TEXT NOT NULL, signal TEXT NOT NULL,"+
            " value TEXT NOT NULL, note TEXT NOT NULL)");
        d.execSQL("CREATE INDEX idx_vehicle_signal ON readings(signal,at_ms)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b){throw new IllegalStateException("Migration needed");}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    static void running(Context c,boolean running){
        prefs(c).edit().putBoolean("monitor_running",running).apply();
    }
    public static boolean running(Context c){return prefs(c).getBoolean("monitor_running",false);}
    public static String value(Context c,String signal){return prefs(c).getString("value/"+signal,"—");}
    public static String source(Context c,String signal){return prefs(c).getString("source/"+signal,"");}
    public static long at(Context c,String signal){return prefs(c).getLong("at/"+signal,0);}
    public static String state(Context c){return prefs(c).getString("connection","не подключено");}
    public static void state(Context c,String message){prefs(c).edit().putString("connection",message).apply();}
    public static synchronized void put(Context ctx,String source,String signal,String value,String note){
        if(!isKnown(signal))return;
        String clean=value==null?"—":value.trim();
        if(clean.length()>120)clean=clean.substring(0,120);
        SharedPreferences sp=prefs(ctx);
        long now=System.currentTimeMillis();
        String old=sp.getString("value/"+signal,null);
        String oldSource=sp.getString("source/"+signal,"");
        long oldAt=sp.getLong("at/"+signal,0);
        // Prefer the direct Android automotive property when it is currently available.
        if("sensor".equals(source)&&"property".equals(oldSource)&&now-oldAt<10000)return;
        if("sensor".equals(source)&&"ui".equals(oldSource)&&now-oldAt<10000)return;
        if("ui".equals(source)&&"property".equals(oldSource)&&now-oldAt<10000)return;
        if("elm".equals(source)&&"property".equals(oldSource)&&now-oldAt<10000)return;
        if("ui".equals(source)&&"elm".equals(oldSource)&&now-oldAt<10000)return;
        boolean changed=!clean.equals(old)||!source.equals(oldSource);
        sp.edit().putString("value/"+signal,clean).putString("source/"+signal,source)
            .putLong("at/"+signal,now).apply();
        if(!changed && now-sp.getLong("logged/"+signal,0)<HEARTBEAT_MS)return;
        sp.edit().putLong("logged/"+signal,now).apply();
        try(VehicleLog helper=new VehicleLog(ctx)){
            SQLiteDatabase db=helper.getWritableDatabase();
            ContentValues values=new ContentValues();
            values.put("at_ms",now);values.put("source",source);
            values.put("signal",signal);values.put("value",clean);
            values.put("note",note==null?"":note.substring(0,Math.min(180,note.length())));
            db.insert("readings",null,values);
            // Bound disk usage. Every insertion is small; expensive pruning only periodically.
            if(now-sp.getLong("last_trim",0)>60000){
                db.execSQL("DELETE FROM readings WHERE id NOT IN (SELECT id FROM readings ORDER BY id DESC LIMIT "+MAX_EVENTS+")");
                sp.edit().putLong("last_trim",now).apply();
            }
        }catch(Exception e){android.util.Log.w("LadaVehicle","Local vehicle history failed",e);}
    }
    private static boolean isKnown(String key){
        for(String name:SIGNALS)if(name.equals(key))return true;
        return false;
    }
    public static JSONObject exportJson(Context c){
        JSONObject root=new JSONObject();
        try{
            root.put("version","LADA Trip 0.5 vehicle");
            root.put("model",android.os.Build.MODEL);root.put("build",android.os.Build.DISPLAY);
            root.put("export_at_ms",System.currentTimeMillis());
            root.put("monitor_running",running(c));root.put("connection",state(c));
            root.put("read_only",true);
            JSONObject latest=new JSONObject();
            long now=System.currentTimeMillis();
            for(String name:SIGNALS){
                JSONObject item=new JSONObject();item.put("value",value(c,name));
                item.put("source",source(c,name));item.put("at_ms",at(c,name));
                item.put("age_seconds",at(c,name)==0?JSONObject.NULL:Math.max(0,(now-at(c,name))/1000));
                item.put("fresh",at(c,name)>0&&now-at(c,name)<12000);
                latest.put(name,item);
            }
            root.put("latest",latest);
            JSONArray events=new JSONArray();
            try(VehicleLog helper=new VehicleLog(c);
                Cursor cursor=helper.getReadableDatabase().rawQuery(
                    "SELECT at_ms,source,signal,value,note FROM readings ORDER BY id ASC LIMIT "+MAX_EVENTS,null)){
                while(cursor.moveToNext()){
                    JSONObject event=new JSONObject();
                    event.put("at_ms",cursor.getLong(0));event.put("source",cursor.getString(1));
                    event.put("signal",cursor.getString(2));event.put("value",cursor.getString(3));
                    event.put("note",cursor.getString(4));
                    events.put(event);
                }
            }
            root.put("events",events);
            root.put("note","UI-only values require the OEM engineering screen to be visible and Accessibility enabled. Raw steering values are not degrees.");
        }catch(Exception e){try{root.put("export_error",e.toString());}catch(Exception ignored){}}
        return root;
    }
    public static String exportCsv(Context c){
        StringBuilder out=new StringBuilder("time_ms;source;signal;value;note\n");
        try(VehicleLog helper=new VehicleLog(c);
            Cursor cursor=helper.getReadableDatabase().rawQuery(
                "SELECT at_ms,source,signal,value,note FROM readings ORDER BY id ASC LIMIT "+MAX_EVENTS,null)){
            while(cursor.moveToNext()){
                out.append(cursor.getLong(0));
                for(int i=1;i<5;i++)out.append(';').append(csv(cursor.getString(i)));
                out.append('\n');
            }
        }catch(Exception e){out.append("# error ").append(e.getClass().getSimpleName()).append('\n');}
        return out.toString();
    }
    private static String csv(String s){
        if(s==null)return "";
        return "\""+s.replace("\"","\"\"").replace('\r',' ').replace('\n',' ')+"\"";
    }
}
