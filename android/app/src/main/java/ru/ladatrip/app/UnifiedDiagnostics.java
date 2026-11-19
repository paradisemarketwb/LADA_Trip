package ru.ladatrip.app;

import android.content.Context;
import org.json.JSONObject;

/** One privacy-safe diagnostic file instead of repeated scattered exports. */
public final class UnifiedDiagnostics {
    private UnifiedDiagnostics(){}
    public static JSONObject build(Context context){
        JSONObject root=new JSONObject();
        try{
            root.put("app","LADA Trip");
            root.put("version",context.getPackageManager()
                .getPackageInfo(context.getPackageName(),0).versionName);
            root.put("device",android.os.Build.MODEL);
            root.put("build",android.os.Build.DISPLAY);
            root.put("timestamp_ms",System.currentTimeMillis());
            root.put("read_only",true);
            root.put("privacy","No location coordinates, auth keys, tokens or server URLs.");
            JSONObject latest=new JSONObject();
            long now=System.currentTimeMillis();
            for(String key:VehicleLog.SIGNALS){
                JSONObject state=new JSONObject();
                String source=VehicleLog.source(context,key);
                long time=VehicleLog.at(context,key);
                state.put("value",VehicleLog.value(context,key));
                state.put("source",source);
                state.put("source_note",VehicleLog.note(context,key));
                state.put("at_ms",time);
                state.put("fresh",VehicleDisplayFormat.fresh(time,now,12000));
                state.put("last_visible_ui_only","ui".equals(source)&&!VehicleDisplayFormat.fresh(time,now,12000));
                latest.put(key,state);
            }
            root.put("vehicle",latest);
            root.put("car_service_state",VehicleLog.state(context));
            root.put("car_monitor_active",VehicleLog.running(context));
            root.put("gps_health",GpsHealth.snapshot(context));
            try(TripStore gps=new TripStore(context)){
                root.put("gps_diagnostics",new JSONObject(gps.exportDiagnostics(context)));
            }catch(Exception e){root.put("gps_diagnostics_error",e.getClass().getSimpleName());}
            try(CanJourneyStore trips=new CanJourneyStore(context)){
                root.put("can_journeys",trips.snapshot());
            }catch(Exception e){root.put("can_trips_error",e.getClass().getSimpleName());}
            root.put("elm_status",EngineElmService.state(context));
            String audit=context.getSharedPreferences("engine_audit",Context.MODE_PRIVATE)
                .getString("last_report",null);
            if(audit!=null){
                try{root.put("engine_access_audit",new JSONObject(audit));}
                catch(Exception ignored){}
            }
            root.put("source_notes",
                "Humax R2 CarProperty speed raw matches km/h; do NOT multiply by 3.6. "+ 
                "Most doors/steering only accessible while OEM CAN Information screen is visible. "+
                "RPM/coolant require CAR_ENGINE_DETAILED or explicitly paired ELM327. "+
                "CAN distance-only trips do not contain GPS coordinates.");
        }catch(Exception e){
            try{root.put("diagnostic_error",e.getClass().getSimpleName());}catch(Exception ignored){}
        }
        return root;
    }
}