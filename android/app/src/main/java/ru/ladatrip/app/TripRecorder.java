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
    public synchronized void take(Location location) {
        if(location==null || !location.hasAccuracy() || location.getAccuracy()>80 || location.getAccuracy()<0)return;
        if(Math.abs(System.currentTimeMillis()-location.getTime())>120000)return;
        TripStore.Fix fix=new TripStore.Fix(location.getTime(),location.getLatitude(),location.getLongitude(),location.getAccuracy());
        if(prior!=null && fix.time<=prior.time)return;
        if(tripId!=0 && prior!=null && fix.time-prior.time>RESTART_GAP_MS) finish(prior);
        double meters=prior==null?0:distance(prior,fix);
        double secs=prior==null?0:(fix.time-prior.time)/1000.0;
        // Reject a massive GPS jump and retain the current position as a new baseline.
        if(prior!=null && secs>0 && meters/secs>65){prior=fix;store.saveFix(fix);return;}
        boolean moving=(location.hasSpeed() && location.getSpeed()>=2.0f) || (secs>0 && secs<=30 && meters>25 && meters/secs>=2.0);
        if(tripId==0) {
            movingSamples=moving?movingSamples+1:0;
            if(movingSamples>=2) {
                tripId=store.startTrip(prior==null?fix.time:prior.time);
                store.addPoint(tripId,prior==null?fix:prior,location.getSpeed());
                store.queue("trip_start",fix,0,0);
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
            if(moving && prior!=null && secs>0 && secs<=120 && meters>=7 && meters/secs<=65)store.addDistance(tripId,meters);
            store.addPoint(tripId,fix,location.hasSpeed()?location.getSpeed():0);
            if(stationarySince!=0 && fix.time-stationarySince>=TRIP_END_STOP_MS) {
                if(!stopSaved){store.addStop(tripId,stationarySince,fix.time,fix);stopSaved=true;}
                finish(fix);
            }
        }
        prior=fix;
        store.saveFix(fix);
    }
    private static double distance(TripStore.Fix a,TripStore.Fix b) {
        float[] arr=new float[1];Location.distanceBetween(a.lat,a.lon,b.lat,b.lon,arr);return arr[0];
    }
    public synchronized void stop() {if(tripId!=0)finish(prior);}
    private void finish(TripStore.Fix fix) {
        if(tripId==0)return;
        long at=fix!=null?fix.time:System.currentTimeMillis();
        double dist=store.distance(tripId);long start=store.startTime(tripId);
        if(stationarySince!=0 && fix!=null && at-stationarySince>=60000 && !stopSaved)store.addStop(tripId,stationarySince,at,fix);
        store.endTrip(tripId,at);store.queue("trip_end",fix,dist,Math.max(0,(at-start)/1000));
        tripId=0;stationarySince=0;stopSaved=false;movingSamples=0;
        listener.onEvent("end");
    }
}
