package ru.ladatrip.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Parallel distance-only trip log using real CAN speed when GPS is unavailable.
 * It never fabricates coordinates. Estimates distance by trapezoidal integration;
 * GPS trip history remains separate, with its own exact source labels.
 */
public final class CanJourneyStore extends SQLiteOpenHelper {
    private long activeId, lastElapsed, stationaryAt, ignitionOffAt;
    private double previousKmh;
    private int consecutiveMoving;
    private long lastSampleAt;
    private String previousSource="";
    public CanJourneyStore(Context context){
        super(context,"lada-can-trips.db",null,1);
    }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE can_trips(id INTEGER PRIMARY KEY AUTOINCREMENT,"+
            " start_at INTEGER NOT NULL, end_at INTEGER, last_at INTEGER NOT NULL,"+
            " distance_m REAL NOT NULL DEFAULT 0, max_kmh REAL NOT NULL DEFAULT 0,"+
            " status TEXT NOT NULL DEFAULT 'ongoing')");
        db.execSQL("CREATE INDEX can_trip_time_idx ON can_trips(start_at)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        throw new IllegalStateException("Migration needed");
    }
    void resumeRecording(){
        try(Cursor cursor=getWritableDatabase().rawQuery(
            "SELECT id,last_at FROM can_trips WHERE end_at IS NULL ORDER BY id DESC LIMIT 1",null)){
            if(cursor.moveToFirst()){
                long id=cursor.getLong(0),t=cursor.getLong(1);
                if(VehicleDisplayFormat.fresh(t,System.currentTimeMillis(),60000))activeId=id;
                else getWritableDatabase().execSQL(
                    "UPDATE can_trips SET end_at=last_at,status='interrupted' WHERE id=?",
                    new Object[]{id});
            }
        }catch(Exception e){android.util.Log.w("LadaCanTrip","Resume failure",e);}
    }
    public synchronized void sample(double kmh,Integer ignition,String source,long sampleAt){
        long now=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        if(!Double.isFinite(kmh)||kmh<0||kmh>240)return;
        boolean continuous=source.equals(previousSource) && sampleAt>lastSampleAt &&
            sampleAt-lastSampleAt<=6000;
        if(!continuous){
            consecutiveMoving=0;stationaryAt=0;ignitionOffAt=0;
        }
        boolean moving=kmh>3;
        boolean engineOff=ignition!=null&&(ignition==1||ignition==2);
        if(moving)consecutiveMoving++;else consecutiveMoving=0;
        if(activeId==0){
            if(consecutiveMoving>=2){
                android.content.ContentValues v=new android.content.ContentValues();
                v.put("start_at",now);v.put("last_at",now);v.put("max_kmh",kmh);
                activeId=getWritableDatabase().insert("can_trips",null,v);
                if(activeId<1){activeId=0;return;}
            }
            lastElapsed=elapsed;lastSampleAt=sampleAt;previousSource=source;previousKmh=kmh;return;
        }
        long delta=lastElapsed<=0?0:elapsed-lastElapsed;
        if(continuous&&delta>0&&delta<=12000){
            double distance=VehicleDisplayFormat.integratedMeters(previousKmh,kmh,delta);
            // Skip unrealistic acceleration/jumps from a broken signal.
            if(Math.abs(kmh-previousKmh)<=70 || delta>=3000){
                getWritableDatabase().execSQL(
                    "UPDATE can_trips SET distance_m=distance_m+?,max_kmh=MAX(max_kmh,?),last_at=? WHERE id=?",
                    new Object[]{Math.max(0,distance),kmh,now,activeId});
            }
        }else{
            getWritableDatabase().execSQL(
                "UPDATE can_trips SET last_at=?,max_kmh=MAX(max_kmh,?) WHERE id=?",
                new Object[]{now,kmh,activeId});
        }
        if(moving)stationaryAt=0;
        else if(stationaryAt==0)stationaryAt=elapsed;
        if(engineOff){
            if(ignitionOffAt==0)ignitionOffAt=elapsed;
        }else ignitionOffAt=0;
        if((stationaryAt>0&&elapsed-stationaryAt>=8*60*1000L)||
            (ignitionOffAt>0&&elapsed-ignitionOffAt>=30000L)){
            finishAt(now,"complete");
            return;
        }
        lastElapsed=elapsed;lastSampleAt=sampleAt;previousSource=source;previousKmh=kmh;
    }
    public synchronized void finish(){
        if(activeId>0)finishAt(System.currentTimeMillis(),"stopped");
    }
    private void finishAt(long time,String reason){
        if(activeId==0)return;
        getWritableDatabase().execSQL(
            "UPDATE can_trips SET end_at=?,last_at=?,status=? WHERE id=? AND end_at IS NULL",
            new Object[]{time,time,reason,activeId});
        activeId=0;lastElapsed=0;lastSampleAt=0;previousSource="";stationaryAt=0;ignitionOffAt=0;
        previousKmh=0;consecutiveMoving=0;
    }
    public synchronized JSONObject snapshot(){
        JSONObject root=new JSONObject();
        try{
            root.put("method","CAN speed integration (estimated, not GPS)");
            root.put("source","Humax R2 vehicle speed");
            try(Cursor open=getReadableDatabase().rawQuery(
                "SELECT 1 FROM can_trips WHERE end_at IS NULL LIMIT 1",null)){
                root.put("can_open_trip",open.moveToFirst());
            }
            root.put("timestamp_ms",System.currentTimeMillis());
            JSONArray trips=new JSONArray();
            try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,start_at,end_at,distance_m,max_kmh,status FROM can_trips ORDER BY id DESC LIMIT 15",null)){
                while(c.moveToNext()){
                    JSONObject j=new JSONObject();
                    j.put("id",c.getLong(0));j.put("start_at",c.getLong(1));
                    j.put("end_at",c.isNull(2)?JSONObject.NULL:c.getLong(2));
                    j.put("distance_km",Math.round(c.getDouble(3)*100.0)/100000.0);
                    j.put("max_speed_kmh",Math.round(c.getDouble(4)*10.0)/10.0);
                    j.put("status",c.getString(5));trips.put(j);
                }
            }
            root.put("trips",trips);
        }catch(Exception e){try{root.put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}
        return root;
    }
    synchronized long activeStart(){
        try(Cursor cursor=getReadableDatabase().rawQuery(
            "SELECT start_at FROM can_trips WHERE end_at IS NULL ORDER BY start_at DESC LIMIT 1",null)){
            return cursor.moveToFirst()?cursor.getLong(0):0;
        }
    }
    synchronized java.util.List<TripStore.TripSpan> tripsInPeriod(long from,long until){
        java.util.List<TripStore.TripSpan> spans=new java.util.ArrayList<>();
        try(Cursor cursor=getReadableDatabase().rawQuery(
            "SELECT start_at,end_at,distance_m FROM can_trips WHERE start_at>=? AND "+
                "COALESCE(end_at,last_at)<=? ORDER BY start_at",
            new String[]{Long.toString(from),Long.toString(until)})){
            while(cursor.moveToNext())spans.add(new TripStore.TripSpan(
                cursor.getLong(0),cursor.getLong(1),cursor.getDouble(2)));
        }
        return spans;
    }
    public synchronized String summary(){
        StringBuilder b=new StringBuilder();
        SimpleDateFormat date=new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault());
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT start_at,end_at,distance_m,max_kmh FROM can_trips ORDER BY id DESC LIMIT 4",null)){
            while(c.moveToNext()){
                if(b.length()>0)b.append('\n');
                b.append(date.format(new Date(c.getLong(0)))).append(" · ")
                    .append(String.format(Locale.getDefault(),"%.2f км",c.getDouble(2)/1000))
                    .append(c.isNull(1)?" · в пути":" · завершена")
                    .append(" · макс. ").append(String.format(Locale.getDefault(),"%.0f",c.getDouble(3))).append(" км/ч");
            }
        }catch(Exception e){return "Журнал CAN временно недоступен";}
        return b.length()==0?"Поездок по CAN пока нет":b.toString();
    }
    public synchronized String exportCsv(){
        StringBuilder b=new StringBuilder("start_ms;end_ms;distance_km;max_kmh;status\n");
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT start_at,end_at,distance_m,max_kmh,status FROM can_trips ORDER BY start_at",null)){
            while(c.moveToNext()){
                b.append(c.getLong(0)).append(';');
                if(!c.isNull(1))b.append(c.getLong(1));
                b.append(';').append(String.format(Locale.US,"%.3f",c.getDouble(2)/1000))
                    .append(';').append(String.format(Locale.US,"%.1f",c.getDouble(3)))
                    .append(';').append(c.getString(4)).append('\n');
            }
        }
        return b.toString();
    }
}