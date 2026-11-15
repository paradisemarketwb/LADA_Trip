package ru.ladatrip.app;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))return;
        if(!Settings.autoStart(context))return;
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
        Settings.monitor(context,true);
        context.startForegroundService(new Intent(context,TripService.class));
        DeliveryJob.schedule(context);
    }
}
