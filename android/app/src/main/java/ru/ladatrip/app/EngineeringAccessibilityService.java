package ru.ladatrip.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Only reads visible Humax EngineeringMode text after explicit Accessibility opt-in.
 * Never opens menus, clicks, changes OEM settings, or requests privileged CAN access.
 */
public final class EngineeringAccessibilityService extends AccessibilityService {
    private static final String TARGET="com.humaxdigital.avtovaz.engineeringmode";
    private static final Pattern LAST_NUMBER=Pattern.compile("([+-]?[0-9]+(?:[.,][0-9]+)?)\\s*\\)?\\s*$");
    private static final class Label {
        final String key,match;
        Label(String key,String match){this.key=key;this.match=match;}
    }
    private static final Label[] LABELS={
        new Label("STEER_RAW","trigonometric sense rotation"),
        new Label("REVERSE","rear gear engaged"),
        new Label("HANDBRAKE","parking brake on"),
        new Label("ENGINE","vehicle status"),
        new Label("ALARM","alarm status"),
        new Label("FUEL_LOW","fuel low"),
        new Label("FUEL_L","fuel level"),
        new Label("OUTSIDE_C","outside air temperature"),
        new Label("TAIL_GATE","tail gate"),
        new Label("HOOD","hood"),
        new Label("DRIVER_DOOR","driver door"),
        new Label("PASSENGER_DOOR","passenger door"),
        new Label("REAR_LEFT_DOOR","rear left door"),
        new Label("REAR_RIGHT_DOOR","rear right door"),
        new Label("SPEED","speed"),
        new Label("RPM","rpm"),
        new Label("ODOMETER","odometer"),
        new Label("ODOMETER","mileage")
    };
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long lastScanAt;
    private final Runnable timer=new Runnable(){
        @Override public void run(){
            scan();
            handler.postDelayed(this,900);
        }
    };
    private static String lastSnapshot="Инженерное меню ещё не открыто";
    private static long lastAt;
    private static final List<String> lastReadings=new ArrayList<>();
    private static int lastNodeCount;
    public static synchronized String snapshot(){
        if(lastAt==0)return "Нет данных инженерного меню";
        long age=Math.max(0,(System.currentTimeMillis()-lastAt)/1000);
        return (age>=12?"УСТАРЕВШЕЕ ":"Последний экран ")+age+" с назад · элементов "+lastNodeCount+
            "\n"+lastSnapshot;
    }
    public static synchronized JSONObject snapshotJson(){
        JSONObject o=new JSONObject();
        try{
            o.put("source_package",TARGET);o.put("capture_at_ms",lastAt);
            o.put("age_seconds",lastAt==0?JSONObject.NULL:Math.max(0,(System.currentTimeMillis()-lastAt)/1000));
            o.put("fresh",lastAt!=0&&System.currentTimeMillis()-lastAt<12000);
            o.put("text_node_count",lastNodeCount);
            o.put("matched_text",new JSONArray(lastReadings));
            o.put("note","Read-only accessible text from visible Humax EngineeringMode; no CAN read from UI in background.");
        }catch(Exception ignored){}
        return o;
    }
    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        handler.removeCallbacks(timer);
        handler.post(timer);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null||event.getPackageName()==null||!TARGET.contentEquals(event.getPackageName()))return;
        scan();
    }
    private void scan(){
        long tick=SystemClock.uptimeMillis();
        if(tick-lastScanAt<300)return;
        lastScanAt=tick;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return;
        try{
            CharSequence pkg=root.getPackageName();
            if(pkg==null||!TARGET.contentEquals(pkg))return;
            List<String> nodes=new ArrayList<>();
            collect(root,0,nodes);
            Map<String,String> found=new LinkedHashMap<>();
            for(int i=0;i<nodes.size();i++){
                String s=nodes.get(i);
                Label label=match(s);
                if(label==null)continue;
                String value=extract(s,label);
                if(value.isEmpty()&&i+1<nodes.size()&&match(nodes.get(i+1))==null){
                    String candidate=nodes.get(i+1);
                    if(candidate.length()<=80)value=candidate;
                }
                if(value.isEmpty())continue;
                if("STEER_RAW".equals(label.key)){
                    // Exact OEM text has e.g. "Unknown (42)"; only trailing number is usable.
                    Matcher m=LAST_NUMBER.matcher(value);
                    if(!m.find())continue;
                    value=m.group(1).replace(',','.');
                }
                if("SPEED".equals(label.key)||"FUEL_L".equals(label.key)||"OUTSIDE_C".equals(label.key)){
                    Matcher m=LAST_NUMBER.matcher(value);
                    if(m.find())value=m.group(1).replace(',','.');
                }
                // If values appear in separate nodes, source stays UI (visible only).
                found.put(label.key,value);
            }
            List<String> latest=new ArrayList<>();
            for(Map.Entry<String,String> entry:found.entrySet()){
                VehicleLog.put(this,"ui",entry.getKey(),entry.getValue(),"Humax EngineeringMode: visible text");
                latest.add(entry.getKey()+" = "+entry.getValue());
            }
            synchronized(EngineeringAccessibilityService.class){
                lastAt=System.currentTimeMillis();lastNodeCount=nodes.size();
                lastReadings.clear();lastReadings.addAll(latest);
                lastSnapshot=latest.isEmpty()?"Текст автомобиля не доступен этому экрану":
                    android.text.TextUtils.join("\n",latest);
            }
        }catch(Exception ex){
            android.util.Log.w("LadaVehicle","Accessibility read only scan failed",ex);
        }finally{root.recycle();}
    }
    private static Label match(String s){
        String t=s.toLowerCase(Locale.ROOT);
        // Counter rotation is a different signal. Never confuse it with steering angle.
        if(t.contains("counter trigonometric sense rotation"))return null;
        for(Label label:LABELS){
            if(label.match.equals("speed")){
                if(t.equals("speed")||t.startsWith("speed ")||t.startsWith("speed:"))
                    return label;
            }else if(t.contains(label.match))return label;
        }
        return null;
    }
    private static String extract(String text,Label label){
        String lower=text.toLowerCase(Locale.ROOT);
        int pos=lower.indexOf(label.match);
        if(pos<0)return "";
        return text.substring(pos+label.match.length()).replaceFirst("^[\\s:=\\-]+","").trim();
    }
    private static void add(List<String> list,CharSequence text){
        if(text==null||list.size()>=200)return;
        String s=text.toString().replace('\r',' ').replace('\n',' ').trim();
        if(s.length()>0&&s.length()<=160 && !list.contains(s))list.add(s);
    }
    private static void collect(AccessibilityNodeInfo node,int depth,List<String> nodes){
        if(node==null||depth>16||nodes.size()>=200)return;
        add(nodes,node.getText());
        add(nodes,node.getContentDescription());
        for(int i=0;i<node.getChildCount()&&nodes.size()<200;i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try{collect(child,depth+1,nodes);}finally{child.recycle();}
        }
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){handler.removeCallbacks(timer);super.onDestroy();}
}
