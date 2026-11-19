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
import java.util.List;
import java.util.Map;

/**
 * Only reads visible Humax EngineeringMode text after explicit Accessibility opt-in.
 * Never opens menus, clicks, changes OEM settings, or requests privileged CAN access.
 */
public final class EngineeringAccessibilityService extends AccessibilityService {
    private static final String TARGET="com.humaxdigital.avtovaz.engineeringmode";
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
            // Only accept verified label/value pairs; never treat headers as door states.
            Map<String,String> found=HumaxCanTextParser.parse(nodes);
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
