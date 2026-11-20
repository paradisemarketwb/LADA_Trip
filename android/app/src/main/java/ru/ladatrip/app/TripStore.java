package ru.ladatrip.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import org.json.JSONArray;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public final class TripStore extends SQLiteOpenHelper {
    public static final class Fix {
        public final long time;
        public final double lat, lon;
        public final float accuracy;
        public Fix(long time, double lat, double lon, float accuracy) { this.time=time; this.lat=lat; this.lon=lon; this.accuracy=accuracy; }
    }
    public static final class Pending {
        public final String eventId, payload;
        public Pending(String id, String p) { eventId=id; payload=p; }
    }
    public TripStore(Context c) {super(c,"lada-trip.db",null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT,start_at INTEGER NOT NULL,end_at INTEGER,distance_m REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER NOT NULL,t INTEGER NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,accuracy REAL,speed REAL)");
        db.execSQL("CREATE INDEX points_trip_idx ON points(trip_id,t)");
        db.execSQL("CREATE TABLE stops (id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER NOT NULL,start_at INTEGER NOT NULL,end_at INTEGER NOT NULL,lat REAL,lon REAL)");
        db.execSQL("CREATE TABLE events (event_id TEXT PRIMARY KEY,type TEXT NOT NULL,payload TEXT NOT NULL,created_at INTEGER NOT NULL,attempts INTEGER DEFAULT 0,acked_at INTEGER,last_error TEXT)");
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY,val TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion) {throw new IllegalStateException("Migration required");}
    public synchronized void put(String key,String val) {
        ContentValues v=new ContentValues(); v.put("key",key);v.put("val",val);
        getWritableDatabase().insertWithOnConflict("state",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized String get(String key) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT val FROM state WHERE key=?",new String[]{key})) {return c.moveToFirst()?c.getString(0):null;}
    }
    public synchronized Fix lastFix() {
        String json=get("last_fix");
        if (json==null) return null;
        try {JSONObject o=new JSONObject(json);return new Fix(o.getLong("t"),o.getDouble("lat"),o.getDouble("lon"),(float)o.getDouble("acc"));}
        catch(Exception e){return null;}
    }
    public synchronized void saveFix(Fix f) {
        try {JSONObject j=new JSONObject();j.put("t",f.time);j.put("lat",f.lat);j.put("lon",f.lon);j.put("acc",f.accuracy);put("last_fix",j.toString());}
        catch(Exception ignored) {}
    }
    public synchronized long openTrip() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM trips WHERE end_at IS NULL ORDER BY id DESC LIMIT 1",null)) {return c.moveToFirst()?c.getLong(0):0;}
    }
    synchronized long startTrip(Fix initial){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            ContentValues trip=new ContentValues();
            trip.put("start_at",initial.time);
            long id=db.insertOrThrow("trips",null,trip);
            insertPoint(db,id,initial,-1f);
            db.setTransactionSuccessful();
            return id;
        }finally{db.endTransaction();}
    }
    private void insertPoint(SQLiteDatabase db,long trip,Fix f,float speed){
        ContentValues point=new ContentValues();
        point.put("trip_id",trip);point.put("t",f.time);
        point.put("lat",f.lat);point.put("lon",f.lon);point.put("accuracy",f.accuracy);
        if(speed>=0)point.put("speed",speed);
        db.insertOrThrow("points",null,point);
    }
    synchronized void addPoint(long trip,Fix f,float speed,double meters){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            insertPoint(db,trip,f,speed);
            if(meters>0)db.execSQL("UPDATE trips SET distance_m=distance_m+? WHERE id=?",
                new Object[]{meters,trip});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized double distance(long trip) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT distance_m FROM trips WHERE id=?",new String[]{Long.toString(trip)})) {return c.moveToFirst()?c.getDouble(0):0;}
    }
    public synchronized long startTime(long trip) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT start_at FROM trips WHERE id=?",new String[]{Long.toString(trip)})) {return c.moveToFirst()?c.getLong(0):0;}
    }
    public synchronized void addStop(long trip,long start,long end,Fix f) {
        if (end-start<60000) return;
        ContentValues v=new ContentValues();v.put("trip_id",trip);v.put("start_at",start);v.put("end_at",end);v.put("lat",f.lat);v.put("lon",f.lon);
        getWritableDatabase().insert("stops",null,v);
    }
    public synchronized void endTrip(long trip,long end) {
        getWritableDatabase().execSQL("UPDATE trips SET end_at=? WHERE id=? AND end_at IS NULL",new Object[]{end,trip});
    }
    private static String iso(long ms) {
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(ms));
    }
    public synchronized void queue(String type,Fix fix,double distance,long durationSeconds) {
        if(fix==null)return;
        try {
            JSONObject j=new JSONObject();
            j.put("event_id",UUID.randomUUID().toString());j.put("type",type);j.put("latitude",fix.lat);j.put("longitude",fix.lon);j.put("accuracy_m",Math.max(0,fix.accuracy));j.put("captured_at",iso(fix.time));
            if ("trip_end".equals(type)) {j.put("distance_m",Math.max(0,distance));j.put("duration_s",Math.max(0,durationSeconds));}
            ContentValues v=new ContentValues();v.put("event_id",j.getString("event_id"));v.put("type",type);v.put("payload",j.toString());v.put("created_at",System.currentTimeMillis());
            getWritableDatabase().insertOrThrow("events",null,v);
        }catch(Exception e){android.util.Log.e("LadaTrip","Failed to queue event",e);}
    }
    public synchronized java.util.List<Pending> pending(int limit) {
        java.util.ArrayList<Pending> out=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT event_id,payload FROM events WHERE acked_at IS NULL ORDER BY created_at ASC LIMIT "+Math.max(1,Math.min(limit,100)),null)) {
            while(c.moveToNext())out.add(new Pending(c.getString(0),c.getString(1)));
        }
        return out;
    }
    public synchronized void attempted(String eventId,String err) {
        ContentValues v=new ContentValues();v.put("last_error",err==null?"unknown":err.substring(0,Math.min(err.length(),160)));
        getWritableDatabase().execSQL("UPDATE events SET attempts=attempts+1,last_error=? WHERE event_id=?",new Object[]{v.getAsString("last_error"),eventId});
    }
    public synchronized void ack(String eventId) {
        ContentValues v=new ContentValues();v.put("acked_at",System.currentTimeMillis());v.putNull("last_error");
        getWritableDatabase().update("events",v,"event_id=?",new String[]{eventId});
    }
    public synchronized long countPending() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM events WHERE acked_at IS NULL",null)) {return c.moveToFirst()?c.getLong(0):0;}
    }
    public synchronized String recentTrips() {
        StringBuilder sb=new StringBuilder();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,start_at,end_at,distance_m,(SELECT count(*) FROM stops WHERE trip_id=trips.id) FROM trips ORDER BY id DESC LIMIT 12",null)) {
            SimpleDateFormat fmt=new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault());
            while(c.moveToNext()) {
                long start=c.getLong(1),end=c.isNull(2)?0:c.getLong(2);
                sb.append(fmt.format(new Date(start))).append("  •  ").append(String.format(Locale.US,"%.2f км",c.getDouble(3)/1000));
                sb.append(end==0?"  · В пути":"  · "+Math.max(1,(end-start)/60000)+" мин");
                sb.append("  · остановок: ").append(c.getInt(4)).append("\n\n");
            }
        }
        return sb.length()==0?"Пока нет записанных поездок":sb.toString().trim();
    }
    static final class TripSpan {
        final long start,end;
        final double meters;
        TripSpan(long start,long end,double meters){
            this.start=start;this.end=end;this.meters=meters;
        }
    }
    synchronized java.util.List<TripSpan> tripsInPeriod(long from,long until){
        java.util.List<TripSpan> spans=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT start_at,end_at,distance_m FROM trips WHERE start_at>=? AND "+
            "COALESCE(end_at,(SELECT MAX(t) FROM points WHERE trip_id=trips.id))<=? "+
            "ORDER BY start_at",new String[]{Long.toString(from),Long.toString(until)})){
            while(c.moveToNext())spans.add(new TripSpan(c.getLong(0),c.getLong(1),c.getDouble(2)));
        }
        return spans;
    }
    static final class RoutePoint {
        final long time;
        final double lat,lon,speed;
        RoutePoint(long time,double lat,double lon,double speed){
            this.time=time;this.lat=lat;this.lon=lon;this.speed=speed;
        }
    }
    static final class Route {
        final java.util.List<RoutePoint> points=new java.util.ArrayList<>();
        final java.util.List<RoutePoint> stops=new java.util.ArrayList<>();
        double meters;
        long start,end;
        boolean truncated;
    }
    synchronized java.util.Map<Long,String> routeChoices(){
        java.util.Map<Long,String> choices=new java.util.LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT id,start_at,distance_m FROM trips ORDER BY id DESC LIMIT 100",null)){
            SimpleDateFormat format=new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault());
            while(c.moveToNext())choices.put(c.getLong(0),String.format(Locale.getDefault(),
                "%s · %.2f км",format.format(new Date(c.getLong(1))),c.getDouble(2)/1000));
        }
        return choices;
    }
    synchronized Route route(long trip){
        Route route=new Route();
        String[] args={Long.toString(trip)};
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT start_at,end_at,distance_m FROM trips WHERE id=?",args)){
            if(c.moveToFirst()){
                route.start=c.getLong(0);route.end=c.getLong(1);route.meters=c.getDouble(2);
            }
        }
        try(Cursor c=db.rawQuery("SELECT t,lat,lon,speed FROM points WHERE trip_id=? "+
            "ORDER BY t,id LIMIT 12001",args)){
            while(c.moveToNext()){
                if(route.points.size()==12000){route.truncated=true;break;}
                route.points.add(new RoutePoint(c.getLong(0),c.getDouble(1),c.getDouble(2),
                    c.isNull(3)?Double.NaN:c.getDouble(3)));
            }
        }
        try(Cursor c=db.rawQuery("SELECT start_at,lat,lon FROM stops WHERE trip_id=? "+
            "AND lat IS NOT NULL AND lon IS NOT NULL ORDER BY start_at LIMIT 1000",args)){
            while(c.moveToNext())route.stops.add(new RoutePoint(
                c.getLong(0),c.getDouble(1),c.getDouble(2),Double.NaN));
        }
        return route;
    }
    private static String csvDouble(double x){return String.format(Locale.US,"%.6f",x);}
    public synchronized String exportCsv() {
        StringBuilder sb=new StringBuilder("trip_id,time_iso,latitude,longitude,accuracy_m,speed_m_s\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT trip_id,t,lat,lon,accuracy,speed FROM points ORDER BY trip_id,t",null)) {
            while(c.moveToNext()) sb.append(c.getLong(0)).append(',').append(iso(c.getLong(1))).append(',').append(csvDouble(c.getDouble(2))).append(',').append(csvDouble(c.getDouble(3))).append(',').append(csvDouble(c.getDouble(4))).append(',').append(c.isNull(5)?"":csvDouble(c.getDouble(5))).append('\n');
        }
        return sb.toString();
    }
    public synchronized String exportTripsCsv() {
        StringBuilder sb=new StringBuilder("trip_id,start_utc,end_utc,distance_km,duration_minutes,stops_count\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,start_at,end_at,distance_m,(SELECT count(*) FROM stops WHERE trip_id=trips.id) FROM trips ORDER BY id",null)) {
            while(c.moveToNext()) {
                sb.append(c.getLong(0)).append(',').append(iso(c.getLong(1))).append(',');
                if(!c.isNull(2))sb.append(iso(c.getLong(2)));
                sb.append(',').append(String.format(Locale.US,"%.3f",c.getDouble(3)/1000)).append(',');
                if(!c.isNull(2))sb.append((c.getLong(2)-c.getLong(1))/60000);
                sb.append(',').append(c.getInt(4)).append('\n');
            }
        }
        return sb.toString();
    }
    public synchronized String exportStopsCsv() {
        StringBuilder sb=new StringBuilder("trip_id,start_utc,end_utc,stop_minutes,latitude,longitude\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT trip_id,start_at,end_at,lat,lon FROM stops ORDER BY trip_id,start_at",null)) {
            while(c.moveToNext())sb.append(c.getLong(0)).append(',').append(iso(c.getLong(1))).append(',').append(iso(c.getLong(2))).append(',')
                .append((c.getLong(2)-c.getLong(1))/60000).append(',').append(csvDouble(c.getDouble(3))).append(',').append(csvDouble(c.getDouble(4))).append('\n');
        }
        return sb.toString();
    }
    public synchronized String exportGpx() {
        StringBuilder sb=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<gpx version=\"1.1\" creator=\"LADA Trip\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        long previous=-1;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT trip_id,t,lat,lon FROM points ORDER BY trip_id,t",null)) {
            while(c.moveToNext()) {
                long id=c.getLong(0);
                if(id!=previous) {if(previous!=-1)sb.append("</trkseg></trk>\n");sb.append("<trk><name>Trip ").append(id).append("</name><trkseg>\n");previous=id;}
                sb.append("<trkpt lat=\"").append(csvDouble(c.getDouble(2))).append("\" lon=\"").append(csvDouble(c.getDouble(3))).append("\"><time>").append(iso(c.getLong(1))).append("</time></trkpt>\n");
            }
        }
        if(previous!=-1)sb.append("</trkseg></trk>\n");
        return sb.append("</gpx>\n").toString();
    }
    /** Stream individual recorded fixes, preserving time and optional speed. */
    void writeGeoJson(java.io.OutputStream output) throws java.io.IOException {
        android.util.JsonWriter json=new android.util.JsonWriter(
            new java.io.OutputStreamWriter(output,java.nio.charset.StandardCharsets.UTF_8));
        json.beginObject().name("type").value("FeatureCollection").name("features").beginArray();
        try(Cursor cursor=getReadableDatabase().rawQuery(
            "SELECT trip_id,t,lat,lon,accuracy,speed FROM points ORDER BY trip_id,t,id",null)){
            while(cursor.moveToNext()){
                json.beginObject().name("type").value("Feature").name("geometry").beginObject()
                    .name("type").value("Point").name("coordinates").beginArray()
                    .value(cursor.getDouble(3)).value(cursor.getDouble(2)).endArray().endObject()
                    .name("properties").beginObject().name("trip_id").value(cursor.getLong(0))
                    .name("time").value(iso(cursor.getLong(1)));
                if(!cursor.isNull(4))json.name("accuracy_m").value(cursor.getDouble(4));
                if(!cursor.isNull(5))json.name("speed_m_s").value(cursor.getDouble(5));
                json.endObject().endObject();
            }
        }
        json.endArray().endObject();json.flush();
    }
    public synchronized String exportDiagnostics(Context context) {
        JSONObject j=new JSONObject();
        try {
            j.put("app","LADA Trip");
            try{
                j.put("app_version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName);
            }catch(Exception e){j.put("app_version","unknown");}
            j.put("exported_at",iso(System.currentTimeMillis()));
            j.put("android_sdk",android.os.Build.VERSION.SDK_INT);j.put("model",android.os.Build.MODEL);
            j.put("manufacturer",android.os.Build.MANUFACTURER);
            j.put("monitor_enabled",Settings.monitor(context));j.put("auto_start",Settings.autoStart(context));
            j.put("server_configured",Settings.server(context)!=null);
            j.put("device_key_present",Settings.token(context)!=null);
            j.put("pending_events",countPending());j.put("open_trip",openTrip());
            android.location.LocationManager m=(android.location.LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
            JSONArray providers=new JSONArray();if(m!=null)for(String p:m.getAllProviders()) providers.put(p);
            j.put("location_providers",providers);
            j.put("gps_enabled",m!=null && m.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER));
            j.put("location_permission",context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED);
            Fix last=lastFix(); j.put("last_fix_age_seconds",last==null?JSONObject.NULL:Math.max(0,(System.currentTimeMillis()-last.time)/1000));
            j.put("system_build",android.os.Build.DISPLAY);
            j.put("gps_health",GpsHealth.snapshot(context));
            // Точная геопозиция, токены и URL не экспортируются в диагностику.
        }catch(Exception ignored){}
        try { return j.toString(2); }
        catch (org.json.JSONException ex) { return j.toString(); }
    }
}
