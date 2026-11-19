package ru.ladatrip.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.graphics.Rect;
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
            List<HumaxCanTextParser.TextCell> cells=new ArrayList<>();
            collect(root,0,cells);
            // OEM displays identical strings for multiple doors; keep every text node.
            // Correlate by screen row, rather than treating the next section header as a value.
            Map<String,String> found=HumaxCanTextParser.parseCells(cells);
            List<String> latest=new ArrayList<>();
            for(Map.Entry<String,String> entry:found.entrySet()){
                VehicleLog.put(this,"ui",entry.getKey(),entry.getValue(),"Humax EngineeringMode: visible text");
                latest.add(entry.getKey()+" = "+entry.getValue());
            }
            synchronized(EngineeringAccessibilityService.class){
                lastAt=System.currentTimeMillis();lastNodeCount=cells.size();
                lastReadings.clear();lastReadings.addAll(latest);
                lastSnapshot=latest.isEmpty()?"Текст автомобиля не доступен этому экрану":
                    android.text.TextUtils.join("\n",latest);
            }
        }catch(Exception ex){
            android.util.Log.w("LadaVehicle","Accessibility read only scan failed",ex);
        }finally{root.recycle();}
    }
    private static void add(List<HumaxCanTextParser.TextCell> cells,
                            CharSequence text,Rect bounds){
        if(text==null||cells.size()>=250)return;
        String v=text.toString().replace('\r',' ').replace('\n',' ').trim();
        if(v.length()>0&&v.length()<=160){
            // Repeated "Close (1)" across different doors MUST NOT be de-duplicated.
            // De-duplicate only genuinely identical nodes with identical geometry.
            for(HumaxCanTextParser.TextCell existing:cells){
                if(existing.text.equals(v)&&existing.left==bounds.left&&existing.top==bounds.top
                    &&existing.right==bounds.right&&existing.bottom==bounds.bottom)return;
            }
            cells.add(new HumaxCanTextParser.TextCell(v,bounds.left,bounds.top,bounds.right,bounds.bottom));
        }
    }
    private static void collect(AccessibilityNodeInfo node,int depth,
                                List<HumaxCanTextParser.TextCell> cells){
        if(node==null||depth>16||cells.size()>=250)return;
        Rect bounds=new Rect();
        node.getBoundsInScreen(bounds);
        add(cells,node.getText(),bounds);
        add(cells,node.getContentDescription(),bounds);
        for(int i=0;i<node.getChildCount()&&cells.size()<250;i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try{collect(child,depth+1,cells);}
            finally{child.recycle();}
        }
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){handler.removeCallbacks(timer);super.onDestroy();}
}
