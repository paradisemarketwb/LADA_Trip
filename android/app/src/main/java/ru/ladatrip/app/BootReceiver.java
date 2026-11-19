package ru.ladatrip.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;

/**
 * Restores previously enabled recording after normal Android boot.
 * Does not start any firmware/engineering activities and never writes to CAN.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))return;
        if(!Settings.autoStart(context))return;
        // Vehicle speed/ignition work independently even if GNSS is unavailable.
        try{context.startForegroundService(new Intent(context,VehicleLiveService.class));}
        catch(Exception e){android.util.Log.w("LadaTrip","Vehicle autoboot unavailable",e);}
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            return;
        LocationManager lm=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        if(lm==null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER))return;
        Settings.monitor(context,true);
        try{context.startForegroundService(new Intent(context,TripService.class));}
        catch(Exception e){android.util.Log.w("LadaTrip","GPS autoboot unavailable",e);}
    }
}
