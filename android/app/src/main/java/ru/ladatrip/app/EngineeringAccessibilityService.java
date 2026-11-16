package ru.ladatrip.app;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opt-in text-only probe. Inspect Humax EngineeringMode and no other package.
 * Extract only labelled vehicle readings and cautious nearby numeric candidates.
 * No clicks, screenshots, commands, network traffic or persistent GPS linkage.
 */
public final class EngineeringAccessibilityService extends AccessibilityService {
    private static final String TARGET="com.humaxdigital.avtovaz.engineeringmode";
    private static final long FRESH_MS=10*60*1000L;
    private static final int MAX_NODES=120, MAX_MATCHES=40, MAX_DEPTH=14;
    private static final Pattern NUMBER=Pattern.compile(
        "^[+\\-]?[0-9][0-9\\s.,]*(?:км|km|km/h|км/ч|об/мин|rpm)?$", Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static String lastSnapshot="Инженерное меню ещё не открыто";
    private static long lastAt;
    private static int lastNodeCount, lastMatchedCount, lastEventType;
    private static final List<String> lastMatched=new ArrayList<>();
    private static final List<String> lastCandidates=new ArrayList<>();

    public static synchronized String snapshot() {
        if(lastAt==0)return "Нет данных: откройте штатное инженерное меню и затем вернитесь в LADA Trip";
        long age=(System.currentTimeMillis()-lastAt)/1000;
        return (age>FRESH_MS/1000?"УСТАРЕВШИЕ ДАННЫЕ ("+age+" с)":"Последнее чтение "+age+" с назад")
            +" · текстовых элементов "+lastNodeCount+" · совпадений "+lastMatchedCount+"\n"+lastSnapshot;
    }

    public static synchronized JSONObject snapshotJson() {
        JSONObject result=new JSONObject();
        try {
            result.put("source_package",TARGET);
            result.put("capture_at_ms",lastAt);
            result.put("age_seconds",lastAt==0?JSONObject.NULL:Math.max(0,(System.currentTimeMillis()-lastAt)/1000));
            result.put("fresh",lastAt!=0&&System.currentTimeMillis()-lastAt<=FRESH_MS);
            result.put("text_node_count",lastNodeCount);
            result.put("match_count",lastMatchedCount);
            result.put("event_type",lastEventType);
            result.put("matched_text",new JSONArray(lastMatched));
            result.put("adjacent_numeric_candidates_unverified",new JSONArray(lastCandidates));
            result.put("note","Only accessible text from Humax EngineeringMode. Adjacent values are unverified, not ECU telemetry.");
        } catch(Exception ignored){}
        return result;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event==null || event.getPackageName()==null || !TARGET.contentEquals(event.getPackageName()))return;
        int kind=event.getEventType();
        if(kind!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            kind!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return;
        try {
            CharSequence pkg=root.getPackageName();
            if(pkg==null||!TARGET.contentEquals(pkg))return;
            List<String> nodes=new ArrayList<>();
            visit(root,0,nodes);
            Set<String> matched=new LinkedHashSet<>();
            Set<String> candidates=new LinkedHashSet<>();
            for(int i=0;i<nodes.size();i++){
                String label=nodes.get(i);
                if(!interesting(label))continue;
                matched.add(label);
                if(i+1<nodes.size() && NUMBER.matcher(nodes.get(i+1)).matches()){
                    candidates.add(label+"  →  "+nodes.get(i+1)+" [рядом, не подтверждено]");
                }
                if(i>0 && NUMBER.matcher(nodes.get(i-1)).matches()){
                    candidates.add(label+"  ←  "+nodes.get(i-1)+" [рядом, не подтверждено]");
                }
                if(matched.size()>=MAX_MATCHES)break;
            }
            synchronized(EngineeringAccessibilityService.class){
                lastAt=System.currentTimeMillis();
                lastEventType=kind;
                lastNodeCount=nodes.size();
                lastMatchedCount=matched.size();
                lastMatched.clear();lastMatched.addAll(matched);
                lastCandidates.clear();lastCandidates.addAll(candidates);
                if(matched.isEmpty()){
                    lastSnapshot="Экран получен, но текст RPM/одометра и других сигналов не обнаружен";
                }else{
                    List<String> all=new ArrayList<>(matched);
                    if(!candidates.isEmpty()){
                        all.add("----- ПРЕДПОЛОЖИТЕЛЬНЫЕ СОСЕДНИЕ ЗНАЧЕНИЯ -----");
                        all.addAll(candidates);
                    }
                    lastSnapshot=android.text.TextUtils.join("\n",all);
                }
            }
        }finally{root.recycle();}
    }

    private static boolean interesting(String text) {
        String t=text.toLowerCase(Locale.ROOT);
        return t.contains("rpm")||t.contains("обор")||t.contains("тахометр")
            ||t.contains("engine speed")||t.contains("об/мин")
            ||t.contains("odometer")||t.contains("mileage")||t.contains("одометр")
            ||t.contains("пробег")||t.contains("километраж")||t.contains("total distance")
            ||t.matches(".*\\bodo\\b.*")
            ||t.contains("speed")||t.contains("скорост")||t.contains("gear")
            ||t.contains("ignition")||t.contains("зажиган")||t.contains("передач")
            ||t.contains("рул")||t.contains("can ");
    }

    private static void addText(List<String> values,CharSequence input){
        if(input==null||values.size()>=MAX_NODES)return;
        String t=input.toString().replace('\n',' ').replace('\r',' ').trim();
        if(t.length()>0&&t.length()<=140&&!values.contains(t))values.add(t);
    }

    private void visit(AccessibilityNodeInfo node,int depth,List<String> values){
        if(node==null||depth>MAX_DEPTH||values.size()>=MAX_NODES)return;
        addText(values,node.getText());
        CharSequence description=node.getContentDescription();
        if(description!=null && (node.getText()==null||!description.toString().contentEquals(node.getText())))
            addText(values,description);
        for(int i=0;i<node.getChildCount()&&values.size()<MAX_NODES;i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try{visit(child,depth+1,values);}finally{child.recycle();}
        }
    }

    @Override public void onInterrupt(){}
}
