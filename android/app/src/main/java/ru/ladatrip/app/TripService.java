package ru.ladatrip.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GnssStatus;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

public final class TripService extends Service implements LocationListener {
    public static final String ACTION_STOP="ru.ladatrip.app.STOP";
    static final String ACTION_RECONNECT="ru.ladatrip.app.GPS_RECONNECT";
    private static final String CHANNEL="lada_trip_monitor";
    private static final String EVENTS="lada_trip_events";
    private static final int MONITOR_ID=175;
    private TripStore db;
    private TripRecorder recorder;
    private final GpsSpeedReference speedReference=new GpsSpeedReference();
    private LocationManager location;
    private boolean gpsSubscribed=false;
    private GnssStatus.Callback gnssCallback;
    private boolean gnssRegistered=false;

    @Override public void onCreate() {
        super.onCreate();
        GpsHealth.start(this);
        db=new TripStore(this);
        recorder=new TripRecorder(db,this::notifyTrip);
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"LADA Trip — GPS",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(EVENTS,"LADA Trip — поездки",NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(MONITOR_ID,createNotification("Ожидание координат GPS"));
        subscribeGps();
        if(getSharedPreferences("vehicle_live_settings",MODE_PRIVATE).getBoolean("auto_with_gps",false)){
            try{startForegroundService(new Intent(this,VehicleLiveService.class));}
            catch(Exception e){android.util.Log.w("LadaTrip","Vehicle monitor autostart failed",e);}
        }
    }
    private Notification createNotification(String status) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setContentTitle("LADA Trip · запись поездок")
            .setContentText(status).setSmallIcon(ru.ladatrip.app.R.drawable.ic_trip)
            .setOngoing(true).setContentIntent(pi).build();
    }
    private void notifyTrip(String type) {
        String text="start".equals(type)?"Поездка началась":"Поездка завершена";
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify("trip-"+type,(int)(System.currentTimeMillis()&0xFFFFFF),
            new Notification.Builder(this,EVENTS).setSmallIcon(R.drawable.ic_trip).setContentTitle("LADA Trip").setContentText(text).setAutoCancel(true).build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && ACTION_STOP.equals(intent.getAction())) {
            recorder.stop();Settings.monitor(this,false);stopForeground(true);stopSelf();return START_NOT_STICKY;
        }
        if(!Settings.monitor(this)) {stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&ACTION_RECONNECT.equals(intent.getAction())&&location!=null){
            if(gpsSubscribed)location.removeUpdates(this);
            if(gnssRegistered)location.unregisterGnssStatusCallback(gnssCallback);
            gpsSubscribed=false;
            gnssRegistered=false;
            GpsHealth.start(this);
        }
        subscribeGps();
        return START_STICKY;
    }
    private void subscribeGps() {
        if(gpsSubscribed)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            GpsHealth.registration(this,"Нет разрешения ACCESS_FINE_LOCATION");return;
        }
        location=(LocationManager)getSystemService(LOCATION_SERVICE);
        if(location==null){GpsHealth.registration(this,"LocationManager недоступен");return;}
        try{
            long interval=GpsSettingsActivity.preferences(this).getBoolean("frequent",false)?2000:3000;
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER,interval,0f,this,Looper.getMainLooper());
            gpsSubscribed=true;
            updateGpsRegistration();
            subscribeGnss();
        }catch(Exception e){
            GpsHealth.registration(this,"Ошибка GPS: "+e.getClass().getSimpleName());
            android.util.Log.w("LadaTrip","GPS registration failed",e);
        }
    }
    private void subscribeGnss(){
        if(gnssRegistered||location==null)return;
        try{
            if(gnssCallback==null){
                gnssCallback=new GnssStatus.Callback(){
                    @Override public void onStarted(){GpsHealth.gnss(TripService.this,"GNSS запущен");}
                    @Override public void onStopped(){GpsHealth.gnss(TripService.this,"GNSS остановлен");}
                    @Override public void onFirstFix(int ttffMillis){
                        GpsHealth.gnss(TripService.this,"Первая фиксация GPS через "+ttffMillis+" мс");
                    }
                    @Override public void onSatelliteStatusChanged(GnssStatus status){
                        GpsHealth.satelliteUpdate(TripService.this,status);
                    }
                };
            }
            gnssRegistered=location.registerGnssStatusCallback(gnssCallback,new Handler(Looper.getMainLooper()));
            if(!gnssRegistered)GpsHealth.gnss(this,"GNSS status callback не зарегистрирован");
        }catch(Exception e){GpsHealth.gnss(this,"GNSS status недоступен: "+e.getClass().getSimpleName());}
    }
    @Override public void onLocationChanged(Location fix) {
        GpsHealth.raw(this,fix);
        if(fix==null){GpsHealth.rejected(this,"Нет объекта Location");return;}
        if(!fix.hasAccuracy()){GpsHealth.rejected(this,"Нет информации о точности GPS");return;}
        int accuracyLimit=GpsSettingsActivity.preferences(this).getBoolean("strict",false)?30:150;
        if(!Float.isFinite(fix.getAccuracy())||fix.getAccuracy()<0||fix.getAccuracy()>accuracyLimit){
            GpsHealth.rejected(this,"Неподходящая точность GPS (порог "+accuracyLimit+" м)");return;
        }
        if(fix.isFromMockProvider()){
            GpsHealth.rejected(this,"Точка от тестового провайдера");return;
        }
        long ageNanos=android.os.SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos();
        if(fix.getElapsedRealtimeNanos()<=0||ageNanos<0||ageNanos>25_000_000_000L){
            GpsHealth.rejected(this,"Устаревшая GPS-метка");return;
        }
        try{
            if(!recorder.take(fix)){
                GpsHealth.rejected(this,"Некорректная, повторная или скачкообразная GPS-точка");return;
            }
        }catch(android.database.sqlite.SQLiteException error){
            GpsHealth.rejected(this,"Ошибка записи SQLite: "+error.getClass().getSimpleName());
            return;
        }
        GpsHealth.accepted(this,fix);
        if(fix.getAccuracy()<=80)speedReference.observe(this,fix);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(MONITOR_ID,createNotification("GPS: ±"+Math.round(fix.getAccuracy())+" м"));
    }
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    private void updateGpsRegistration(){
        GpsHealth.registration(this,location.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ?"GPS_PROVIDER: подписка зарегистрирована"
            :"GPS_PROVIDER выключен · ожидание включения");
    }
    @Override public void onProviderEnabled(String p){
        if(LocationManager.GPS_PROVIDER.equals(p))updateGpsRegistration();
    }
    @Override public void onProviderDisabled(String p){
        if(LocationManager.GPS_PROVIDER.equals(p)){
            GpsHealth.clearSpeed(this);
            updateGpsRegistration();
        }
    }
    @Override public void onDestroy() {
        GpsHealth.stop(this);
        if(location!=null && gnssRegistered && gnssCallback!=null)
            try{location.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){}
        if(location!=null && gpsSubscribed)try{location.removeUpdates(this);}catch(Exception ignored){}
        if(db!=null)db.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
