package ru.ladatrip.app;

import android.location.Location;

/** GPS trip state machine. No network access, no ignition/CAN bus assumptions. */
public final class TripRecorder {
    public interface Listener { void onEvent(String event); }
    private final TripStore store;
    private final Listener listener;
    private long tripId;
    private TripStore.Fix prior;
    private long stationarySince;
    private int movingSamples;
    private boolean stopSaved;
    private static final long TRIP_END_STOP_MS=8*60*1000L;
    private static final long RESTART_GAP_MS=15*60*1000L;

    public TripRecorder(TripStore store,Listener listener) {
        this.store=store;this.listener=listener;
        this.tripId=store.openTrip();
        this.prior=store.lastFix();
        // After a long power-off, close an unfinished trip at the last REAL GPS fix.
        if(tripId!=0 && prior!=null && System.currentTimeMillis()-prior.time>RESTART_GAP_MS) finish(prior);
    }
    public synchronized boolean take(Location location) {
        double lat=location.getLatitude(),lon=location.getLongitude();
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return false;
        TripStore.Fix fix=new TripStore.Fix(location.getTime(),lat,lon,location.getAccuracy());
        if(prior!=null && fix.time<=prior.time)return false;
        if(tripId!=0 && prior!=null && fix.time-prior.time>RESTART_GAP_MS) finish(prior);
        boolean continuous=prior!=null && fix.time-prior.time<=120000;
        double meters=continuous?distance(prior,fix):0;
        double secs=continuous?(fix.time-prior.time)/1000.0:0;
        // A rejected jump can't become the baseline for distance or last known location.
        if(continuous && !VehicleDisplayFormat.plausibleGpsStep(meters,secs,prior.accuracy,fix.accuracy))
            return false;
        if(!continuous){stationarySince=0;stopSaved=false;movingSamples=0;}
        float speed=location.hasSpeed()&&Float.isFinite(location.getSpeed())&&location.getSpeed()>=0
            ?location.getSpeed():-1f;
        boolean speedReliable=speed>=0&&(!location.hasSpeedAccuracy()||
            location.getSpeedAccuracyMetersPerSecond()<=2.5f);
        boolean goodPosition=continuous&&prior.accuracy<=80&&fix.accuracy<=80;
        boolean moving=(speedReliable&&speed>=2.0f) ||
            (goodPosition&&secs<=30&&meters>Math.max(25,fix.accuracy+prior.accuracy)&&meters/secs>=2.0);
        if(tripId==0) {
            movingSamples=moving?movingSamples+1:0;
            if(movingSamples>=2) {
                tripId=store.startTrip(prior);
                stationarySince=0;stopSaved=false;movingSamples=0;
                listener.onEvent("start");
            }
        }
        if(tripId!=0) {
            if(moving) {
                if(stationarySince!=0) {
                    if(!stopSaved && prior!=null && fix.time-stationarySince>=60000)store.addStop(tripId,stationarySince,fix.time,prior);
                    stationarySince=0;stopSaved=false;
                }
            } else if(stationarySince==0) {stationarySince=fix.time;stopSaved=false;}
            store.addPoint(tripId,fix,speedReliable?speed:-1,
                moving&&goodPosition&&meters>=7&&meters/secs<=65?meters:0);
            if(stationarySince!=0 && fix.time-stationarySince>=TRIP_END_STOP_MS) {
                if(!stopSaved){store.addStop(tripId,stationarySince,fix.time,fix);stopSaved=true;}
                finish(fix);
            }
        }
        prior=fix;
        store.saveFix(fix);
        return true;
    }
    private static double distance(TripStore.Fix a,TripStore.Fix b) {
        float[] arr=new float[1];Location.distanceBetween(a.lat,a.lon,b.lat,b.lon,arr);return arr[0];
    }
    public synchronized void stop() {if(tripId!=0)finish(prior);}
    private void finish(TripStore.Fix fix) {
        if(tripId==0)return;
        long at=fix!=null?fix.time:System.currentTimeMillis();
        if(stationarySince!=0 && fix!=null && at-stationarySince>=60000 && !stopSaved)store.addStop(tripId,stationarySince,at,fix);
        store.endTrip(tripId,at);
        tripId=0;stationarySince=0;stopSaved=false;movingSamples=0;
        listener.onEvent("end");
    }
}
