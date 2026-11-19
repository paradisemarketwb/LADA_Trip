package ru.ladatrip.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.GnssStatus;
import android.location.Location;
import org.json.JSONObject;
import java.util.Locale;

/**
 * GNSS diagnostics without recording or exporting geographic coordinates.
 * Distinguishes "no callbacks" from "callbacks rejected for accuracy/time".
 */
public final class GpsHealth {
    private static final String PREF="gps_health";
    private GpsHealth(){}
    private static SharedPreferences preferences(Context c){
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    static void start(Context c){
        preferences(c).edit().putBoolean("service_running",true)
            .putLong("started_at",System.currentTimeMillis())
            .putInt("raw_callbacks",0).putInt("valid_callbacks",0)
            .putInt("rejected_callbacks",0).putInt("satellite_updates",0)
            .putLong("last_raw_at",0).putLong("last_valid_at",0)
            .putString("last_rejection","")
            .putString("subscription","Ожидание регистрации GPS")
            .putString("gnss_state","Не проверено")
            .putInt("satellites_seen",0).putInt("satellites_used",0)
            .apply();
    }
    static void stop(Context c){preferences(c).edit().putBoolean("service_running",false).apply();}
    static void registration(Context c,String state){
        preferences(c).edit().putString("subscription",state).apply();
    }
    static void gnss(Context c,String state){
        preferences(c).edit().putString("gnss_state",state).apply();
    }
    static void satelliteUpdate(Context c,GnssStatus status){
        int seen=status.getSatelliteCount(),used=0;
        for(int i=0;i<seen;i++)if(status.usedInFix(i))used++;
        SharedPreferences sp=preferences(c);
        sp.edit().putInt("satellites_seen",seen).putInt("satellites_used",used)
            .putInt("satellite_updates",sp.getInt("satellite_updates",0)+1)
            .putLong("satellite_last_at",System.currentTimeMillis()).apply();
    }
    static void raw(Context c,Location location){
        SharedPreferences sp=preferences(c);
        SharedPreferences.Editor e=sp.edit().putInt("raw_callbacks",sp.getInt("raw_callbacks",0)+1)
            .putLong("last_raw_at",System.currentTimeMillis());
        if(location!=null){
            e.putString("raw_provider",location.getProvider());
            e.putFloat("last_accuracy_m",location.hasAccuracy()?location.getAccuracy():-1);
            e.putLong("location_timestamp_offset_ms",System.currentTimeMillis()-location.getTime());
        }
        e.apply();
    }
    static void accepted(Context c){
        SharedPreferences sp=preferences(c);
        sp.edit().putInt("valid_callbacks",sp.getInt("valid_callbacks",0)+1)
            .putLong("last_valid_at",System.currentTimeMillis()).apply();
    }
    static void rejected(Context c,String cause){
        SharedPreferences sp=preferences(c);
        sp.edit().putInt("rejected_callbacks",sp.getInt("rejected_callbacks",0)+1)
            .putString("last_rejection",cause).apply();
    }
    public static JSONObject snapshot(Context c){
        JSONObject root=new JSONObject();SharedPreferences sp=preferences(c);
        try{
            root.put("service_running",sp.getBoolean("service_running",false));
            root.put("started_at_ms",sp.getLong("started_at",0));
            root.put("subscription",sp.getString("subscription","not_started"));
            root.put("gnss_state",sp.getString("gnss_state","not_started"));
            root.put("satellite_updates",sp.getInt("satellite_updates",0));
            root.put("satellites_seen",sp.getInt("satellites_seen",0));
            root.put("satellites_used_in_fix",sp.getInt("satellites_used",0));
            root.put("raw_location_callbacks",sp.getInt("raw_callbacks",0));
            root.put("accepted_location_callbacks",sp.getInt("valid_callbacks",0));
            root.put("rejected_location_callbacks",sp.getInt("rejected_callbacks",0));
            root.put("last_rejection",sp.getString("last_rejection",""));
            root.put("last_accuracy_m",sp.getFloat("last_accuracy_m",-1));
            root.put("location_timestamp_offset_ms",sp.getLong("location_timestamp_offset_ms",0));
            root.put("seconds_since_last_raw",age(sp.getLong("last_raw_at",0)));
            root.put("seconds_since_last_accepted",age(sp.getLong("last_valid_at",0)));
            root.put("seconds_since_last_satellites",age(sp.getLong("satellite_last_at",0)));
            root.put("note","No latitude, longitude, device key or network data are included.");
        }catch(Exception ignored){}
        return root;
    }
    private static Object age(long at){
        if(at<=0)return JSONObject.NULL;
        return Math.max(0,(System.currentTimeMillis()-at)/1000);
    }
    public static String summary(Context c){
        SharedPreferences p=preferences(c);
        boolean running=p.getBoolean("service_running",false);
        return (running?"GPS-служба запущена":"GPS-служба не запущена")+
            "\nРегистрация: "+p.getString("subscription","ещё не проверялась")+
            "\nGNSS: "+p.getString("gnss_state","—")+
            " · спутников "+p.getInt("satellites_seen",0)+
            " / в решении "+p.getInt("satellites_used",0)+
            "\nПолучено GPS-событий: "+p.getInt("raw_callbacks",0)+
            " · принято: "+p.getInt("valid_callbacks",0)+
            " · отклонено: "+p.getInt("rejected_callbacks",0)+
            (p.getString("last_rejection","").isEmpty()?"":"\nПричина последнего отклонения: "+p.getString("last_rejection",""));
    }
}