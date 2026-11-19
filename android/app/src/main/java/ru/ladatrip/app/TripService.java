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
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

public final class TripService extends Service implements LocationListener {
    public static final String ACTION_STOP="ru.ladatrip.app.STOP";
    private static final String CHANNEL="lada_trip_monitor";
    private static final String EVENTS="lada_trip_events";
    private static final int MONITOR_ID=175;
    private TripStore db;
    private TripRecorder recorder;
    private LocationManager location;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkAvailable=false,waitingForFreshFix=false;
    private boolean gpsSubscribed=false;
    private GnssStatus.Callback gnssCallback;
    private boolean gnssRegistered=false;

    @Override public void onCreate() {
        super.onCreate();
        GpsHealth.start(this);
        db=new TripStore(this);
        recorder=new TripRecorder(db,type->{
            notifyTrip(type);
            DeliveryJob.schedule(this);
        });
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"LADA Trip — GPS",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(EVENTS,"LADA Trip — поездки",NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(MONITOR_ID,createNotification("Ожидание координат GPS"));
        subscribeGps();
        subscribeNetwork();
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
            recorder.stop();Settings.monitor(this,false);DeliveryJob.schedule(this);stopForeground(true);stopSelf();return START_NOT_STICKY;
        }
        if(!Settings.monitor(this)) {stopSelf();return START_NOT_STICKY;}
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
            if(!location.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                GpsHealth.registration(this,"GPS_PROVIDER выключен");return;
            }
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER,5000L,3f,this,Looper.getMainLooper());
            gpsSubscribed=true;
            GpsHealth.registration(this,"GPS_PROVIDER: подписка зарегистрирована (5 с / 3 м)");
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
    private void subscribeNetwork() {
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(connectivity==null)return;
        networkCallback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n){new Handler(Looper.getMainLooper()).post(()->handleConnected());}
            @Override public void onCapabilitiesChanged(Network n,android.net.NetworkCapabilities caps) {
                // Captive portal / hotspot may transition to actually working internet later.
                if(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    new Handler(Looper.getMainLooper()).post(()->{handleConnected();DeliveryJob.schedule(TripService.this);});
            }
            @Override public void onLost(Network n){new Handler(Looper.getMainLooper()).post(()->{networkAvailable=false;waitingForFreshFix=false;});}
        };
        try {connectivity.registerDefaultNetworkCallback(networkCallback);}
        catch(Exception e){android.util.Log.w("LadaTrip","Network callback failed",e);}
    }
    private void handleConnected() {
        if(networkAvailable)return;
        networkAvailable=true;
        TripStore.Fix last=db.lastFix();
        long now=System.currentTimeMillis();
        // Persisted cooldown also guards against duplicate startup callbacks.
        long previous=0;
        try{previous=Long.parseLong(db.get("network_event_at"));}catch(Exception ignored){}
        if(now-previous>60000) {
            if(last!=null && now-last.time<30*60*1000L) {
                db.queue("location",last,0,0);
                db.put("network_event_at",Long.toString(now));
            } else waitingForFreshFix=true;
        }
        DeliveryJob.schedule(this);
    }
    @Override public void onLocationChanged(Location fix) {
        GpsHealth.raw(this,fix);
        if(fix==null){GpsHealth.rejected(this,"Нет объекта Location");return;}
        if(!fix.hasAccuracy()){GpsHealth.rejected(this,"Нет информации о точности GPS");return;}
        if(fix.getAccuracy()<0||fix.getAccuracy()>80){
            GpsHealth.rejected(this,"Точность хуже 80 м: "+Math.round(fix.getAccuracy())+" м");return;
        }
        long timeDifference=Math.abs(System.currentTimeMillis()-fix.getTime());
        if(timeDifference>120000){
            GpsHealth.rejected(this,"Устаревшая или будущая GPS-метка: "+timeDifference/1000+" с");return;
        }
        GpsHealth.accepted(this);
        recorder.take(fix);
        if(waitingForFreshFix && networkAvailable) {
            TripStore.Fix point=db.lastFix();
            if(point!=null) {
                db.queue("location",point,0,0);db.put("network_event_at",Long.toString(System.currentTimeMillis()));
                waitingForFreshFix=false;DeliveryJob.schedule(this);
            }
        }
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(MONITOR_ID,createNotification("GPS: ±"+Math.round(fix.getAccuracy())+" м"));
    }
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public void onProviderEnabled(String p){if(LocationManager.GPS_PROVIDER.equals(p))subscribeGps();}
    @Override public void onProviderDisabled(String p){if(LocationManager.GPS_PROVIDER.equals(p)){gpsSubscribed=false;GpsHealth.registration(this,"GPS_PROVIDER выключен");}}
    @Override public void onDestroy() {
        GpsHealth.stop(this);
        if(location!=null && gnssRegistered && gnssCallback!=null)
            try{location.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){}
        if(location!=null && gpsSubscribed)try{location.removeUpdates(this);}catch(Exception ignored){}
        if(connectivity!=null && networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        if(db!=null)db.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
