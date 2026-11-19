package ru.ladatrip.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import java.util.Locale;

/** Captures reference pairs from steady, closely timed GPS and vehicle samples. */
final class GpsSpeedReference {
    private static final int FIRST_BUCKET=2,LAST_BUCKET=12;
    private String source="";
    private int count,bucket;
    private double gpsSum,vehicleSum,minGps,maxGps,minCar,maxCar;
    private long first,last;

    void observe(Context context,Location fix){
        if(!fix.hasSpeed()||!fix.hasSpeedAccuracy()||!Float.isFinite(fix.getSpeedAccuracyMetersPerSecond())||
            fix.getSpeedAccuracyMetersPerSecond()>0.7f||fix.getAccuracy()>15){
            count=0;return;
        }
        VehicleLog.Reading car=VehicleLog.speed(context);
        if(!("property".equals(car.source)||"sensor".equals(car.source)) ||
            Math.abs(car.at-fix.getTime())>2000){
            count=0;return;
        }
        double measured;
        try{measured=Double.parseDouble(car.value);}
        catch(NumberFormatException error){count=0;return;}
        double gps=fix.getSpeed()*3.6;
        int currentBucket=(int)(gps/10);
        if(!Double.isFinite(gps)||!Double.isFinite(measured)||measured<=0||
            currentBucket<FIRST_BUCKET||currentBucket>LAST_BUCKET||Math.abs(gps-measured)>30){
            count=0;return;
        }

        long at=fix.getElapsedRealtimeNanos()/1_000_000L;
        if(count==0||!source.equals(car.source)||bucket!=currentBucket||at<=last||at-last>9000||
            Math.max(maxGps,gps)-Math.min(minGps,gps)>3||
            Math.max(maxCar,measured)-Math.min(minCar,measured)>3){
            count=0;gpsSum=0;vehicleSum=0;first=at;source=car.source;bucket=currentBucket;
            minGps=gps;maxGps=gps;minCar=measured;maxCar=measured;
        }
        count++;gpsSum+=gps;vehicleSum+=measured;last=at;
        minGps=Math.min(minGps,gps);maxGps=Math.max(maxGps,gps);
        minCar=Math.min(minCar,measured);maxCar=Math.max(maxCar,measured);
        if(count>=4&&at-first>=12000){
            preferences(context).edit().putString(source+"/"+bucket,
                String.format(Locale.US,"%.2f %.2f",vehicleSum/count,gpsSum/count)).apply();
            count=0;
        }
    }

    static String points(Context context,String source){
        SharedPreferences preferences=preferences(context);
        StringBuilder points=new StringBuilder();
        for(int bucket=FIRST_BUCKET;bucket<=LAST_BUCKET;bucket++){
            String pair=preferences.getString(source+"/"+bucket,null);
            if(pair!=null){
                if(points.length()>0)points.append('\n');
                points.append(pair);
            }
        }
        return points.toString();
    }

    private static SharedPreferences preferences(Context context){
        return context.getSharedPreferences("gps_speed_reference",Context.MODE_PRIVATE);
    }
}
