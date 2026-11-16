package ru.ladatrip.canprobe;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads only on-screen text from the Humax engineering UI.
 * Scans periodically while EngineeringMode is foreground, even when its text changes silently.
 * Never performs clicks, gestures, key events, screenshots, or privileged CAN operations.
 */
public final class MenuReaderService extends AccessibilityService {
    private static final String TARGET="com.humaxdigital.avtovaz.engineeringmode";
    private static final int NODE_CAP=200, DEPTH_CAP=16;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long lastScan;
    private final Runnable scanTick=new Runnable(){
        @Override public void run(){
            if(CaptureStore.active(MenuReaderService.this))scan();
            handler.postDelayed(this,900);
        }
    };
    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        handler.removeCallbacks(scanTick);
        handler.post(scanTick);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null||event.getPackageName()==null||!TARGET.contentEquals(event.getPackageName()))return;
        if(!CaptureStore.active(this))return;
        scan();
    }
    private void scan(){
        long now=SystemClock.uptimeMillis();
        if(now-lastScan<350)return;
        lastScan=now;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return;
        try{
            if(root.getPackageName()==null||!TARGET.contentEquals(root.getPackageName()))return;
            List<String> nodes=new ArrayList<>();
            collect(root,0,nodes);
            CaptureStore.count(this,"accessibility_scans");
            // Record even when no relevant text is accessible.
            CaptureStore.event(this,"UI","WINDOW","VISIBLE", "Humax EngineeringMode foreground");
            CaptureStore.event(this,"UI","TEXT_COUNT",String.valueOf(nodes.size()),"Readable text and descriptions in active window");
            if(nodes.isEmpty()){
                CaptureStore.event(this,"UI","ACCESS","NO_ACCESSIBLE_TEXT","Screen could use Canvas/native rendering");
                return;
            }
            CaptureStore.event(this,"UI","ACCESS","TEXT_AVAILABLE","AccessibilityNodeInfo.getText/getContentDescription");
            Map<String,String> matches=new LinkedHashMap<>();
            for(int i=0;i<nodes.size();i++){
                String line=nodes.get(i);
                String signal=signalFor(line);
                if(signal==null)continue;
                String value=valueFromLabel(line,signal);
                String detail="text element";
                if(value.isEmpty()){
                    String neighbor=i+1<nodes.size()?nodes.get(i+1):"";
                    if(isPossibleValue(neighbor)){
                        value=neighbor;
                        detail="NEXT TEXT NODE: unverified label/value association";
                    }else if(i>0 && isPossibleValue(nodes.get(i-1))){
                        value=nodes.get(i-1);
                        detail="PREVIOUS TEXT NODE: unverified label/value association";
                    }else{
                        value="LABEL_ONLY";
                        detail="Field exists, value not exposed as accessible text";
                    }
                }
                // A node that contains only an English label may have separate sibling values.
                matches.put(signal,value);
                CaptureStore.event(this,"UI",signal,value,detail+" | "+line);
            }
            CaptureStore.event(this,"UI","FOUND_SIGNALS",String.valueOf(matches.size()),
                "Counts text labels recognized on current EngineeringMode screen");
        }catch(Exception e){
            CaptureStore.event(this,"UI","ERROR",e.getClass().getSimpleName(),e.getMessage());
        }finally{root.recycle();}
    }
    private static boolean isPossibleValue(String s){
        if(s==null||s.isEmpty()||s.length()>60)return false;
        String t=s.toLowerCase(Locale.ROOT);
        return s.matches("[-+]?\\d[\\d\\s.,]*(?:\\s*(?:km|км|rpm|об/мин))?")
            ||t.contains("unknown")||t.contains("running")
            ||t.contains("applied")||t.contains("engaged")
            ||t.contains("not engaged")||t.contains("open")||t.contains("close")
            ||t.contains("false")||t.contains("true");
    }
    private static String signalFor(String s){
        String t=s.toLowerCase(Locale.ROOT);
        if(t.contains("counter trigonometric sense rotation"))return "STEER_COUNTER_ROTATION";
        if(t.contains("trigonometric sense rotation"))return "STEER_TRIG_ROTATION";
        if(t.contains("rear gear engaged"))return "REVERSE_GEAR";
        if(t.contains("parking brake on"))return "PARKING_BRAKE";
        if(t.contains("vehicle status")||t.contains("engine running"))return "VEHICLE_STATUS";
        if(t.contains("alarm status"))return "ALARM_STATUS";
        if(t.contains("fuel low"))return "FUEL_LOW";
        if(t.contains("fuel level"))return "FUEL_LEVEL";
        if(t.contains("outside air temperature"))return "OUTSIDE_TEMP";
        if(t.contains("odometer")||t.contains("mileage")||t.contains("одометр")||t.contains("пробег"))return "ODOMETER";
        if(t.contains("rpm")||t.contains("обороты")||t.contains("об/мин"))return "ENGINE_RPM";
        if(t.equals("speed")||t.startsWith("speed ")||t.contains("vehicle speed")||t.contains("скорость"))return "VEHICLE_SPEED";
        if(t.contains("tail gate"))return "TAIL_GATE";
        if(t.contains("hood door"))return "HOOD";
        if(t.contains("driver door"))return "DRIVER_DOOR";
        if(t.contains("passenger door"))return "PASSENGER_DOOR";
        if(t.contains("rear left door"))return "REAR_LEFT_DOOR";
        if(t.contains("rear right door"))return "REAR_RIGHT_DOOR";
        return null;
    }
    private static String valueFromLabel(String s,String signal){
        String lower=s.toLowerCase(Locale.ROOT);
        String[] labels;
        switch(signal){
            case "STEER_COUNTER_ROTATION":labels=new String[]{"counter trigonometric sense rotation"};break;
            case "STEER_TRIG_ROTATION":labels=new String[]{"trigonometric sense rotation"};break;
            case "REVERSE_GEAR":labels=new String[]{"rear gear engaged"};break;
            case "PARKING_BRAKE":labels=new String[]{"parking brake on"};break;
            case "VEHICLE_STATUS":labels=new String[]{"vehicle status","engine running"};break;
            case "ALARM_STATUS":labels=new String[]{"alarm status"};break;
            case "FUEL_LOW":labels=new String[]{"fuel low"};break;
            case "FUEL_LEVEL":labels=new String[]{"fuel level"};break;
            case "OUTSIDE_TEMP":labels=new String[]{"outside air temperature"};break;
            case "ODOMETER":labels=new String[]{"odometer","mileage","одометр","пробег"};break;
            case "ENGINE_RPM":labels=new String[]{"rpm","обороты","об/мин"};break;
            case "VEHICLE_SPEED":labels=new String[]{"vehicle speed","speed","скорость"};break;
            case "TAIL_GATE":labels=new String[]{"tail gate"};break;
            case "HOOD":labels=new String[]{"hood door"};break;
            case "DRIVER_DOOR":labels=new String[]{"driver door"};break;
            case "PASSENGER_DOOR":labels=new String[]{"passenger door"};break;
            case "REAR_LEFT_DOOR":labels=new String[]{"rear left door"};break;
            case "REAR_RIGHT_DOOR":labels=new String[]{"rear right door"};break;
            default:return "";
        }
        for(String label:labels){
            int i=lower.indexOf(label);
            if(i>=0)return s.substring(i+label.length()).replaceFirst("^[\\s:=\\-]+","").trim();
        }
        return "";
    }
    private static void add(List<String> a,CharSequence s){
        if(s==null||a.size()>=NODE_CAP)return;
        String x=s.toString().replace('\r',' ').replace('\n',' ').trim();
        if(x.length()>0&&x.length()<180&&!a.contains(x))a.add(x);
    }
    private static void collect(AccessibilityNodeInfo node,int depth,List<String> a){
        if(node==null||depth>DEPTH_CAP||a.size()>=NODE_CAP)return;
        add(a,node.getText());add(a,node.getContentDescription());
        for(int i=0;i<node.getChildCount()&&a.size()<NODE_CAP;i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try{collect(child,depth+1,a);}finally{child.recycle();}
        }
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){
        handler.removeCallbacks(scanTick);
        super.onDestroy();
    }
}
